package cn.kylebing.meifeng.wubi.data

import cn.kylebing.meifeng.wubi.engine.RimeNative
import cn.kylebing.meifeng.wubi.engine.RimeQueue
import java.io.File

data class EngineCandidate(val text: String, val comment: String)

/**
 * 与 iOS RimeEngineLibrime 同一条 librime 路径：process_key、全量候选、独立查词会话、部署。
 * 四码唯一和空码清空也按 iOS 键盘在引擎外处理，方案里 auto_select 保持关闭。
 */
class WubiEngine private constructor() {
    data class CandidatePaint(
        val preedit: String,
        val texts: List<String>,
        val comments: List<String>,
        val specialCount: Int,
    )

    @Volatile var isReady = false
        private set

    private var lastCommit: String = ""

    fun post(block: () -> Unit) = RimeQueue.post(block)

    fun <T> call(block: () -> T): T = RimeQueue.call(block)

    fun start(forceFullCheck: Boolean = false) {
        if (isReady) return
        val user = AppPaths.userDir
        RimeResourceDeployer.syncBundledIfNeeded(AppPaths.context)
        RimeResourceDeployer.disableSchemaAutoSelectIfNeeded(user)
        val logDir = File(AppPaths.context.cacheDir, "rime-log").apply { mkdirs() }
        val version = runCatching {
            AppPaths.context.packageManager.getPackageInfo(AppPaths.context.packageName, 0).versionName
        }.getOrNull().orEmpty().ifEmpty { "1.0.0" }
        val error = RimeNative.start(
            sharedDir = user.absolutePath,
            userDir = user.absolutePath,
            logDir = logDir.absolutePath,
            version = version,
            forceFull = forceFullCheck,
        )
        isReady = error == null && RimeNative.isStarted()
    }

    fun ensureStarted(forceFullCheck: Boolean) {
        if (isReady) {
            if (forceFullCheck) redeploy()
            return
        }
        start(forceFullCheck)
    }

    fun stop() {
        if (RimeNative.isStarted()) RimeNative.shutdown()
        isReady = false
        lastCommit = ""
    }

    fun redeploy(): Boolean {
        if (!isReady) {
            start(forceFullCheck = true)
            return isReady
        }
        val ok = RimeNative.deploy()
        isReady = ok && RimeNative.isStarted()
        return isReady
    }

    fun processKey(keyCode: Int, modifiers: Int = 0): Boolean {
        if (!isReady) return false
        return RimeNative.processKey(keyCode, modifiers)
    }

    fun processLetter(ch: Char): Boolean {
        if (!isReady) return false
        val c = ch.lowercaseChar()
        if (c !in 'a'..'z') return false
        processKey(c.code)
        applyEmptyCodeClear()
        applyUniqueFourCodeSelect()
        return true
    }

    fun processSpace(): Boolean {
        if (!isReady || !isComposing) return false
        return processKey(0x20)
    }

    fun processDelete(): Boolean {
        if (!isReady || preedit().isEmpty()) return false
        return processKey(0xff08)
    }

    fun select(index: Int): Boolean {
        if (!isReady || index < 0) return false
        return RimeNative.selectCandidateAbsolute(index)
    }

    fun commitText(): String? {
        if (!isReady) return null
        val text = RimeNative.context(takeCommit = true)?.commitText.orEmpty()
        if (text.isEmpty()) return null
        lastCommit = text
        return text
    }

    fun commitRaw(): String? {
        if (!isReady) return null
        val snap = RimeNative.context(takeCommit = false) ?: return null
        if (!snap.composing && snap.preedit.isEmpty()) return null
        val preedit = snap.preedit
        processKey(0xff0d)
        val committed = commitText()
        if (!committed.isNullOrEmpty()) return committed
        if (preedit.isNotEmpty()) {
            clearComposition()
            lastCommit = preedit
            return preedit
        }
        return null
    }

    fun clearComposition() {
        if (!isReady) return
        RimeNative.clearComposition()
    }

    fun noteLastCommit(text: String) {
        if (text.isNotEmpty()) lastCommit = text
    }

    fun preedit(): String {
        if (!isReady) return ""
        return RimeNative.context(takeCommit = false)?.preedit.orEmpty()
    }

    fun rawInput(): String = if (isReady) RimeNative.rawInput() else ""

    val isComposing: Boolean
        get() {
            if (!isReady) return false
            val snap = RimeNative.context(takeCommit = false) ?: return false
            return snap.composing || snap.preedit.isNotEmpty()
        }

    fun rimeCandidates(): List<EngineCandidate> {
        if (!isReady) return emptyList()
        return (RimeNative.allCandidates() ?: emptyArray()).map {
            EngineCandidate(it.text, it.comment)
        }
    }

    fun allCandidates(): List<EngineCandidate> = paint().let { paint ->
        paint.texts.zip(paint.comments) { text, comment -> EngineCandidate(text, comment) }
    }

    fun paint(): CandidatePaint {
        if (!isReady) return CandidatePaint("", emptyList(), emptyList(), 0)
        val snap = RimeNative.context(takeCommit = false)
        val preedit = snap?.preedit.orEmpty()
        val rime = rimeCandidates()
        val special = DateTimeCandidates.candidates(preedit)
        if (special != null) {
            val texts = special + rime.map { it.text }
            return CandidatePaint(preedit, texts, List(texts.size) { "" }, special.size)
        }
        val code = typedCode(preedit)
        if (isSoloZ(code) && lastCommit.isNotEmpty()) {
            val restTexts = mutableListOf<String>()
            val restComments = mutableListOf<String>()
            val notes = commentsFor(preedit, rime)
            rime.forEachIndexed { index, cand ->
                if (cand.text == lastCommit) return@forEachIndexed
                restTexts.add(cand.text)
                restComments.add(notes.getOrElse(index) { "" })
            }
            return CandidatePaint(
                preedit,
                listOf(lastCommit) + restTexts,
                listOf("") + restComments,
                1,
            )
        }
        val notes = commentsFor(preedit, rime)
        return CandidatePaint(preedit, rime.map { it.text }, notes, 0)
    }

    fun lookupTexts(code: String): List<String> {
        if (!isReady || code.isEmpty()) return emptyList()
        return (RimeNative.lookupTexts(code) ?: emptyArray()).toList()
    }

    fun restore(code: String) {
        if (!isReady) return
        clearComposition()
        code.forEach { ch ->
            val value = ch.code
            if (value in 0x20..0x7E) processKey(value)
        }
    }

    fun shouldClearEmptyCode(): Boolean = false

    private fun applyEmptyCodeClear() {
        if (!KeyboardPrefs.clearEmptyCode || !isReady) return
        val snap = RimeNative.context(takeCommit = false) ?: return
        if (!snap.composing) return
        val preedit = snap.preedit
        if (!isFullFourCode(typedCode(preedit))) return
        if (DateTimeCandidates.candidates(preedit) != null) return
        if (snap.texts.isNotEmpty()) return
        if (rimeCandidates().isNotEmpty()) return
        clearComposition()
    }

    private fun applyUniqueFourCodeSelect() {
        if (!KeyboardPrefs.autoSelectUniqueFourCode || !isReady) return
        val snap = RimeNative.context(takeCommit = false) ?: return
        if (!snap.composing) return
        val preedit = snap.preedit
        if (!isFullFourCode(typedCode(preedit))) return
        if (DateTimeCandidates.candidates(preedit) != null) return
        if (rimeCandidates().size != 1) return
        RimeNative.selectCandidate(0)
    }

    private fun typedCode(preedit: String): String {
        val typed = rawInput()
        return if (typed.isEmpty()) preedit else typed
    }

    private fun commentsFor(preedit: String, rime: List<EngineCandidate>): List<String> {
        if (!KeyboardPrefs.showCommentInPanel || DateTimeCandidates.candidates(preedit) != null) {
            return List(rime.size) { "" }
        }
        val input = typedCode(preedit)
        val notes = runCatching { DictIndex.shared.displayNotes(rime.map { it.text }, input) }
            .getOrDefault(emptyList())
        return rime.indices.map { index ->
            val value = rime[index].comment.trim()
            if (value.isNotEmpty()) value else notes.getOrElse(index) { "" }
        }
    }

    companion object {
        val shared = WubiEngine()

        fun isSoloZ(code: String): Boolean = code.length == 1 && code.equals("z", ignoreCase = true)

        fun isFullFourCode(code: String): Boolean {
            if (code.length != 4 || code.first().equals('z', ignoreCase = true)) return false
            return code.all { it in 'a'..'z' || it in 'A'..'Z' }
        }
    }
}
