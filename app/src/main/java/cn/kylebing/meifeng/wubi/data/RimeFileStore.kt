package cn.kylebing.meifeng.wubi.data

import java.io.File

/** Rime 用户目录文件操作，供局域网 Web 同步服务调用 */
object RimeFileStore {
    data class Entry(
        val path: String,
        val size: Long,
        val isDirectory: Boolean,
        val mtime: Double
    )

    val root: File get() = AppPaths.userDir

    fun resolve(relative: String): File {
        val trimmed = relative.trim('/')
        val rootFile = root.canonicalFile
        if (trimmed.isEmpty()) return rootFile
        val target = File(rootFile, trimmed).canonicalFile
        val rootPath = rootFile.path
        val targetPath = target.path
        if (targetPath != rootPath && !targetPath.startsWith(rootPath + File.separator)) {
            error("path escape")
        }
        return target
    }

    fun listAll(): List<Entry> {
        val rootFile = root
        rootFile.mkdirs()
        val result = mutableListOf<Entry>()
        rootFile.walkTopDown()
            .onEnter { it == rootFile || !it.name.startsWith('.') }
            .forEach { file ->
                if (file == rootFile) return@forEach
                if (file.name.startsWith('.')) return@forEach
                val rel = file.relativeTo(rootFile).path.replace('\\', '/')
                if (rel.isEmpty()) return@forEach
                result.add(
                    Entry(
                        path = rel,
                        size = if (file.isDirectory) 0 else file.length(),
                        isDirectory = file.isDirectory,
                        mtime = file.lastModified() / 1000.0
                    )
                )
            }
        return result.sortedWith { a, b -> a.path.compareTo(b.path, ignoreCase = true) }
    }

    fun delete(paths: List<String>): Int {
        var count = 0
        for (path in paths) {
            val file = resolve(path)
            if (!file.exists()) continue
            if (file.deleteRecursively()) count++
        }
        if (count > 0) UserDictWriter.markNeedsDeploy()
        return count
    }

    fun clearAll(): Int {
        val items = root.listFiles() ?: emptyArray()
        var count = 0
        for (item in items) {
            if (item.deleteRecursively()) count++
        }
        AppPaths.prefs.edit().remove("rime.bundled.stamp").apply()
        if (count > 0) UserDictWriter.markNeedsDeploy()
        return count
    }

    fun sanitizeRelativePath(raw: String): String {
        var s = raw.replace('\\', '/')
        while (s.startsWith("./")) s = s.drop(2)
        s = s.trim('/')
        if (s.isEmpty()) error("invalid name")
        val parts = s.split('/')
        if (parts.any { it.isEmpty() || it == ".." }) error("invalid name")
        return parts.joinToString("/")
    }

    fun writeUpload(relativePath: String, data: ByteArray) {
        val name = sanitizeRelativePath(relativePath)
        val dest = resolve(name)
        dest.parentFile?.mkdirs()
        dest.writeBytes(data)
        UserDictWriter.markNeedsDeploy()
    }

    fun makeDirectory(relativePath: String) {
        val name = sanitizeRelativePath(relativePath)
        resolve(name).mkdirs()
        UserDictWriter.markNeedsDeploy()
    }

    fun readFile(relativePath: String): Pair<ByteArray, String> {
        val file = resolve(relativePath)
        if (file.isDirectory) error("is directory")
        return file.readBytes() to file.name
    }
}

object RimeFileLayout {
    const val SCHEMA = "wubi86_jidian.schema.yaml"

    fun flattenNestedSchemeIfNeeded(root: File = AppPaths.userDir): Boolean {
        if (File(root, SCHEMA).exists()) return false
        val candidates = root.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith('.') && File(it, SCHEMA).exists() }
            ?: return false
        if (candidates.size != 1) return false
        val src = candidates[0]
        src.listFiles()?.forEach { child ->
            val dest = File(root, child.name)
            if (dest.exists()) dest.deleteRecursively()
            child.renameTo(dest)
        }
        src.deleteRecursively()
        return true
    }

    fun validateSchemeRoot(root: File = AppPaths.userDir): String? {
        if (File(root, SCHEMA).exists()) return null
        root.listFiles()?.filter { it.isDirectory }?.forEach { item ->
            if (File(item, SCHEMA).exists()) {
                return "方案在子目录「${item.name}」内，根目录缺少 $SCHEMA。请重新上传目录内文件，或点「复位原始配置」。"
            }
        }
        return "缺少 $SCHEMA，无法出候选。请点「复位原始配置」恢复内置方案后再部署。"
    }
}

object RimeConfigDeploy {
    fun deployNow(): Result<Unit> = WubiEngine.shared.call {
        RimeFileLayout.flattenNestedSchemeIfNeeded()
        val invalid = RimeFileLayout.validateSchemeRoot()
        if (invalid != null) {
            Result.failure(IllegalStateException(invalid))
        } else {
            runCatching {
                val ok = WubiEngine.shared.redeploy()
                if (!ok) error("librime 部署失败")
                UserDictWriter.consumeNeedsDeploy()
                WubiDictEncoder.clearCache()
                runCatching { WubiDictEncoder.rebuildCharMap() }
                Unit
            }
        }
    }

    fun resetToFactory(): Result<Unit> = WubiEngine.shared.call {
        runCatching {
            WubiEngine.shared.stop()
            RimeFileStore.clearAll()
            AppPaths.prefs.edit().remove("rime.bundled.stamp").apply()
            RimeResourceDeployer.copyBundled(AppPaths.context, overwriteUserDict = true)
            AppPaths.prefs.edit().putInt("rime.bundled.stamp", 3).apply()
            RimeResourceDeployer.disableSchemaAutoSelectIfNeeded(AppPaths.userDir)
            WubiEngine.shared.start(forceFullCheck = true)
            if (!WubiEngine.shared.isReady) error("librime 部署失败")
            UserDictWriter.consumeNeedsDeploy()
            WubiDictEncoder.clearCache()
            runCatching { WubiDictEncoder.rebuildCharMap() }
            Unit
        }
    }
}
