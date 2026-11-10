package cn.kylebing.meifeng.wubi.data

import android.content.Context
import java.io.File

/** 词库与偏好的本地目录，对齐 iOS App Group 用户数据目录 */
object AppPaths {
    lateinit var filesDir: File
        private set
    lateinit var prefs: android.content.SharedPreferences
        private set
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
        filesDir = appContext.filesDir
        prefs = appContext.getSharedPreferences("meifeng_wubi", Context.MODE_PRIVATE)
        userDir.mkdirs()
        RimeResourceDeployer.syncBundledIfNeeded(appContext)
    }

    val context: Context get() = appContext

    val userDir: File get() = File(filesDir, "rime")

    val userDictFile: File get() = File(userDir, "wubi86_jidian_user.dict.yaml")
    val extraDictFile: File get() = File(userDir, "wubi86_jidian_extra.dict.yaml")
    val mainDictFile: File get() = File(userDir, WubiDictEncoder.selectedMainDictName)
    val charMapFile: File get() = File(userDir, WubiDictEncoder.CHAR_MAP_NAME)
}

/** 首次启动把内置方案拷到用户目录。用户词和扩展词库已存在时不覆盖。 */
object RimeResourceDeployer {
    private const val STAMP = "rime.bundled.stamp"
    private const val STAMP_VALUE = 3
    const val SCHEMA_ID = "wubi86_jidian"
    private val keepIfExists = setOf(
        "wubi86_jidian_user.dict.yaml",
        "wubi86_jidian_user_hamster.dict.yaml",
        "wubi86_jidian_extra.dict.yaml",
    )

    fun syncBundledIfNeeded(context: Context): Boolean {
        val dest = AppPaths.userDir
        dest.mkdirs()
        val ready = AppPaths.prefs.getInt(STAMP, 0) == STAMP_VALUE &&
            File(dest, "wubi86_jidian.schema.yaml").exists() &&
            File(dest, "wubi86_jidian.dict.yaml").exists() &&
            File(dest, "default.custom.yaml").exists()
        if (ready) {
            disableSchemaAutoSelectIfNeeded(dest)
            return false
        }
        copyBundled(context, overwriteUserDict = false)
        disableSchemaAutoSelectIfNeeded(dest)
        AppPaths.prefs.edit().putInt(STAMP, STAMP_VALUE).apply()
        return true
    }

    fun copyBundled(context: Context, overwriteUserDict: Boolean) {
        val dest = AppPaths.userDir
        dest.mkdirs()
        val names = context.assets.list("rime") ?: return
        for (name in names) {
            val target = File(dest, name)
            if (!overwriteUserDict && name in keepIfExists && target.exists()) continue
            context.assets.open("rime/$name").use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }

    fun disableSchemaAutoSelectIfNeeded(root: File): Boolean {
        val file = File(root, "wubi86_jidian.schema.yaml")
        if (!file.exists()) return false
        val text = file.readText()
        if (!text.contains("auto_select: true")) return false
        file.writeText(text.replace("auto_select: true", "auto_select: false"))
        UserDictWriter.markNeedsDeploy()
        return true
    }

    fun resetToFactory(context: Context) {
        RimeConfigDeploy.resetToFactory().getOrThrow()
    }
}
