package cn.kylebing.meifeng.wubi.data

import java.io.File
import java.util.ArrayDeque
import java.util.PriorityQueue

/**
 * 码表备注和调权用的 YAML 索引。打字和部署走 librime，不从这里出候选。
 */
class DictIndex private constructor() {
    data class Entry(
        val text: String,
        val code: String,
        val weight: Int,
        val note: String,
        val source: Int, // 3 用户 / 2 扩展 / 1 主 / 0 拼音
        val fileName: String,
        val lineIndex: Int,
        // 收集顺序：import_tables 在前，主词库正文在后；同权重时靠它稳住词序
        val ordinal: Long
    )

    private val lock = Any()
    @Volatile private var loaded = false
    private val byPrefix = HashMap<String, MutableList<Entry>>(65536)
    private val pinyinByPrefix = HashMap<String, MutableList<Entry>>(65536)
    private var userEntries = emptyList<Entry>()
    // 主码表 sort:；导入表各自的 sort 不单独生效，和 librime 编进同一张表一样
    private var tableSortByWeight = true
    private var tableImports = emptyList<File>()

    fun ensureLoaded() {
        // 排序算法升级后强制重建索引，避免常驻 IME 进程继续用旧词序
        if (loaded && AppPaths.prefs.getInt(SORT_VERSION_KEY, 0) == SORT_VERSION) return
        synchronized(lock) {
            if (loaded && AppPaths.prefs.getInt(SORT_VERSION_KEY, 0) == SORT_VERSION) return
            loadAllLocked()
            loaded = true
            AppPaths.prefs.edit().putInt(SORT_VERSION_KEY, SORT_VERSION).commit()
        }
    }

    fun reload() {
        synchronized(lock) {
            loaded = false
            byPrefix.clear()
            pinyinByPrefix.clear()
            loadAllLocked()
            loaded = true
            AppPaths.prefs.edit().putInt(SORT_VERSION_KEY, SORT_VERSION).commit()
        }
    }

    fun reloadUser() {
        synchronized(lock) {
            ensureLoadedUnlocked()
            // 去掉旧用户词，按 import_tables 里的原位置再灌入
            byPrefix.values.forEach { list -> list.removeAll { it.source == 3 } }
            userEntries = emptyList()
            tableImports.forEachIndexed { band, file ->
                if (dictSource(file) != 3 || !file.exists()) return@forEachIndexed
                val entries = loadFile(file, 3, band)
                userEntries = userEntries + entries
                entries.forEach { put(byPrefix, it) }
            }
            sortAll(byPrefix, tableSortByWeight)
        }
    }

    private fun ensureLoadedUnlocked() {
        if (!loaded) {
            loadAllLocked()
            loaded = true
        }
    }

    fun lookup(code: String, limit: Int = 200): List<Entry> {
        ensureLoaded()
        val key = code.lowercase()
        if (key.isEmpty()) return emptyList()
        if (key.startsWith("z") && key.length > 1) {
            val py = key.drop(1)
            return synchronized(lock) { pinyinByPrefix[py]?.take(limit) ?: emptyList() }
        }
        return synchronized(lock) { byPrefix[key]?.take(limit) ?: emptyList() }
    }

    fun exact(code: String): List<Entry> {
        ensureLoaded()
        val key = code.lowercase()
        return synchronized(lock) {
            (byPrefix[key] ?: emptyList()).filter { it.code == key }
        }
    }

    fun preferredHit(text: String, preedit: String): DictHit? {
        ensureLoaded()
        val code = preedit.lowercase().filter { it.isLetter() }
        val list = synchronized(lock) { byPrefix[code] ?: byPrefix[code.take(4)] ?: emptyList() }
        val hit = list.firstOrNull { it.text == text }
            ?: list.firstOrNull { it.text == text && it.code.startsWith(code) }
        return hit?.let {
            DictHit(it.text, it.code, it.weight, it.note, it.fileName, it.lineIndex)
        }
    }

    fun updateWeight(hit: DictHit, newWeight: Int) {
        val file = File(AppPaths.userDir, hit.fileName)
        if (!file.exists()) return
        val lines = file.readLines().toMutableList()
        if (hit.lineIndex !in lines.indices) return
        val old = UserDictWriter.parseEntryLine(lines[hit.lineIndex]) ?: return
        val updated = old.copy(weight = newWeight)
        val note = updated.note
        lines[hit.lineIndex] = if (note.isEmpty()) {
            "${updated.text}\t${updated.code}\t$newWeight"
        } else {
            "${updated.text}\t${updated.code}\t$newWeight\t$note"
        }
        file.writeText(lines.joinToString("\n", postfix = "\n"))
        UserDictWriter.markNeedsDeploy()
    }

    fun displayNotes(texts: List<String>, preedit: String): List<String> {
        return texts.map { text -> preferredHit(text, preedit)?.note ?: "" }
    }

    private fun loadAllLocked() {
        byPrefix.clear()
        pinyinByPrefix.clear()
        val main = AppPaths.mainDictFile
        // 跟 librime DictCompiler 一样：整张表只看主词库的 sort
        tableSortByWeight = readSortByWeight(main)
        tableImports = importTableFiles(main)
        userEntries = emptyList()
        tableImports.forEachIndexed { band, file ->
            if (!file.exists()) return@forEachIndexed
            val source = dictSource(file)
            val entries = loadFile(file, source, band)
            if (source == 3) userEntries = userEntries + entries
            entries.forEach { put(byPrefix, it) }
        }
        if (main.exists()) {
            loadFile(main, 1, tableImports.size).forEach { put(byPrefix, it) }
        }
        sortAll(byPrefix, tableSortByWeight)
        val py = File(AppPaths.userDir, "pinyin_simp.dict.yaml")
        if (py.exists()) {
            loadFile(py, 0, 0).forEach { put(pinyinByPrefix, it) }
            sortAll(pinyinByPrefix, readSortByWeight(py))
        }
        if (!AppPaths.charMapFile.exists()) {
            runCatching { WubiDictEncoder.rebuildCharMap() }
        }
    }

    private fun loadFile(file: File, source: Int, band: Int): List<Entry> {
        if (!file.exists()) return emptyList()
        val out = ArrayList<Entry>(if (source == 1) 90000 else 256)
        var inBody = false
        file.bufferedReader().useLines { seq ->
            seq.forEachIndexed { index, raw ->
                val trimmed = raw.trim()
                if (trimmed == "...") { inBody = true; return@forEachIndexed }
                if (!inBody && trimmed.startsWith("---")) return@forEachIndexed
                if (!inBody && raw.contains('\t') && !trimmed.startsWith("#") && !trimmed.startsWith("name:")) {
                    inBody = true
                }
                if (!inBody) return@forEachIndexed
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("##")) return@forEachIndexed
                val entry = UserDictWriter.parseEntryLine(raw) ?: return@forEachIndexed
                out.add(
                    Entry(
                        text = entry.text,
                        code = entry.code.lowercase(),
                        weight = entry.weight ?: 0,
                        note = entry.note,
                        source = source,
                        fileName = file.name,
                        lineIndex = index,
                        ordinal = band.toLong() * ORDINAL_BAND + out.size
                    )
                )
            }
        }
        return out
    }

    // 主词库 import_tables，顺序就是 Rime 收集词条的顺序
    private fun importTableFiles(main: File): List<File> {
        if (!main.exists()) return emptyList()
        val dir = main.parentFile ?: return emptyList()
        val names = ArrayList<String>()
        var inList = false
        main.bufferedReader().useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (line == "..." || line.startsWith("columns:")) break
                if (line.startsWith("import_tables:")) {
                    inList = true
                    continue
                }
                if (!inList || line.isEmpty() || line.startsWith("#")) continue
                if (line.startsWith("- ")) {
                    val name = line.removePrefix("- ").substringBefore("#").trim()
                    if (name.isNotEmpty()) names.add(name)
                    continue
                }
                break
            }
        }
        return names.map { name ->
            val fileName = if (name.endsWith(".dict.yaml")) name else "$name.dict.yaml"
            File(dir, fileName)
        }
    }

    // 没写 sort，或不是 original，Rime 都按权重排
    private fun readSortByWeight(file: File): Boolean {
        if (!file.exists()) return true
        file.bufferedReader().useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (line == "...") return true
                if (!line.startsWith("sort:")) continue
                val value = line.removePrefix("sort:").substringBefore("#").trim()
                return value != "original"
            }
        }
        return true
    }

    // 3 用户 / 2 扩展 / 1 主。只用来标记来源，不参与词序
    private fun dictSource(file: File): Int {
        val name = file.name
        return when {
            name.contains("user") -> 3
            name.contains("extra") -> 2
            else -> 1
        }
    }

    private fun put(map: HashMap<String, MutableList<Entry>>, entry: Entry) {
        val code = entry.code
        if (code.isEmpty() || code.length > 16) return
        var i = 1
        while (i <= code.length) {
            val prefix = code.substring(0, i)
            map.getOrPut(prefix) { ArrayList() }.add(entry)
            i++
        }
    }

    // 对齐 librime：短码整段在前，同长度里才比权重；sort: original 时同码保持收集顺序
    private fun sortAll(map: HashMap<String, MutableList<Entry>>, byWeight: Boolean) {
        map.values.forEach { list ->
            if (list.size < 2) return@forEach
            // librime 对同一字、同一编码只保留先收集到的那条
            dedupeFirst(list)
            if (list.size < 2) return@forEach
            if (byWeight) {
                list.sortWith(
                    compareBy<Entry> { it.code.length }
                        .thenByDescending { rankWeight(it.weight) }
                        .thenBy { it.ordinal }
                )
            } else {
                sortOriginal(list)
            }
        }
    }

    // 同一词条+编码只留收集顺序更早的一条，后面的定义丢掉
    private fun dedupeFirst(list: MutableList<Entry>) {
        val best = HashMap<String, Long>(list.size)
        for (entry in list) {
            val key = entry.text + "\t" + entry.code
            val old = best[key]
            if (old == null || entry.ordinal < old) best[key] = entry.ordinal
        }
        if (best.size == list.size) return
        list.removeAll { entry -> best[entry.text + "\t" + entry.code] != entry.ordinal }
    }

    // 非正权重在 Rime 里都变成同一个 log(epsilon)，彼此不分高低
    private fun rankWeight(weight: Int): Int = if (weight > 0) weight else 0

    // sort: original：每个编码内部按收集顺序走，不同编码按当前词条权重交错
    private fun sortOriginal(list: MutableList<Entry>) {
        val byCode = LinkedHashMap<String, ArrayDeque<Entry>>()
        for (entry in list.sortedBy { it.ordinal }) {
            byCode.getOrPut(entry.code) { ArrayDeque() }.add(entry)
        }
        val byLen = sortedMapOf<Int, MutableList<ArrayDeque<Entry>>>()
        for (queue in byCode.values) {
            val len = queue.first().code.length
            byLen.getOrPut(len) { ArrayList() }.add(queue)
        }
        val merged = ArrayList<Entry>(list.size)
        val newerFirst = compareByDescending<ArrayDeque<Entry>> { rankWeight(it.first().weight) }
            .thenBy { it.first().ordinal }
        for (queues in byLen.values) {
            val heap = PriorityQueue(newerFirst)
            queues.forEach { heap.add(it) }
            while (heap.isNotEmpty()) {
                val queue = heap.poll() ?: break
                merged.add(queue.removeFirst())
                if (queue.isNotEmpty()) heap.add(queue)
            }
        }
        list.clear()
        list.addAll(merged)
    }

    companion object {
        val shared = DictIndex()
        // 词序改成跟 librime 同一套；升版本会触发 ensureLoaded 重建
        private const val SORT_VERSION = 3
        private const val SORT_VERSION_KEY = "dict.index.sortVersion"
        private const val ORDINAL_BAND = 10_000_000L
    }
}
