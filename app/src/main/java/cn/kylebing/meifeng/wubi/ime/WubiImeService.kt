package cn.kylebing.meifeng.wubi.ime

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import cn.kylebing.meifeng.wubi.data.AppPaths
import cn.kylebing.meifeng.wubi.data.CandidateRankWeight
import cn.kylebing.meifeng.wubi.data.DateTimeCandidates
import cn.kylebing.meifeng.wubi.data.DictIndex
import cn.kylebing.meifeng.wubi.data.KeyboardPrefs
import cn.kylebing.meifeng.wubi.data.PunctuationPrefs
import cn.kylebing.meifeng.wubi.data.RecentHanziStore
import cn.kylebing.meifeng.wubi.data.UserDictEntry
import cn.kylebing.meifeng.wubi.data.UserDictWriter
import cn.kylebing.meifeng.wubi.data.WubiDictEncoder
import cn.kylebing.meifeng.wubi.data.WubiEngine

class WubiImeService : InputMethodService(), KeyboardListener {
    private lateinit var keyboard: KeyboardView
    private lateinit var inputRoot: FrameLayout
    private val engine = WubiEngine.shared
    private val handler = Handler(Looper.getMainLooper())
    private var deleteRepeat: Runnable? = null
    private val quotePair = PunctuationPrefs.PairState()
    private var punctSymbols = listOf<String>()
    private var displayed = listOf<String>()
    private var displayedComments = listOf<String>()
    private var specialCount = 0
    private var caretTail = ""
    private var rankRows = mutableListOf<RankEdit>()
    private var rankDirty = false
    private var rankCode = ""

    data class RankEdit(
        var text: String,
        var comment: String,
        var weight: Int,
        var hasWeight: Boolean,
        val fileName: String,
        val lineIndex: Int,
        var edited: Boolean = false
    )

    override fun onCreate() {
        super.onCreate()
        AppPaths.init(this)
        engine.post { engine.start() }
    }

    override fun onDestroy() {
        stopDeleteRepeat()
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        // 根容器必须占满输入法区域，否则触点会穿过键面落到宿主 App
        inputRoot = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            isClickable = true
            isFocusable = true
        }
        keyboard = KeyboardView(this).also { it.listener = this }
        keyboard.needsGlobe = shouldOfferSwitchingToNextInputMethod()
        keyboard.isClickable = true
        keyboard.isFocusable = true
        inputRoot.addView(
            keyboard,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                keyboard.contentHeight
            )
        )
        refreshCandidates()
        return inputRoot
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        quotePair.reset()
        keyboard.returnTitle = returnTitle(info)
        keyboard.applyNight()
        keyboard.needsGlobe = shouldOfferSwitchingToNextInputMethod()
        syncKeyboardHeight()
        cn.kylebing.meifeng.wubi.sync.UserDictAutoSync.activate()
        engine.post {
            val force = UserDictWriter.consumeNeedsDeploy()
            engine.ensureStarted(force)
            if (force && !engine.isReady) UserDictWriter.markNeedsDeploy()
            publish()
        }
    }

    // 沿用系统默认：窗口上半空白穿透，只有底部输入区接收触点。
    // 以前把 contentTopInsets 设成键盘高度，等于把键面也标成穿透区。
    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        if (!::inputRoot.isInitialized || !inputRoot.isShown) return
        val loc = IntArray(2)
        inputRoot.getLocationInWindow(loc)
        val top = loc[1]
        outInsets.contentTopInsets = top
        outInsets.visibleTopInsets = top
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_CONTENT
    }

    private fun syncKeyboardHeight() {
        if (!::keyboard.isInitialized) return
        val h = keyboard.contentHeight
        val lp = keyboard.layoutParams
        if (lp != null && lp.height != h) {
            lp.height = h
            keyboard.layoutParams = lp
        }
        keyboard.requestLayout()
    }

    private fun returnTitle(info: EditorInfo?): String {
        val ime = info?.imeOptions ?: return "换行"
        return when (ime and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_GO -> "前往"
            EditorInfo.IME_ACTION_SEARCH -> "搜索"
            EditorInfo.IME_ACTION_SEND -> "发送"
            EditorInfo.IME_ACTION_NEXT -> "下一项"
            EditorInfo.IME_ACTION_DONE -> "完成"
            else -> "换行"
        }
    }

    override fun onTouchKey() {
        if (KeyboardPrefs.hapticEnabled) {
            keyboard.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    override fun onKey(spec: KeySpec) {
        when (spec.action) {
            KeyAction.Character -> handleCharacter(spec.payload.ifEmpty { spec.title })
            KeyAction.Space -> handleSpace()
            KeyAction.Return -> handleReturn()
            KeyAction.Delete -> handleDelete()
            KeyAction.ToggleLanguage -> engine.post {
                if (engine.isComposing) engine.clearComposition()
                publish()
            }
            KeyAction.NextInput -> switchToNextInput()
            KeyAction.Dismiss -> requestHideSelf(0)
            KeyAction.Tab -> commitToHost("\t")
            else -> {}
        }
    }

    private fun switchToNextInput() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            switchToNextInputMethod(false)
        } else {
            @Suppress("DEPRECATION")
            imm.switchToNextInputMethod(window.window?.attributes?.token, false)
        }
    }

    override fun onDeleteRepeatStart() {
        stopDeleteRepeat()
        val interval = KeyboardPrefs.deleteRepeatIntervalMs()
        deleteRepeat = object : Runnable {
            override fun run() {
                handleDelete()
                onTouchKey()
                handler.postDelayed(this, interval)
            }
        }
        handler.postDelayed(deleteRepeat!!, interval)
    }

    override fun onDeleteRepeatStop() = stopDeleteRepeat()

    private fun stopDeleteRepeat() {
        deleteRepeat?.let { handler.removeCallbacks(it) }
        deleteRepeat = null
    }

    override fun onMoveCursor(offset: Int) {
        if (keyboard.pickingHanzi) return
        engine.post {
            if (engine.isComposing) {
                engine.clearComposition()
                publish()
            }
        }
        currentInputConnection?.commitText("", 0)
        val code = if (offset > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
        currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        caretTail = ""
    }

    override fun onSelectCandidate(index: Int) = selectDisplayed(index)
    override fun onSelectExpanded(index: Int) {
        keyboard.expandVisible = false
        selectDisplayed(index)
    }

    override fun onExpand() {
        if (displayed.isEmpty()) return
        if (!keyboard.canExpand()) return
        keyboard.expandVisible = !keyboard.expandVisible
        // 展开后首行固定，禁止横滑
        if (keyboard.expandVisible) keyboard.resetCandidateScroll()
        keyboard.invalidate()
    }

    override fun onTapCode() {
        if (displayed.isNotEmpty()) selectDisplayed(0) else commitRaw()
    }

    override fun onAddWord() {
        if (keyboard.rankVisible) {
            finishRank()
            return
        }
        val initial = displayed.firstOrNull().orEmpty()
        val shown = keyboard.preedit
        engine.post {
            val pre = shown.ifEmpty { engine.preedit() }
            if (pre.isNotEmpty()) {
                engine.clearComposition()
                val paint = engine.paint()
                handler.post {
                    applyPaint(paint)
                    keyboard.beginAddWord(pre.lowercase(), initial)
                }
            } else {
                handler.post {
                    val chars = RecentHanziStore.characters.map { it.toString() }
                    if (chars.isNotEmpty()) keyboard.beginPick(chars)
                }
            }
        }
    }

    override fun onFinishPickHanzi(text: String) {
        val word = text.trim()
        keyboard.endPick()
        if (word.isEmpty()) {
            refreshCandidates()
            return
        }
        keyboard.beginAddWord(WubiDictEncoder.encode(word), word)
    }

    override fun onCancelAddWord() {
        keyboard.endAddWord()
        engine.post {
            engine.clearComposition()
            publish()
        }
    }

    override fun onSaveAddWord() {
        val code = keyboard.addWordCode
        val text = keyboard.addWordText
        if (code.isEmpty() || text.isEmpty()) return
        keyboard.endAddWord()
        engine.post {
            val saved = runCatching { UserDictWriter.addEntry(UserDictEntry(code, text)) }.isSuccess
            if (saved) {
                val deployed = engine.redeploy()
                UserDictWriter.consumeNeedsDeploy()
                if (!deployed) UserDictWriter.markNeedsDeploy()
            }
            engine.clearComposition()
            publish()
        }
    }

    override fun onAddWordChanged() {
        if (!keyboard.addWord) {
            keyboard.sameCode = emptyList()
            return
        }
        val code = keyboard.addWordCode
        val highlight = keyboard.addWordText
        engine.post {
            val same = engine.lookupTexts(code)
            handler.post {
                if (!keyboard.addWord) return@post
                keyboard.sameCode = same
                keyboard.sameHighlight = highlight
                onHeightChanged(keyboard.contentHeight)
                keyboard.invalidate()
            }
        }
    }

    override fun onRank() {
        if (keyboard.rankVisible) {
            finishRank()
            return
        }
        beginRank()
    }

    override fun onReorder(from: Int, to: Int) {
        if (from !in rankRows.indices || from == to) return
        val step = KeyboardPrefs.candidateRankStep
        val weights = rankRows.map { it.weight }
        val newWeight = CandidateRankWeight.placed(weights, from, to, step) ?: return
        val moving = rankRows.removeAt(from)
        moving.weight = newWeight
        moving.hasWeight = true
        moving.edited = true
        val others = rankRows.map { it.weight }
        val index = CandidateRankWeight.index(newWeight, others)
        rankRows.add(index, moving)
        DictIndex.shared.updateWeight(
            cn.kylebing.meifeng.wubi.data.DictHit(
                moving.text, rankCode, newWeight, moving.comment, moving.fileName, moving.lineIndex
            ),
            newWeight
        )
        rankDirty = true
        publishRank("「${moving.text}」权重 → $newWeight。点完成后重新加载。")
    }

    override fun onFinishRank() = finishRank()

    override fun onHeightChanged(height: Int) {
        syncKeyboardHeight()
        // 高度变化后立刻重算 insets，避免短暂穿透
        updateFullscreenMode()
    }

    private fun handleCharacter(text: String) {
        if (keyboard.pickingHanzi) return
        if (punctSymbols.isNotEmpty()) {
            if (text.length == 1 && text[0].isDigit()) {
                val n = text.toInt()
                if (n in 1..9 && n - 1 in punctSymbols.indices) {
                    commitPunct(punctSymbols[n - 1])
                    return
                }
            }
            if (shouldPunct(text)) {
                applyPunct(text)
                return
            }
            punctSymbols = emptyList()
        }
        val english = keyboard.english
        val addWord = keyboard.addWord
        val editingCode = keyboard.editingCode
        val onSym = keyboard.page != KeyboardPage.Letters
        val upper = (keyboard.shift || keyboard.caps) && text.any { it.isLetter() }
        if (addWord && editingCode) {
            engine.post {
                engine.clearComposition()
                publish()
            }
            keyboard.appendAddWordCode(text)
            return
        }
        if (english) {
            insertCommitted(text)
            maybeCloseSymbol(text)
            return
        }
        engine.post {
            val composing = engine.isComposing
            val digit = text.length == 1 && text[0].isDigit()
            if (digit && (onSym || !composing)) {
                if (composing) engine.clearComposition()
                val paint = engine.paint()
                handler.post {
                    applyPaint(paint)
                    insertCommitted(text)
                    maybeCloseSymbol(text)
                }
                return@post
            }
            if (upper) {
                if (composing) engine.clearComposition()
                val paint = engine.paint()
                handler.post {
                    applyPaint(paint)
                    insertCommitted(text.uppercase())
                    maybeCloseSymbol(text)
                }
                return@post
            }
            if (!composing && PunctuationPrefs.item(text) != null) {
                handler.post { applyPunct(text) }
                return@post
            }
            val ch = text.firstOrNull()
            if (ch != null && ch.isLetter() && ch.code < 127) {
                engine.processLetter(ch)
                deliverCommit(onSym, text)
                return@post
            }
            if (digit && composing) {
                engine.processKey(text[0].code)
                deliverCommit(false, text)
                return@post
            }
            handler.post {
                insertCommitted(text)
                maybeCloseSymbol(text)
            }
        }
    }

    private fun shouldPunct(text: String, composing: Boolean = false): Boolean {
        if (keyboard.english) return false
        if (PunctuationPrefs.item(text) == null) return false
        if (keyboard.addWord && keyboard.editingCode) return false
        if (composing) return false
        return true
    }

    private fun applyPunct(key: String) {
        val symbols = PunctuationPrefs.symbols(key)
        val mode = PunctuationPrefs.mode(key)
        halfWidth(key, symbols.firstOrNull() ?: key)?.let { (t, replace) ->
            commitPunct(t, replace, stay = keyboard.page == KeyboardPage.Numbers && !replace && (key == "." || key == ":"))
            return
        }
        if (mode == PunctuationPrefs.Mode.Direct || keyboard.addWord) {
            commitPunct(quotePair.directSymbol(key, symbols))
            return
        }
        punctSymbols = symbols
        displayed = symbols
        displayedComments = emptyList()
        specialCount = symbols.size
        keyboard.preedit = ""
        keyboard.candidates = symbols
        keyboard.comments = emptyList()
        keyboard.rankAvailable = false
        keyboard.invalidate()
    }

    private fun halfWidth(key: String, primary: String): Pair<String, Boolean>? {
        val rules = listOf("。" to ".", "，" to ",", "：" to ":")
        val rule = rules.firstOrNull { it.first == primary } ?: return null
        val prefix = contextBefore(2) ?: return null
        val last = prefix.lastOrNull() ?: return null
        if (last.isDigit() || last in '０'..'９') return rule.second to false
        if (last.toString() == rule.second && prefix.length >= 2) {
            val prev = prefix[prefix.length - 2]
            if (prev.isDigit() || prev in '０'..'９') return rule.first to true
        }
        return null
    }

    private fun contextBefore(limit: Int): String? {
        if (keyboard.addWord && !keyboard.editingCode) return keyboard.addWordText.takeLast(limit)
        val before = currentInputConnection?.getTextBeforeCursor(8, 0)?.toString()
        if (!before.isNullOrEmpty()) {
            caretTail = before.takeLast(8)
            return before.takeLast(limit)
        }
        return if (caretTail.isNotEmpty()) caretTail.takeLast(limit) else null
    }

    private fun commitPunct(text: String, replace: Boolean = false, stay: Boolean = false) {
        punctSymbols = emptyList()
        val leave = keyboard.page != KeyboardPage.Letters
        if (replace) {
            if (keyboard.addWord && !keyboard.editingCode) keyboard.deleteAddWord()
            else {
                currentInputConnection?.deleteSurroundingText(1, 0)
                if (caretTail.isNotEmpty()) caretTail = caretTail.dropLast(1)
            }
        }
        insertCommitted(text)
        refreshCandidates()
        if (leave && !stay) maybeCloseSymbol(text)
    }

    private fun maybeCloseSymbol(text: String) {
        if (!KeyboardPrefs.closesSymbolPageAfterInput) return
        if (keyboard.page == KeyboardPage.Letters) return
        if (keyboard.page == KeyboardPage.Numbers && (text == "." || text == ":")) return
        handler.post { keyboard.setPage(KeyboardPage.Letters) }
    }

    private fun handleSpace() {
        if (punctSymbols.isNotEmpty()) {
            commitPunct(punctSymbols[0]); return
        }
        if (keyboard.addWord && keyboard.editingCode) return
        if (keyboard.english) {
            insertCommitted(" "); return
        }
        val addWord = keyboard.addWord
        engine.post {
            val paint = engine.paint()
            if (engine.isComposing && paint.specialCount > 0 && paint.texts.isNotEmpty()) {
                val text = paint.texts[0]
                engine.clearComposition()
                engine.noteLastCommit(text)
                val next = engine.paint()
                handler.post {
                    insertCommitted(text)
                    applyPaint(next)
                }
                return@post
            }
            if (engine.isComposing) {
                engine.processSpace()
                deliverCommit(false, "")
            } else if (addWord) {
                handler.post { keyboard.appendAddWordText(" ") }
            } else {
                handler.post { insertCommitted(" ") }
            }
        }
    }

    private fun handleReturn() {
        if (punctSymbols.isNotEmpty()) {
            punctSymbols = emptyList()
            refreshCandidates()
        }
        val addWord = keyboard.addWord
        val clears = KeyboardPrefs.enterClearsCode
        engine.post {
            if (clears && engine.isComposing) {
                engine.clearComposition()
                publish()
                return@post
            }
            if (engine.isComposing) {
                val text = engine.commitRaw()
                val paint = engine.paint()
                handler.post {
                    if (!text.isNullOrEmpty()) insertCommitted(text)
                    applyPaint(paint)
                }
            } else if (addWord) {
                handler.post { onSaveAddWord() }
            } else {
                handler.post { performReturn() }
            }
        }
    }

    private fun performReturn() {
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo
        val action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED &&
            (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0
        ) {
            ic.performEditorAction(action)
        } else insertCommitted("\n")
    }

    private fun handleDelete() {
        if (punctSymbols.isNotEmpty()) {
            punctSymbols = emptyList()
            refreshCandidates()
            return
        }
        if (keyboard.pickingHanzi) {
            keyboard.undoPick(); return
        }
        val editingCode = keyboard.addWord && keyboard.editingCode
        val addWord = keyboard.addWord
        if (editingCode) {
            engine.post {
                engine.clearComposition()
                publish()
            }
            keyboard.deleteAddWord()
            return
        }
        engine.post {
            if (engine.preedit().isNotEmpty()) {
                engine.processDelete()
                deliverCommit(false, "")
            } else if (addWord) {
                handler.post { keyboard.deleteAddWord() }
            } else {
                handler.post {
                    currentInputConnection?.deleteSurroundingText(1, 0)
                    if (caretTail.isNotEmpty()) caretTail = caretTail.dropLast(1)
                }
            }
        }
    }

    private fun insertCommitted(text: String) {
        if (keyboard.addWord) {
            if (keyboard.editingCode) keyboard.editingCode = false
            keyboard.appendAddWordText(text)
        } else commitToHost(text)
    }

    private fun commitToHost(text: String) {
        currentInputConnection?.commitText(text, 1)
        caretTail = (caretTail + text).takeLast(8)
        RecentHanziStore.append(text)
    }

    private fun deliverCommit(closeSymbol: Boolean, symbolText: String) {
        val commit = engine.commitText()
        val paint = engine.paint()
        handler.post {
            if (!commit.isNullOrEmpty()) insertCommitted(commit)
            applyPaint(paint)
            if (closeSymbol) maybeCloseSymbol(symbolText)
        }
    }

    private fun publish() {
        val paint = engine.paint()
        handler.post { applyPaint(paint) }
    }

    private fun commitRaw() {
        engine.post {
            val text = engine.commitRaw() ?: return@post
            val paint = engine.paint()
            handler.post {
                insertCommitted(text)
                applyPaint(paint)
            }
        }
    }

    private fun selectDisplayed(index: Int) {
        if (punctSymbols.isNotEmpty()) {
            if (index in punctSymbols.indices) commitPunct(punctSymbols[index])
            return
        }
        if (index < 0 || index >= displayed.size) return
        val special = specialCount
        val chosen = displayed[index]
        if (index < special) {
            engine.post {
                engine.clearComposition()
                engine.noteLastCommit(chosen)
                val paint = engine.paint()
                handler.post {
                    insertCommitted(chosen)
                    applyPaint(paint)
                }
            }
            return
        }
        val rimeIndex = index - special
        engine.post {
            engine.select(rimeIndex)
            deliverCommit(false, "")
        }
    }

    private fun refreshCandidates() {
        if (punctSymbols.isNotEmpty()) return
        engine.post { publish() }
    }

    private fun applyPaint(paint: WubiEngine.CandidatePaint) {
        if (punctSymbols.isNotEmpty()) return
        displayed = paint.texts
        displayedComments = paint.comments
        specialCount = paint.specialCount
        keyboard.preedit = paint.preedit
        keyboard.candidates = paint.texts
        keyboard.comments = paint.comments
        keyboard.rankAvailable = canRank()
        keyboard.recentAvailable = RecentHanziStore.characters.isNotEmpty()
        if (paint.texts.isEmpty()) keyboard.expandVisible = false
        keyboard.invalidate()
    }

    private fun canRank(): Boolean {
        if (punctSymbols.isNotEmpty() || specialCount > 0) return false
        if (keyboard.english || keyboard.addWord || keyboard.pickingHanzi) return false
        if (displayed.size < 2 || keyboard.preedit.isEmpty()) return false
        if (DateTimeCandidates.candidates(keyboard.preedit) != null) return false
        val compact = keyboard.preedit.lowercase().filter { it.isLetter() || it == '\'' }
        if (compact.matches(Regex("^z[a-z]*'?$"))) return false
        return true
    }

    private fun beginRank() {
        if (!canRank()) return
        rankDirty = false
        rankCode = keyboard.preedit
        rankRows.clear()
        displayed.forEachIndexed { i, raw ->
            val text = raw.trim()
            if (text.isEmpty()) return@forEachIndexed
            val hit = DictIndex.shared.preferredHit(text, rankCode)
            rankRows.add(
                RankEdit(
                    text = text,
                    comment = displayedComments.getOrNull(i).orEmpty().ifEmpty { hit?.note.orEmpty() },
                    weight = hit?.weight ?: 0,
                    hasWeight = hit?.weight != null,
                    fileName = hit?.fileName.orEmpty(),
                    lineIndex = hit?.lineIndex ?: -1
                )
            )
        }
        keyboard.rankVisible = true
        keyboard.expandVisible = false
        keyboard.rankCode = rankCode
        publishRank("拖动一行调整顺序。幅度是相对旁边那条候选的权重差。")
    }

    private fun publishRank(status: String) {
        keyboard.rankRows = rankRows.map {
            RankRow(it.text, if (KeyboardPrefs.showCommentInPanel) it.comment else "", if (it.hasWeight) it.weight.toString() else "—", it.fileName.isNotEmpty(), it.edited)
        }
        keyboard.rankStatus = status
        keyboard.rankCode = rankCode
        keyboard.invalidate()
    }

    private fun finishRank() {
        val dirty = rankDirty
        val code = rankCode
        rankDirty = false
        rankRows.clear()
        keyboard.rankVisible = false
        engine.post {
            if (dirty) {
                val ok = engine.redeploy()
                UserDictWriter.consumeNeedsDeploy()
                if (!ok) UserDictWriter.markNeedsDeploy()
                DictIndex.shared.reload()
                engine.clearComposition()
                engine.restore(code)
            }
            publish()
        }
    }
}
