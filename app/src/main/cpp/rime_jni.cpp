#include <jni.h>
#include <rime_api.h>

#include <mutex>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

namespace {

std::mutex g_mu;
RimeApi *g_api = nullptr;
RimeSessionId g_session = 0;
RimeSessionId g_lookup = 0;
bool g_started = false;
std::string g_shared;
std::string g_user;
std::string g_log;
std::string g_version;

jclass g_snap_class = nullptr;
jmethodID g_snap_ctor = nullptr;
jclass g_cand_class = nullptr;
jmethodID g_cand_ctor = nullptr;

void mkdirs(const std::string &path) {
    if (path.empty()) return;
    std::string cur;
    for (size_t i = 0; i < path.size(); ++i) {
        cur.push_back(path[i]);
        if (path[i] == '/' || i + 1 == path.size()) {
            if (cur.size() > 1) mkdir(cur.c_str(), 0700);
        }
    }
}

bool file_exists(const std::string &path) {
    return access(path.c_str(), F_OK) == 0;
}

std::string jstring_to_std(JNIEnv *env, jstring value) {
    if (!value) return {};
    const char *utf = env->GetStringUTFChars(value, nullptr);
    std::string out = utf ? utf : "";
    if (utf) env->ReleaseStringUTFChars(value, utf);
    return out;
}

jstring std_to_jstring(JNIEnv *env, const std::string &value) {
    return env->NewStringUTF(value.c_str());
}

jstring c_to_jstring(JNIEnv *env, const char *value) {
    return env->NewStringUTF(value ? value : "");
}

void cache_classes(JNIEnv *env) {
    if (g_snap_ctor) return;
    jclass snap = env->FindClass("cn/kylebing/meifeng/wubi/engine/RimeSnapshot");
    jclass cand = env->FindClass("cn/kylebing/meifeng/wubi/engine/RimeCandidate");
    if (!snap || !cand) return;
    g_snap_class = reinterpret_cast<jclass>(env->NewGlobalRef(snap));
    g_cand_class = reinterpret_cast<jclass>(env->NewGlobalRef(cand));
    g_snap_ctor = env->GetMethodID(
            g_snap_class,
            "<init>",
            "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;ZZ)V");
    g_cand_ctor = env->GetMethodID(
            g_cand_class, "<init>", "(Ljava/lang/String;Ljava/lang/String;)V");
    env->DeleteLocalRef(snap);
    env->DeleteLocalRef(cand);
}

struct Snap {
    std::string commit;
    std::string preedit;
    std::vector<std::string> texts;
    std::vector<std::string> comments;
    bool composing = false;
    bool ascii = false;
};

void destroy_lookup() {
    if (g_lookup && g_api) g_api->destroy_session(g_lookup);
    g_lookup = 0;
}

RimeSessionId ensure_lookup() {
    if (g_lookup) return g_lookup;
    if (!g_started || !g_api) return 0;
    g_lookup = g_api->create_session();
    if (g_lookup) g_api->select_schema(g_lookup, "wubi86_jidian");
    return g_lookup;
}

void append_commit(RimeSessionId sid, std::vector<std::string> &texts) {
    RIME_STRUCT(RimeCommit, commit);
    if (!g_api->get_commit(sid, &commit)) return;
    if (commit.text && commit.text[0]) {
        std::string text = commit.text;
        bool seen = false;
        for (const auto &item : texts) {
            if (item == text) {
                seen = true;
                break;
            }
        }
        if (!seen) texts.push_back(text);
    }
    g_api->free_commit(&commit);
}

Snap take_context(bool take_commit) {
    Snap snap;
    if (!g_started || !g_session || !g_api) return snap;

    if (take_commit) {
        RIME_STRUCT(RimeCommit, commit);
        if (g_api->get_commit(g_session, &commit)) {
            if (commit.text) snap.commit = commit.text;
            g_api->free_commit(&commit);
        }
    }

    RIME_STRUCT(RimeContext, ctx);
    if (g_api->get_context(g_session, &ctx)) {
        if (ctx.composition.preedit) snap.preedit = ctx.composition.preedit;
        for (int i = 0; i < ctx.menu.num_candidates; ++i) {
            const auto &cand = ctx.menu.candidates[i];
            snap.texts.emplace_back(cand.text ? cand.text : "");
            snap.comments.emplace_back(cand.comment ? cand.comment : "");
        }
        g_api->free_context(&ctx);
    }

    RIME_STRUCT(RimeStatus, status);
    if (g_api->get_status(g_session, &status)) {
        snap.composing = status.is_composing;
        snap.ascii = status.is_ascii_mode;
        g_api->free_status(&status);
    }
    return snap;
}

jobject snap_to_java(JNIEnv *env, const Snap &snap) {
    cache_classes(env);
    if (!g_snap_ctor) return nullptr;
    jobjectArray texts = env->NewObjectArray(
            static_cast<jsize>(snap.texts.size()), env->FindClass("java/lang/String"), nullptr);
    jobjectArray comments = env->NewObjectArray(
            static_cast<jsize>(snap.comments.size()), env->FindClass("java/lang/String"), nullptr);
    for (size_t i = 0; i < snap.texts.size(); ++i) {
        jstring text = std_to_jstring(env, snap.texts[i]);
        env->SetObjectArrayElement(texts, static_cast<jsize>(i), text);
        env->DeleteLocalRef(text);
    }
    for (size_t i = 0; i < snap.comments.size(); ++i) {
        jstring comment = std_to_jstring(env, snap.comments[i]);
        env->SetObjectArrayElement(comments, static_cast<jsize>(i), comment);
        env->DeleteLocalRef(comment);
    }
    jstring commit = std_to_jstring(env, snap.commit);
    jstring preedit = std_to_jstring(env, snap.preedit);
    jobject obj = env->NewObject(
            g_snap_class, g_snap_ctor, commit, preedit, texts, comments,
            static_cast<jboolean>(snap.composing), static_cast<jboolean>(snap.ascii));
    env->DeleteLocalRef(commit);
    env->DeleteLocalRef(preedit);
    env->DeleteLocalRef(texts);
    env->DeleteLocalRef(comments);
    return obj;
}

std::vector<std::pair<std::string, std::string>> all_candidates() {
    std::vector<std::pair<std::string, std::string>> list;
    if (!g_started || !g_session || !g_api || !g_api->candidate_list_begin) {
        Snap snap = take_context(false);
        for (size_t i = 0; i < snap.texts.size(); ++i) {
            list.emplace_back(snap.texts[i], i < snap.comments.size() ? snap.comments[i] : "");
        }
        return list;
    }
    RimeCandidateListIterator it{};
    if (!g_api->candidate_list_begin(g_session, &it)) {
        Snap snap = take_context(false);
        for (size_t i = 0; i < snap.texts.size(); ++i) {
            list.emplace_back(snap.texts[i], i < snap.comments.size() ? snap.comments[i] : "");
        }
        return list;
    }
    while (g_api->candidate_list_next(&it)) {
        list.emplace_back(
                it.candidate.text ? it.candidate.text : "",
                it.candidate.comment ? it.candidate.comment : "");
        if (list.size() >= 120) break;
    }
    g_api->candidate_list_end(&it);
    return list;
}

bool recreate_session() {
    destroy_lookup();
    if (g_session && g_api) {
        g_api->destroy_session(g_session);
        g_session = 0;
    }
    if (!g_api) return false;
    g_session = g_api->create_session();
    if (!g_session) return false;
    g_api->select_schema(g_session, "wubi86_jidian");
    return true;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_cn_kylebing_meifeng_wubi_engine_RimeNative_start(
        JNIEnv *env, jobject, jstring shared_dir, jstring user_dir, jstring log_dir,
        jstring version, jboolean force_full) {
    std::lock_guard<std::mutex> lock(g_mu);
    cache_classes(env);
    if (!g_api) g_api = rime_get_api();
    if (!g_api) return env->NewStringUTF("rime_get_api failed");
    if (g_started) return nullptr;

    g_shared = jstring_to_std(env, shared_dir);
    g_user = jstring_to_std(env, user_dir);
    g_log = jstring_to_std(env, log_dir);
    g_version = jstring_to_std(env, version);
    if (g_version.empty()) g_version = "1.0.0";
    mkdirs(g_user);
    mkdirs(g_log);

    const std::string table = g_user + "/build/wubi86_jidian.table.bin";
    bool do_full = force_full == JNI_TRUE || !file_exists(table);

    RIME_STRUCT(RimeTraits, traits);
    traits.shared_data_dir = g_shared.c_str();
    traits.user_data_dir = g_user.c_str();
    traits.distribution_name = "玫枫五笔";
    traits.distribution_code_name = "MeifengWubi";
    traits.distribution_version = g_version.c_str();
    traits.app_name = "rime.meifeng";
    traits.min_log_level = 2;
    traits.log_dir = g_log.c_str();
    traits.modules = nullptr;

    g_api->setup(&traits);
    g_api->initialize(&traits);

    if (g_api->start_maintenance) {
        Bool maintenance = g_api->start_maintenance(do_full ? True : False);
        if (maintenance && g_api->join_maintenance_thread) g_api->join_maintenance_thread();
    }
    if (!file_exists(table) && !do_full && g_api->start_maintenance) {
        Bool maintenance = g_api->start_maintenance(True);
        if (maintenance && g_api->join_maintenance_thread) g_api->join_maintenance_thread();
    }

    g_session = g_api->create_session();
    if (!g_session) {
        g_api->finalize();
        return env->NewStringUTF("create_session failed");
    }
    g_started = true;
    g_api->select_schema(g_session, "wubi86_jidian");
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_cn_kylebing_meifeng_wubi_engine_RimeNative_shutdown(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mu);
    if (!g_api || !g_started) return;
    destroy_lookup();
    if (g_session) {
        g_api->destroy_session(g_session);
        g_session = 0;
    }
    g_api->finalize();
    g_started = false;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_cn_kylebing_meifeng_wubi_engine_RimeNative_isStarted(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mu);
    return g_started ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_cn_kylebing_meifeng_wubi_engine_RimeNative_processKey(
        JNIEnv *, jobject, jint key_code, jint modifiers) {
    std::lock_guard<std::mutex> lock(g_mu);
    if (!g_started || !g_session || !g_api) return JNI_FALSE;
    return g_api->process_key(g_session, key_code, modifiers) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_cn_kylebing_meifeng_wubi_engine_RimeNative_selectCandidate(
        JNIEnv *, jobject, jint index) {
    std::lock_guard<std::mutex> lock(g_mu);
    if (!g_started || !g_session || !g_api) return JNI_FALSE;
    if (g_api->select_candidate_on_current_page) {
        return g_api->select_candidate_on_current_page(g_session, static_cast<size_t>(index))
                       ? JNI_TRUE
                       : JNI_FALSE;
    }
    return g_api->process_key(g_session, '1' + index, 0) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_cn_kylebing_meifeng_wubi_engine_RimeNative_selectCandidateAbsolute(
        JNIEnv *, jobject, jint index) {
    std::lock_guard<std::mutex> lock(g_mu);
    if (!g_started || !g_session || !g_api) return JNI_FALSE;
    if (g_api->select_candidate) {
        return g_api->select_candidate(g_session, static_cast<size_t>(index)) ? JNI_TRUE : JNI_FALSE;
    }
    if (g_api->select_candidate_on_current_page) {
        return g_api->select_candidate_on_current_page(g_session, static_cast<size_t>(index))
                       ? JNI_TRUE
                       : JNI_FALSE;
    }
    return JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_cn_kylebing_meifeng_wubi_engine_RimeNative_clearComposition(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mu);
    if (!g_started || !g_session || !g_api) return;
    g_api->clear_composition(g_session);
}

extern "C" JNIEXPORT jstring JNICALL
Java_cn_kylebing_meifeng_wubi_engine_RimeNative_rawInput(JNIEnv *env, jobject) {
    std::lock_guard<std::mutex> lock(g_mu);
    if (!g_started || !g_session || !g_api || !g_api->get_input) return env->NewStringUTF("");
    const char *input = g_api->get_input(g_session);
    return c_to_jstring(env, input);
}

extern "C" JNIEXPORT jobject JNICALL
Java_cn_kylebing_meifeng_wubi_engine_RimeNative_context(JNIEnv *env, jobject, jboolean take_commit) {
    std::lock_guard<std::mutex> lock(g_mu);
    if (!g_started) return nullptr;
    return snap_to_java(env, take_context(take_commit == JNI_TRUE));
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_cn_kylebing_meifeng_wubi_engine_RimeNative_allCandidates(JNIEnv *env, jobject) {
    std::lock_guard<std::mutex> lock(g_mu);
    cache_classes(env);
    auto list = all_candidates();
    if (!g_cand_class || !g_cand_ctor) return nullptr;
    jobjectArray array = env->NewObjectArray(static_cast<jsize>(list.size()), g_cand_class, nullptr);
    for (size_t i = 0; i < list.size(); ++i) {
        jstring text = std_to_jstring(env, list[i].first);
        jstring comment = std_to_jstring(env, list[i].second);
        jobject cand = env->NewObject(g_cand_class, g_cand_ctor, text, comment);
        env->SetObjectArrayElement(array, static_cast<jsize>(i), cand);
        env->DeleteLocalRef(text);
        env->DeleteLocalRef(comment);
        env->DeleteLocalRef(cand);
    }
    return array;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_cn_kylebing_meifeng_wubi_engine_RimeNative_lookupTexts(JNIEnv *env, jobject, jstring code) {
    std::lock_guard<std::mutex> lock(g_mu);
    std::vector<std::string> texts;
    std::string lower = jstring_to_std(env, code);
    for (char &ch : lower) {
        if (ch >= 'A' && ch <= 'Z') ch = static_cast<char>(ch - 'A' + 'a');
    }
    if (g_started && g_api && !lower.empty()) {
        RimeSessionId sid = ensure_lookup();
        if (sid) {
            g_api->clear_composition(sid);
            size_t limit = lower.size() < 4 ? lower.size() : 4;
            for (size_t i = 0; i < limit; ++i) {
                char ch = lower[i];
                if (ch < 'a' || ch > 'z') continue;
                g_api->process_key(sid, ch, 0);
                append_commit(sid, texts);
            }
            if (g_api->candidate_list_begin) {
                RimeCandidateListIterator it{};
                if (g_api->candidate_list_begin(sid, &it)) {
                    while (g_api->candidate_list_next(&it)) {
                        if (!it.candidate.text || !it.candidate.text[0]) continue;
                        std::string text = it.candidate.text;
                        bool seen = false;
                        for (const auto &item : texts) {
                            if (item == text) {
                                seen = true;
                                break;
                            }
                        }
                        if (seen) continue;
                        texts.push_back(text);
                        if (texts.size() >= 80) break;
                    }
                    g_api->candidate_list_end(&it);
                }
            }
            g_api->clear_composition(sid);
        }
    }
    jclass string_class = env->FindClass("java/lang/String");
    jobjectArray array = env->NewObjectArray(static_cast<jsize>(texts.size()), string_class, nullptr);
    for (size_t i = 0; i < texts.size(); ++i) {
        jstring text = std_to_jstring(env, texts[i]);
        env->SetObjectArrayElement(array, static_cast<jsize>(i), text);
        env->DeleteLocalRef(text);
    }
    env->DeleteLocalRef(string_class);
    return array;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_cn_kylebing_meifeng_wubi_engine_RimeNative_deploy(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mu);
    if (!g_api || !g_started) return JNI_FALSE;
    Bool ok = False;
    if (g_api->start_maintenance) {
        ok = g_api->start_maintenance(True);
        if (ok && g_api->join_maintenance_thread) g_api->join_maintenance_thread();
    }
    if (!ok && g_api->deploy) ok = g_api->deploy();
    if (!recreate_session()) return JNI_FALSE;
    return JNI_TRUE;
}
