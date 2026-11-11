package cn.kylebing.meifeng.wubi.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import cn.kylebing.meifeng.wubi.data.KeyboardPrefs
import kotlin.math.max
import kotlin.math.min

interface KeyboardListener {
    fun onTouchKey()
    fun onKey(spec: KeySpec)
    fun onDeleteRepeatStart()
    fun onDeleteRepeatStop()
    fun onMoveCursor(offset: Int)
    fun onSelectCandidate(index: Int)
    fun onExpand()
    fun onTapCode()
    fun onAddWord()
    fun onRank()
    fun onCancelAddWord()
    fun onSaveAddWord()
    fun onSelectExpanded(index: Int)
    fun onReorder(from: Int, to: Int)
    fun onFinishRank()
    fun onFinishPickHanzi(text: String)
    fun onAddWordChanged()
    fun onHeightChanged(height: Int)
}

data class RankRow(
    val text: String,
    val comment: String,
    val weightText: String,
    val adjustable: Boolean,
    val edited: Boolean
)

/** 仿 iOS 系统三页键盘：候选栏 + 四行键区 */
class KeyboardView(context: Context) : View(context) {
    var listener: KeyboardListener? = null
    var page = KeyboardPage.Letters
        private set
    var english = false
        private set
    var shift = false
        private set
    var caps = false
        private set
    var returnTitle = "换行"
        set(value) {
            if (field == value) return
            field = value
            rebuild()
        }
    var needsGlobe = true
        set(value) {
            if (field == value) return
            field = value
            rebuild()
        }

    var preedit = ""
        set(value) {
            if (field == value) return
            field = value
            resetCandidateScroll()
        }
    var candidates = listOf<String>()
        set(value) {
            if (field == value) return
            field = value
            resetCandidateScroll()
        }
    var comments = listOf<String>()
    var expandVisible = false
    var rankVisible = false
        set(value) {
            if (field == value) return
            field = value
            if (value) rankScrollY = 0f
        }
    var rankRows = listOf<RankRow>()
    var rankStatus = ""
    var rankCode = ""
    var addWord = false
    var editingCode = false
    var addWordCode = ""
    var addWordText = ""
    var pickingHanzi = false
    var pickChars = listOf<String>()
    var pickSelected = mutableSetOf<Int>()
    var sameCode = listOf<String>()
    var sameHighlight = ""
    var rankAvailable = false
    var recentAvailable = false
    /** 候选是否溢出栏宽（展开按钮可点） */
    var expandEnabled = false
        private set
    /** 栏内能放下的候选数（对齐 iOS visibleCount） */
    var visibleCount = 0
        private set

    private var theme = KeyboardTheme.current(context)
    private val keys = mutableListOf<PlacedKey>()
    private val candidateRects = mutableListOf<RectF>()
    private val expandRects = mutableListOf<Pair<RectF, Int>>()
    // 工具胶囊内按钮：0 展开 / 1 排序 / 2 造词
    private val toolHitRects = mutableListOf<Pair<RectF, Int>>()
    private var toolCapsuleRect = RectF()
    private var pressedIndex = -1
    private var lastShiftAt = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var deleteArmed = false
    private var spaceCursor = false
    private var lastSpaceX = 0f
    private var holdRunnable: Runnable? = null
    private val face = RectF()
    private val shadow = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    // 候选横向滚动（收起且溢出时）
    private var candidateScrollX = 0f
    private var maxCandidateScroll = 0f
    private var barTracking = false
    private var barMoved = false
    private var barDownX = 0f
    private var barDownY = 0f
    private var barDownScroll = 0f
    private var pendingBarTapX = Float.NaN
    private var pendingBarTapY = Float.NaN
    // 造词左侧「编码 → 词条」热区，和右侧按钮分开
    private var addWordCodeRect = RectF()
    private var addWordTextRect = RectF()
    private var loneCodeRect = RectF()
    private var firstCodeRect = RectF()
    // 展开面板：按词宽流式排布，可纵/横滑
    private var expandScrollX = 0f
    private var expandScrollY = 0f
    private var maxExpandScrollX = 0f
    private var maxExpandScrollY = 0f
    private var expandTracking = false
    private var expandMoved = false
    private var expandAxis = 0
    private var expandDownX = 0f
    private var expandDownY = 0f
    private var expandDownScrollX = 0f
    private var expandDownScrollY = 0f
    private var pendingExpandX = 0f
    private var pendingExpandY = 0f
    // 同码条纵向滚动
    private var sameScrollY = 0f
    private var maxSameScroll = 0f
    private var sameTracking = false
    private var sameMoved = false
    private var sameDownY = 0f
    private var sameDownScroll = 0f
    private val sameRects = mutableListOf<RectF>()
    // 滑选：按下后滑动连续选中
    private var pickAnchor = -1
    private var pickDragMoved = false
    private val pickIndexForRect = mutableListOf<Int>()
    // 排序列表滚动；长按才拖动，避免和滚动抢手势
    private var rankListTop = 0f
    private var rankViewportBottom = 0f
    private var rankScrollY = 0f
    private var maxRankScroll = 0f
    private var rankTracking = false
    private var rankScrolling = false
    private var rankDownY = 0f
    private var rankLastY = 0f
    private var rankDownScroll = 0f
    private var rankPotential = -1
    private var rankHold: Runnable? = null
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    // 排序拖动
    private var rankDragFrom = -1
    private var rankDragTarget = -1
    private var rankDragOffsetY = 0f
    private var rankDragFingerY = 0f
    private val rankStepRects = mutableListOf<Pair<RectF, Int>>()
    private var rankDoneRect = RectF()
    private val rankRowRects = mutableListOf<RectF>()

    // 与 iOS SystemLikeKeyboardView.phoneHeight / candidateBarHeight 一致：52 + 212
    private val barH get() = KeyboardTheme.dp(context, 52f)
    // 候选栏内容在纵向居中后再整体下移
    private val barContentDy = 6f
    private val phoneH get() = KeyboardTheme.dp(context, 264f)
    // 造词同码条加在键面上方：无词只留一行提示，有词固定高度并在内部滚动
    private val sameH get() = when {
        !addWord -> 0f
        sameCode.isEmpty() -> dp(34f)
        else -> dp(92f)
    }
    val contentHeight: Int get() = (phoneH + sameH).toInt()
    private val rankRowH get() = KeyboardTheme.dp(context, 40f)
    private val toolCapsuleH get() = KeyboardTheme.dp(context, 30f)
    private val toolCapsuleMargin get() = KeyboardTheme.dp(context, 8f)

    fun resetCandidateScroll() {
        candidateScrollX = 0f
        expandScrollX = 0f
        expandScrollY = 0f
    }

    private fun dp(v: Float) = KeyboardTheme.dp(context, v)

    fun canExpand(): Boolean = expandEnabled || expandVisible

    data class PlacedKey(val spec: KeySpec, val cell: RectF)

    fun applyNight() {
        theme = KeyboardTheme.current(context)
        invalidate()
    }

    fun setPage(p: KeyboardPage) {
        if (page == p) return
        page = p
        if (p != KeyboardPage.Letters) { shift = false; caps = false }
        rebuild()
    }

    fun toggleLanguage() {
        english = !english
        shift = false
        caps = false
        expandVisible = false
        rankVisible = false
        rebuild()
    }

    fun toggleShift() {
        val now = SystemClock.uptimeMillis()
        val dbl = now - lastShiftAt < 350
        lastShiftAt = now
        when {
            caps -> { caps = false; shift = false }
            dbl -> { caps = true; shift = true }
            else -> shift = !shift
        }
        rebuild()
    }

    fun consumeOneShotShift() {
        if (shift && !caps) { shift = false; rebuild() }
    }

    fun beginAddWord(code: String, text: String) {
        addWord = true
        addWordCode = code
        addWordText = text
        editingCode = false
        pickingHanzi = false
        expandVisible = false
        rankVisible = false
        sameScrollY = 0f
        listener?.onAddWordChanged()
        notifyHeight()
        invalidate()
    }

    fun endAddWord() {
        addWord = false
        addWordCode = ""
        addWordText = ""
        editingCode = false
        sameCode = emptyList()
        listener?.onAddWordChanged()
        notifyHeight()
        invalidate()
    }

    fun appendAddWordText(t: String) { addWordText += t; listener?.onAddWordChanged(); invalidate() }
    fun appendAddWordCode(t: String) {
        val c = t.lowercase().filter { it.isLetter() }
        if (addWordCode.length + c.length <= 4) addWordCode += c
        listener?.onAddWordChanged(); invalidate()
    }
    fun deleteAddWord() {
        if (editingCode) {
            if (addWordCode.isNotEmpty()) addWordCode = addWordCode.dropLast(1)
        } else if (addWordText.isNotEmpty()) addWordText = addWordText.dropLast(1)
        listener?.onAddWordChanged(); invalidate()
    }

    fun beginPick(chars: List<String>) {
        pickingHanzi = true
        pickChars = chars
        pickSelected.clear()
        expandVisible = false
        rankVisible = false
        invalidate()
    }

    fun endPick() {
        pickingHanzi = false
        pickChars = emptyList()
        pickSelected.clear()
        invalidate()
    }

    fun undoPick() {
        if (pickSelected.isNotEmpty()) {
            pickSelected.remove(pickSelected.maxOrNull()!!)
            invalidate()
        } else endPick()
    }

    fun commitPick() {
        val text = pickChars.indices.filter { it in pickSelected }.joinToString("") { pickChars[it] }
        listener?.onFinishPickHanzi(text)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, contentHeight)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        rebuild()
    }

    private fun notifyHeight() {
        listener?.onHeightChanged(contentHeight)
        requestLayout()
    }

    private fun rebuild() {
        keys.clear()
        if (width <= 0) return
        val rows = layout()
        val top = barH + sameH
        val rowH = (height - top) / rows.size.toFloat()
        val insetY = KeyboardTheme.dp(context, 5.5f)
        val insetX = KeyboardTheme.dp(context, 3f)
        rows.forEachIndexed { ri, row ->
            val y = top + ri * rowH
            val total = row.sumOf { it.widthFactor.toDouble() }.toFloat().coerceAtLeast(0.01f)
            var x = 0f
            if (page == KeyboardPage.Letters && ri == 1) {
                val pad = width * 0.05f
                x = pad
            }
            row.forEachIndexed { i, spec ->
                val isLetter = spec.action == KeyAction.Character && !spec.special
                val cellW = if (page == KeyboardPage.Letters && ri <= 2 && isLetter) width * 0.1f
                else if (page == KeyboardPage.Letters && ri <= 2) {
                    val letters = row.count { it.action == KeyAction.Character && !it.special }
                    (width - letters * width * 0.1f) / max(1, row.size - letters)
                } else width * (spec.widthFactor / total)
                val cell = RectF(x + 0.5f, y, x + cellW - 0.5f, y + rowH)
                keys.add(PlacedKey(spec, cell))
                x += cellW
            }
        }
        invalidate()
    }

    private fun layout(): List<List<KeySpec>> = when (page) {
        KeyboardPage.Letters -> letterLayout()
        KeyboardPage.Numbers -> numberLayout()
        KeyboardPage.Symbols -> symbolLayout()
    }

    private fun letterLayout(): List<List<KeySpec>> {
        val upper = if (english) shift || caps else true
        val bold = !english && (shift || caps)
        fun chars(s: String) = s.map { c ->
            val raw = c.toString()
            val text = if (upper) raw.uppercase() else raw.lowercase()
            val payload = if (english) text else raw.lowercase()
            KeySpec(text, KeyAction.Character, payload, bold = bold)
        }
        val lang = if (english) "英" else "中"
        return listOf(
            chars("qwertyuiop"),
            chars("asdfghjkl"),
            listOf(
                KeySpec("", KeyAction.Shift, widthFactor = 1.5f, special = true, showsPopup = false, bold = shift || caps)
            ) + chars("zxcvbnm") + listOf(
                KeySpec("", KeyAction.Delete, widthFactor = 1.5f, special = true, showsPopup = false)
            ),
            bottom(KeySpec("123", KeyAction.SwitchNumbers, widthFactor = 1.4f, special = true, showsPopup = false), lang)
        )
    }

    private fun numberLayout(): List<List<KeySpec>> {
        fun c(items: List<String>) = items.map { KeySpec(it, KeyAction.Character, it) }
        return listOf(
            c(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")),
            c(listOf("-", "/", ":", ";", "(", ")", "$", "&", "@", "\"")),
            listOf(KeySpec("#+=", KeyAction.SwitchSymbols, widthFactor = 1.5f, special = true, showsPopup = false)) +
                c(listOf(".", ",", "?", "!", "'")) +
                listOf(KeySpec("", KeyAction.Delete, widthFactor = 1.5f, special = true, showsPopup = false)),
            bottom(KeySpec("ABC", KeyAction.SwitchLetters, widthFactor = 1.4f, special = true, showsPopup = false), if (english) "英" else "中")
        )
    }

    private fun symbolLayout(): List<List<KeySpec>> {
        fun c(items: List<String>) = items.map { KeySpec(it, KeyAction.Character, it) }
        return listOf(
            c(listOf("[", "]", "{", "}", "#", "%", "^", "*", "+", "=")),
            c(listOf("_", "\\", "|", "~", "<", ">", "€", "£", "¥", "•")),
            listOf(KeySpec("123", KeyAction.SwitchNumbers, widthFactor = 1.5f, special = true, showsPopup = false)) +
                c(listOf(".", ",", "?", "!", "'")) +
                listOf(KeySpec("", KeyAction.Delete, widthFactor = 1.5f, special = true, showsPopup = false)),
            bottom(KeySpec("ABC", KeyAction.SwitchLetters, widthFactor = 1.4f, special = true, showsPopup = false), if (english) "英" else "中")
        )
    }

    private fun bottom(leading: KeySpec, lang: String): List<KeySpec> {
        // 对齐 iOS phoneBottomRow：有地球键时 123/地球/中英/空格/回车
        return if (needsGlobe) {
            listOf(
                leading.copy(widthFactor = 1.2f),
                KeySpec("", KeyAction.NextInput, widthFactor = 1.0f, special = true, showsPopup = false),
                KeySpec(lang, KeyAction.ToggleLanguage, widthFactor = 1.0f, special = true, showsPopup = false),
                KeySpec("", KeyAction.Space, widthFactor = 4.5f, showsPopup = false, badge = if (english) null else "五"),
                KeySpec(returnTitle, KeyAction.Return, widthFactor = 2.1f, special = true, showsPopup = false)
            )
        } else {
            listOf(
                leading.copy(widthFactor = 1.4f),
                KeySpec(lang, KeyAction.ToggleLanguage, widthFactor = 1.1f, special = true, showsPopup = false),
                KeySpec("", KeyAction.Space, widthFactor = 5.2f, showsPopup = false, badge = if (english) null else "五"),
                KeySpec(returnTitle, KeyAction.Return, widthFactor = 2.1f, special = true, showsPopup = false)
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.alpha = 255
        textPaint.alpha = 255
        canvas.drawColor(theme.background)
        drawBar(canvas)
        if (sameH > 0) drawSameCode(canvas)
        val radius = KeyboardTheme.dp(context, 5.5f)
        val insetX = KeyboardTheme.dp(context, 3f)
        val insetY = KeyboardTheme.dp(context, 5.5f)
        val shadowDy = KeyboardTheme.dp(context, 1f)
        keys.forEachIndexed { i, k ->
            val pressed = i == pressedIndex
            face.set(k.cell.left + insetX, k.cell.top + insetY, k.cell.right - insetX, k.cell.bottom - insetY)
            // iOS 键帽底阴影（按下时收起）
            if (!pressed) {
                paint.style = Paint.Style.FILL
                paint.color = theme.keyShadow
                shadow.set(face.left, face.top + shadowDy, face.right, face.bottom + shadowDy)
                canvas.drawRoundRect(shadow, radius, radius, paint)
            }
            paint.color = when {
                pressed && k.spec.special -> theme.specialPressed
                pressed -> theme.keyPressed
                k.spec.special -> theme.specialFill
                else -> theme.keyFill
            }
            canvas.drawRoundRect(face, radius, radius, paint)
            drawKeyContent(canvas, k.spec, face, pressed)
            // iOS 字符键按下红三角预览
            if (pressed && k.spec.showsPopup && k.spec.action == KeyAction.Character) {
                drawPopupTriangle(canvas, face)
            }
        }
        if (expandVisible) drawExpand(canvas)
        if (rankVisible) drawRank(canvas)
    }

    private fun drawKeyContent(canvas: Canvas, spec: KeySpec, face: RectF, pressed: Boolean) {
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.alpha = 255
        textPaint.color = theme.title
        textPaint.typeface = if (spec.bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
        when (spec.action) {
            KeyAction.Shift -> drawShiftIcon(canvas, face, filled = shift || caps)
            KeyAction.Delete -> drawDeleteIcon(canvas, face)
            KeyAction.NextInput -> drawGlobeIcon(canvas, face)
            KeyAction.Space -> {
                spec.badge?.let { b ->
                    textPaint.textAlign = Paint.Align.RIGHT
                    textPaint.textSize = KeyboardTheme.dp(context, 11f)
                    textPaint.color = theme.hint
                    canvas.drawText(
                        b,
                        face.right - KeyboardTheme.dp(context, 8f),
                        face.bottom - KeyboardTheme.dp(context, 6f),
                        textPaint
                    )
                    textPaint.textAlign = Paint.Align.CENTER
                }
            }
            else -> {
                val title = spec.title
                if (title.isEmpty()) return
                textPaint.textSize = if (spec.special) KeyboardTheme.dp(context, 15f) else KeyboardTheme.dp(context, 22f)
                val cy = face.centerY() - (textPaint.descent() + textPaint.ascent()) / 2
                canvas.drawText(title, face.centerX(), cy, textPaint)
            }
        }
        if (spec.action == KeyAction.Shift && caps) {
            paint.style = Paint.Style.FILL
            paint.color = theme.title
            canvas.drawCircle(
                face.right - KeyboardTheme.dp(context, 7f),
                face.bottom - KeyboardTheme.dp(context, 7f),
                KeyboardTheme.dp(context, 2.2f),
                paint
            )
        }
    }

    // iOS PopupPreviewView：键上方红三角指向被按键
    private fun drawPopupTriangle(canvas: Canvas, face: RectF) {
        val size = KeyboardTheme.dp(context, 8f)
        val cx = face.centerX()
        val top = face.top - KeyboardTheme.dp(context, 2f)
        paint.style = Paint.Style.FILL
        paint.color = theme.accent
        path.reset()
        path.moveTo(cx, top)
        path.lineTo(cx - size, top - size * 1.2f)
        path.lineTo(cx + size, top - size * 1.2f)
        path.close()
        canvas.drawPath(path, paint)
    }

    private fun drawShiftIcon(canvas: Canvas, face: RectF, filled: Boolean) {
        val cx = face.centerX()
        val cy = face.centerY()
        val h = KeyboardTheme.dp(context, 14f)
        val w = KeyboardTheme.dp(context, 16f)
        path.reset()
        path.moveTo(cx, cy - h / 2)
        path.lineTo(cx + w / 2, cy)
        path.lineTo(cx + w / 4, cy)
        path.lineTo(cx + w / 4, cy + h / 2)
        path.lineTo(cx - w / 4, cy + h / 2)
        path.lineTo(cx - w / 4, cy)
        path.lineTo(cx - w / 2, cy)
        path.close()
        if (filled) {
            paint.style = Paint.Style.FILL
            paint.color = theme.title
            canvas.drawPath(path, paint)
        } else {
            iconPaint.color = theme.title
            iconPaint.strokeWidth = KeyboardTheme.dp(context, 1.6f)
            iconPaint.style = Paint.Style.STROKE
            canvas.drawPath(path, iconPaint)
        }
    }

    private fun drawDeleteIcon(canvas: Canvas, face: RectF) {
        val cx = face.centerX()
        val cy = face.centerY()
        val w = KeyboardTheme.dp(context, 20f)
        val h = KeyboardTheme.dp(context, 12f)
        path.reset()
        path.moveTo(cx - w / 2 + KeyboardTheme.dp(context, 4f), cy - h / 2)
        path.lineTo(cx + w / 2, cy - h / 2)
        path.lineTo(cx + w / 2, cy + h / 2)
        path.lineTo(cx - w / 2 + KeyboardTheme.dp(context, 4f), cy + h / 2)
        path.lineTo(cx - w / 2, cy)
        path.close()
        iconPaint.color = theme.title
        iconPaint.strokeWidth = KeyboardTheme.dp(context, 1.6f)
        iconPaint.style = Paint.Style.STROKE
        canvas.drawPath(path, iconPaint)
        // X
        val x = KeyboardTheme.dp(context, 3.5f)
        canvas.drawLine(cx - x + 2, cy - x, cx + x + 2, cy + x, iconPaint)
        canvas.drawLine(cx - x + 2, cy + x, cx + x + 2, cy - x, iconPaint)
    }

    private fun drawGlobeIcon(canvas: Canvas, face: RectF) {
        val cx = face.centerX()
        val cy = face.centerY()
        val r = KeyboardTheme.dp(context, 9f)
        iconPaint.color = theme.title
        iconPaint.strokeWidth = KeyboardTheme.dp(context, 1.5f)
        iconPaint.style = Paint.Style.STROKE
        canvas.drawCircle(cx, cy, r, iconPaint)
        canvas.drawOval(cx - r / 2.2f, cy - r, cx + r / 2.2f, cy + r, iconPaint)
        canvas.drawLine(cx - r, cy, cx + r, cy, iconPaint)
        canvas.drawLine(cx - r * 0.85f, cy - r / 2.5f, cx + r * 0.85f, cy - r / 2.5f, iconPaint)
        canvas.drawLine(cx - r * 0.85f, cy + r / 2.5f, cx + r * 0.85f, cy + r / 2.5f, iconPaint)
    }

    private enum class ToolMode { Normal, AddWord, Pick }

    private data class ToolSeg(val action: Int, val title: String?, val width: Float, val enabled: Boolean)

    // 造词/滑选时右侧换成确认按钮，平时才是展开|排序|造词
    private fun toolMode(): ToolMode = when {
        pickingHanzi -> ToolMode.Pick
        addWord -> ToolMode.AddWord
        else -> ToolMode.Normal
    }

    // action：0 展开 / 1 排序 / 2 造词 / 3 取消 / 4 保存 / 5 完成选字；-1 是分隔线
    private fun toolSegments(): List<ToolSeg> {
        val div = dp(1f)
        return when (toolMode()) {
            ToolMode.Pick -> listOf(
                ToolSeg(5, "完成选字", measureToolLabel("完成选字"), pickSelected.isNotEmpty())
            )
            ToolMode.AddWord -> listOf(
                ToolSeg(3, "取消", measureToolLabel("取消"), true),
                ToolSeg(-1, null, div, true),
                ToolSeg(4, "保存", measureToolLabel("保存"), addWordCode.isNotEmpty() && addWordText.isNotEmpty())
            )
            ToolMode.Normal -> {
                val list = mutableListOf(
                    ToolSeg(0, null, dp(36f), expandEnabled || expandVisible)
                )
                if (rankAvailable) {
                    list.add(ToolSeg(-1, null, div, true))
                    list.add(ToolSeg(1, "排序", measureToolLabel("排序"), true))
                }
                list.add(ToolSeg(-1, null, div, true))
                list.add(ToolSeg(2, "造词", measureToolLabel("造词"), preedit.isNotEmpty() || recentAvailable))
                list
            }
        }
    }

    // 胶囊宽度按当前可见按钮计算，避免被左侧词条挤没
    private fun toolCapsuleWidth(): Float {
        if (english && toolMode() == ToolMode.Normal) return 0f
        var w = dp(4f)
        toolSegments().forEach { w += it.width }
        return max(w, dp(44f))
    }

    private fun measureToolLabel(title: String): Float {
        textPaint.textSize = dp(13f)
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        return textPaint.measureText(title) + dp(24f)
    }

    private fun candidateAreaRight(): Float {
        if (english && toolMode() == ToolMode.Normal) return width.toFloat()
        return width - toolCapsuleWidth() - toolCapsuleMargin * 2
    }

    // 候选格子宽度跟词条（和备注）走，左右各留 10，再加 2 防止字形被切
    private fun chipWidth(text: String, comment: String): Float {
        textPaint.textSize = dp(20f)
        textPaint.typeface = medium
        var w = textPaint.measureText(text)
        if (KeyboardPrefs.showCommentInPanel && comment.isNotEmpty()) {
            textPaint.textSize = dp(11f)
            textPaint.typeface = Typeface.DEFAULT
            w += dp(4f) + textPaint.measureText(comment)
        }
        return kotlin.math.ceil(w) + dp(22f)
    }

    // 展开格：词多宽格子多宽，不再五等分
    private fun expandChipWidth(text: String, comment: String): Float {
        textPaint.textSize = dp(20f)
        textPaint.typeface = medium
        var w = textPaint.measureText(text)
        if (KeyboardPrefs.showCommentInPanel && comment.isNotEmpty()) {
            textPaint.textSize = dp(11f)
            textPaint.typeface = Typeface.DEFAULT
            w += dp(4f) + textPaint.measureText(comment)
        }
        return max(kotlin.math.ceil(w) + dp(24f), dp(48f))
    }

    private fun pickWidth(text: String): Float {
        textPaint.textSize = dp(20f)
        textPaint.typeface = medium
        return kotlin.math.ceil(textPaint.measureText(text)) + dp(13f)
    }

    private fun drawBar(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.alpha = 255
        paint.color = theme.separator
        canvas.drawRect(0f, barH - 1f, width.toFloat(), barH, paint)
        candidateRects.clear()
        addWordCodeRect.setEmpty()
        addWordTextRect.setEmpty()
        loneCodeRect.setEmpty()
        firstCodeRect.setEmpty()
        pickIndexForRect.clear()

        val areaRight = candidateAreaRight()
        if (english && !addWord && !pickingHanzi) {
            drawEnglishHint(canvas, areaRight)
            drawTools(canvas)
            return
        }
        var contentLeft = dp(6f)
        if (addWord) contentLeft = drawAddWordStatus(canvas, contentLeft, areaRight)
        if (pickingHanzi) drawPickBar(canvas, contentLeft, areaRight)
        else drawCandidateChips(canvas, contentLeft, areaRight)
        drawTools(canvas)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = Typeface.DEFAULT
        paint.style = Paint.Style.FILL
    }

    private fun drawEnglishHint(canvas: Canvas, areaRight: Float) {
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.typeface = medium
        textPaint.textSize = dp(20f)
        textPaint.color = theme.title
        textPaint.alpha = 82
        val cy = barH / 2f + barContentDy - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText("English", dp(16f), cy, textPaint)
        textPaint.alpha = 255
    }

    // 左侧编码→词条，宽度封顶，给右侧取消/保存留位
    private fun drawAddWordStatus(canvas: Canvas, startX: Float, areaRight: Float): Float {
        val cap = min(dp(148f), (areaRight - startX).coerceAtLeast(dp(48f)))
        val code = if (addWordCode.isEmpty()) "?" else addWordCode
        val word = displayTruncatedWord(addWordText)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = dp(20f)
        textPaint.typeface = medium
        val codeW = textPaint.measureText(code)
        textPaint.textSize = dp(16f)
        textPaint.typeface = Typeface.DEFAULT
        val arrowW = textPaint.measureText("→")
        textPaint.textSize = dp(18f)
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        val wordW = textPaint.measureText(word)
        val gap = dp(4f)
        var wordDrawW = wordW
        var total = codeW + gap + arrowW + gap + wordW
        if (total > cap) {
            wordDrawW = (cap - codeW - arrowW - gap * 2).coerceAtLeast(dp(24f))
            total = cap
        }
        val yBase = barH / 2f + barContentDy
        var x = startX
        val save = canvas.save()
        canvas.clipRect(startX, 0f, startX + total, barH)

        textPaint.textSize = dp(20f)
        textPaint.typeface = medium
        textPaint.color = theme.title
        textPaint.alpha = if (editingCode) 255 else 115
        val codeCy = yBase - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(code, x, codeCy, textPaint)
        addWordCodeRect.set(x, 0f, x + codeW, barH)
        if (editingCode) drawUnderline(canvas, x, x + codeW, codeCy)
        x += codeW + gap

        textPaint.textSize = dp(16f)
        textPaint.typeface = Typeface.DEFAULT
        textPaint.alpha = 180
        val arrowCy = yBase - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText("→", x, arrowCy, textPaint)
        x += arrowW + gap

        textPaint.textSize = dp(18f)
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textPaint.color = theme.title
        textPaint.alpha = if (editingCode) 115 else 255
        val wordCy = yBase - (textPaint.descent() + textPaint.ascent()) / 2f
        val wordRight = x + wordDrawW
        canvas.drawText(word, x, wordCy, textPaint)
        addWordTextRect.set(x, 0f, wordRight, barH)
        if (!editingCode) drawUnderline(canvas, x, min(x + wordW, wordRight), wordCy)
        textPaint.alpha = 255
        canvas.restoreToCount(save)
        return startX + total + dp(6f)
    }

    private fun drawUnderline(canvas: Canvas, left: Float, right: Float, baseline: Float) {
        paint.style = Paint.Style.FILL
        paint.color = theme.title
        paint.alpha = 200
        val y = baseline + dp(2f)
        canvas.drawRect(left, y, right, y + dp(1f), paint)
        paint.alpha = 255
    }

    // 手机上超过 4 个字折成首尾，避免词条把确认按钮顶没
    private fun displayTruncatedWord(text: String): String {
        if (text.isEmpty()) return "…"
        val cps = text.codePoints().toArray()
        if (cps.size <= 4) return text
        return String(cps, 0, 2) + "..." + String(cps, cps.size - 2, 2)
    }

    // 编码比词条宽时折成首...尾
    private fun middleTruncate(text: String, maxW: Float): String {
        if (text.isEmpty() || textPaint.measureText(text) <= maxW) return text
        val ell = "..."
        val ellW = textPaint.measureText(ell)
        if (maxW <= ellW + 2f) return ell
        val cps = text.codePoints().toArray()
        var best = ell
        var low = 2
        var high = cps.size
        while (low <= high) {
            val keep = (low + high) / 2
            val head = keep / 2
            val tail = keep - head
            if (head < 1 || tail < 1 || head + tail >= cps.size) {
                high = keep - 1
                continue
            }
            val candidate = String(cps, 0, head) + ell + String(cps, cps.size - tail, tail)
            if (textPaint.measureText(candidate) <= maxW) {
                best = candidate
                low = keep + 1
            } else {
                high = keep - 1
            }
        }
        return best
    }

    private fun drawCandidateChips(canvas: Canvas, left: Float, right: Float) {
        val avail = (right - left).coerceAtLeast(0f)
        val spacing = dp(4f)
        if (candidates.isEmpty()) {
            visibleCount = 0
            expandEnabled = false
            maxCandidateScroll = 0f
            if (preedit.isNotEmpty() && !addWord) drawLoneCode(canvas, left, right)
            return
        }
        val widths = FloatArray(candidates.size) { i ->
            chipWidth(candidates[i], comments.getOrNull(i).orEmpty())
        }
        var content = 0f
        var fittingUsed = 0f
        var fitting = 0
        widths.forEachIndexed { i, w ->
            val extra = if (i == 0) w else spacing + w
            content += extra
            if (fitting == i && fittingUsed + extra <= avail + 0.5f) {
                fittingUsed += extra
                fitting++
            }
        }
        visibleCount = fitting
        expandEnabled = fitting < candidates.size
        val canScroll = !expandVisible && content > avail + 0.5f
        maxCandidateScroll = if (canScroll) max(0f, content - avail) else 0f
        candidateScrollX = candidateScrollX.coerceIn(0f, maxCandidateScroll)

        val save = canvas.save()
        canvas.clipRect(left, 0f, right, barH)
        var x = left - if (canScroll) candidateScrollX else 0f
        val drawCount = if (expandVisible) fitting else candidates.size
        for (i in 0 until drawCount) {
            val w = widths[i]
            val rect = RectF(x, 0f, x + w, barH)
            candidateRects.add(rect)
            drawChip(canvas, rect, candidates[i], comments.getOrNull(i).orEmpty(), showCode = i == 0)
            x += w + spacing
        }
        canvas.restoreToCount(save)
    }

    private fun drawChip(canvas: Canvas, rect: RectF, text: String, comment: String, showCode: Boolean) {
        val save = canvas.save()
        canvas.clipRect(rect)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.typeface = medium
        textPaint.textSize = dp(20f)
        textPaint.color = theme.title
        textPaint.alpha = 255
        val textX = rect.left + dp(10f)
        // 词条在候选栏里纵向居中后再下移，编码仍相对词条定位
        val cy = barH / 2f + barContentDy - (textPaint.descent() + textPaint.ascent()) / 2f
        val wordW = textPaint.measureText(text)
        canvas.drawText(text, textX, cy, textPaint)
        var cursor = textX + wordW
        if (KeyboardPrefs.showCommentInPanel && comment.isNotEmpty()) {
            textPaint.textSize = dp(11f)
            textPaint.typeface = Typeface.DEFAULT
            textPaint.color = theme.hint
            val ccy = barH / 2f + barContentDy - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(comment, cursor + dp(4f), ccy, textPaint)
        }
        if (showCode && preedit.isNotEmpty()) {
            textPaint.textSize = dp(20f)
            textPaint.typeface = medium
            val wordTop = cy + textPaint.ascent()
            textPaint.textSize = dp(8f)
            textPaint.typeface = Typeface.MONOSPACE
            textPaint.color = theme.title
            textPaint.alpha = 255
            val code = middleTruncate(preedit, max(dp(12f), wordW))
            val codeW = textPaint.measureText(code)
            // 相对首个词条绝对定位：贴在词条上方，空隙比贴住时多一点
            val codeBase = wordTop - textPaint.descent() - dp(3f)
            canvas.drawText(code, textX, codeBase, textPaint)
            val codeTop = codeBase + textPaint.ascent()
            firstCodeRect.set(textX - dp(2f), codeTop - dp(2f), textX + codeW + dp(4f), wordTop)
            textPaint.alpha = 255
        }
        canvas.restoreToCount(save)
    }

    private fun drawLoneCode(canvas: Canvas, left: Float, right: Float) {
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.typeface = medium
        textPaint.textSize = dp(20f)
        textPaint.color = theme.title
        textPaint.alpha = 255
        val maxW = max(dp(24f), right - left - dp(8f))
        val display = middleTruncate(preedit, maxW - dp(8f))
        val w = min(textPaint.measureText(display) + dp(16f), maxW)
        val cy = barH / 2f + barContentDy - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(display, left + dp(4f), cy, textPaint)
        loneCodeRect.set(left, 0f, left + w, barH)
        candidateRects.add(loneCodeRect)
    }

    // 滑选按每个字自己的宽度排，右侧留给「完成选字」
    private fun drawPickBar(canvas: Canvas, left: Float, right: Float) {
        val avail = (right - left).coerceAtLeast(dp(28f))
        var used = 0f
        var start = pickChars.size
        for (i in pickChars.indices.reversed()) {
            val w = pickWidth(pickChars[i])
            if (start != pickChars.size && used + w > avail) break
            used += w
            start = i
        }
        val save = canvas.save()
        canvas.clipRect(left, 0f, right, barH)
        var x = left
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = dp(20f)
        textPaint.typeface = medium
        for (i in start until pickChars.size) {
            val w = pickWidth(pickChars[i])
            val rect = RectF(x, 0f, x + w, barH)
            candidateRects.add(rect)
            pickIndexForRect.add(i)
            val fill = RectF(rect.left + dp(2f), rect.top + dp(8f), rect.right - dp(2f), rect.bottom - dp(8f))
            if (i in pickSelected) {
                paint.style = Paint.Style.FILL
                paint.color = theme.accent
                canvas.drawRoundRect(fill, dp(8f), dp(8f), paint)
                textPaint.color = android.graphics.Color.WHITE
            } else {
                textPaint.color = theme.title
            }
            val cy = barH / 2f + barContentDy - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(pickChars[i], rect.centerX(), cy, textPaint)
            x += w
        }
        canvas.restoreToCount(save)
    }

    private fun drawTools(canvas: Canvas) {
        toolHitRects.clear()
        if (english && toolMode() == ToolMode.Normal) {
            toolCapsuleRect.setEmpty()
            return
        }
        val segs = toolSegments()
        val capW = toolCapsuleWidth()
        val capH = toolCapsuleH
        val left = width - toolCapsuleMargin - capW
        val top = (barH - capH) / 2f + barContentDy
        toolCapsuleRect.set(left, top, left + capW, top + capH)
        val radius = capH / 2f
        paint.style = Paint.Style.FILL
        paint.color = theme.specialFill
        paint.alpha = 235
        canvas.drawRoundRect(toolCapsuleRect, radius, radius, paint)
        paint.alpha = 255

        var x = left + dp(2f)
        segs.forEach { seg ->
            val rect = RectF(x, top, x + seg.width, top + capH)
            if (seg.action < 0) {
                paint.color = theme.title
                paint.alpha = 46
                val mid = rect.centerX()
                canvas.drawRect(mid, top + dp(7f), mid + 1f, top + capH - dp(7f), paint)
                paint.alpha = 255
            } else {
                toolHitRects.add(rect to seg.action)
                if (seg.title == null) drawExpandGlyph(canvas, rect, seg.enabled)
                else drawToolLabel(canvas, seg.title, rect, seg.enabled)
            }
            x += seg.width
        }
    }

    private fun drawToolLabel(canvas: Canvas, title: String, rect: RectF, enabled: Boolean) {
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = dp(13f)
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textPaint.color = theme.title
        textPaint.alpha = if (enabled) 255 else 100
        val ty = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(title, rect.centerX(), ty, textPaint)
        textPaint.alpha = 255
        textPaint.typeface = Typeface.DEFAULT
    }

    // 展开/收起：对角双向箭头
    private fun drawExpandGlyph(canvas: Canvas, rect: RectF, enabled: Boolean) {
        iconPaint.color = theme.title
        iconPaint.alpha = if (enabled) 255 else 90
        iconPaint.strokeWidth = dp(1.7f)
        iconPaint.style = Paint.Style.STROKE
        val cx = rect.centerX()
        val cy = rect.centerY()
        val arm = dp(4.5f)
        val tip = dp(3.2f)
        if (expandVisible) {
            canvas.drawLine(cx - arm, cy - arm, cx - tip, cy - tip, iconPaint)
            canvas.drawLine(cx - arm, cy - arm, cx - arm, cy - tip, iconPaint)
            canvas.drawLine(cx - arm, cy - arm, cx - tip, cy - arm, iconPaint)
            canvas.drawLine(cx + arm, cy + arm, cx + tip, cy + tip, iconPaint)
            canvas.drawLine(cx + arm, cy + arm, cx + arm, cy + tip, iconPaint)
            canvas.drawLine(cx + arm, cy + arm, cx + tip, cy + arm, iconPaint)
        } else {
            canvas.drawLine(cx - tip, cy - tip, cx - arm, cy - arm, iconPaint)
            canvas.drawLine(cx - arm, cy - arm, cx - arm, cy - tip, iconPaint)
            canvas.drawLine(cx - arm, cy - arm, cx - tip, cy - arm, iconPaint)
            canvas.drawLine(cx + tip, cy + tip, cx + arm, cy + arm, iconPaint)
            canvas.drawLine(cx + arm, cy + arm, cx + arm, cy + tip, iconPaint)
            canvas.drawLine(cx + arm, cy + arm, cx + tip, cy + arm, iconPaint)
        }
        iconPaint.alpha = 255
    }

    private fun drawSameCode(canvas: Canvas) {
        val top = barH
        paint.style = Paint.Style.FILL
        paint.alpha = 255
        paint.color = theme.panelFill
        canvas.drawRect(0f, top, width.toFloat(), top + sameH, paint)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = dp(12f)
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textPaint.color = theme.title
        textPaint.alpha = 140
        canvas.drawText("同码", dp(10f), top + dp(16f), textPaint)
        textPaint.alpha = 255
        if (sameCode.isEmpty()) {
            textPaint.textSize = dp(15f)
            textPaint.typeface = Typeface.DEFAULT
            textPaint.color = theme.hint
            canvas.drawText("暂无同码词条", dp(46f), top + dp(17f), textPaint)
            maxSameScroll = 0f
            sameRects.clear()
            return
        }
        val contentTop = dp(22f)
        val pad = dp(8f)
        val gapX = dp(6f)
        val gapY = dp(4f)
        val rowH = dp(28f)
        var x = pad
        var y = contentTop
        sameRects.clear()
        textPaint.textSize = dp(16f)
        textPaint.typeface = medium
        sameCode.forEach { word ->
            val natural = kotlin.math.ceil(textPaint.measureText(word)) + dp(16f)
            // 词多宽格子多宽；比屏幕还长才收到一行里，避免和旁边的词叠在一起
            val chipW = natural.coerceIn(dp(36f), (width - pad * 2).coerceAtLeast(dp(36f)))
            if (x > pad && x + chipW > width - pad) {
                x = pad
                y += rowH + gapY
            }
            sameRects.add(RectF(x, y, x + chipW, y + rowH))
            x += chipW + gapX
        }
        val contentBottom = (sameRects.lastOrNull()?.bottom ?: contentTop) + dp(6f)
        val viewBottom = sameH
        maxSameScroll = max(0f, contentBottom - viewBottom)
        sameScrollY = sameScrollY.coerceIn(0f, maxSameScroll)

        val save = canvas.save()
        canvas.clipRect(0f, top + contentTop, width.toFloat(), top + sameH)
        canvas.translate(0f, top - sameScrollY)
        sameRects.forEachIndexed { i, r ->
            val word = sameCode[i]
            val hi = word == sameHighlight && sameHighlight.isNotEmpty()
            paint.color = if (hi) theme.accent else theme.keyFill
            paint.alpha = if (hi) 70 else 255
            canvas.drawRoundRect(r, dp(6f), dp(6f), paint)
            paint.alpha = 255
            textPaint.textAlign = Paint.Align.LEFT
            textPaint.color = theme.title
            textPaint.textSize = dp(16f)
            textPaint.typeface = medium
            val ty = r.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
            val clip = canvas.save()
            canvas.clipRect(r)
            canvas.drawText(word, r.left + dp(8f), ty, textPaint)
            canvas.restoreToCount(clip)
        }
        canvas.restoreToCount(save)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = Typeface.DEFAULT
    }

    // 展开层：左对齐按词宽换行，装不下就滚动
    private fun drawExpand(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.alpha = 255
        paint.color = theme.panelFill
        canvas.drawRect(0f, barH, width.toFloat(), height.toFloat(), paint)
        expandRects.clear()
        val skip = visibleCount.coerceIn(0, candidates.size)
        val rest = candidates.drop(skip)
        if (rest.isEmpty()) {
            maxExpandScrollX = 0f
            maxExpandScrollY = 0f
            return
        }
        val pad = dp(8f)
        val rowH = dp(44f)
        var x = pad
        var y = dp(4f)
        var contentRight = width.toFloat()
        var contentBottom = y
        val panelW = width.toFloat()
        rest.forEachIndexed { i, text ->
            val comment = comments.getOrNull(skip + i).orEmpty()
            val w = expandChipWidth(text, comment)
            if (x > pad && x + w > panelW - pad) {
                x = pad
                y += rowH
            }
            val rect = RectF(x, y, x + w, y + rowH)
            expandRects.add(rect to (skip + i))
            contentRight = max(contentRight, rect.right + pad)
            contentBottom = rect.bottom + dp(8f)
            x += w
        }
        val viewH = (height - barH).coerceAtLeast(1f)
        maxExpandScrollX = max(0f, contentRight - panelW)
        maxExpandScrollY = max(0f, contentBottom - viewH)
        expandScrollX = expandScrollX.coerceIn(0f, maxExpandScrollX)
        expandScrollY = expandScrollY.coerceIn(0f, maxExpandScrollY)

        val save = canvas.save()
        canvas.clipRect(0f, barH, panelW, height.toFloat())
        canvas.translate(-expandScrollX, barH - expandScrollY)
        expandRects.forEach { (rect, index) ->
            val text = candidates.getOrNull(index).orEmpty()
            val comment = comments.getOrNull(index).orEmpty()
            val clip = canvas.save()
            canvas.clipRect(rect)
            textPaint.textAlign = Paint.Align.LEFT
            textPaint.typeface = medium
            textPaint.textSize = dp(20f)
            textPaint.color = theme.title
            textPaint.alpha = 255
            val textX = rect.left + dp(12f)
            val cy = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(text, textX, cy, textPaint)
            if (KeyboardPrefs.showCommentInPanel && comment.isNotEmpty()) {
                val wordW = textPaint.measureText(text)
                textPaint.textSize = dp(11f)
                textPaint.typeface = Typeface.DEFAULT
                textPaint.color = theme.hint
                val ccy = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
                canvas.drawText(comment, textX + wordW + dp(4f), ccy, textPaint)
            }
            canvas.restoreToCount(clip)
            paint.color = theme.separator
            paint.alpha = 255
            canvas.drawRect(rect.right - 1f, rect.top + dp(8f), rect.right, rect.bottom - dp(8f), paint)
            canvas.drawRect(rect.left + dp(8f), rect.bottom - 1f, rect.right - dp(8f), rect.bottom, paint)
        }
        canvas.restoreToCount(save)
        drawScrollThumb(canvas, barH, height.toFloat(), expandScrollY, maxExpandScrollY)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = Typeface.DEFAULT
    }

    // 内容超出时在右侧画一条滚动条，提示可以滑
    private fun drawScrollThumb(canvas: Canvas, top: Float, bottom: Float, scroll: Float, maxScroll: Float) {
        if (maxScroll <= 1f) return
        val trackTop = top + dp(6f)
        val trackBottom = bottom - dp(6f)
        val trackH = (trackBottom - trackTop).coerceAtLeast(dp(20f))
        val thumbH = max(dp(18f), trackH * trackH / (trackH + maxScroll))
        val travel = (trackH - thumbH).coerceAtLeast(0f)
        val thumbTop = trackTop + travel * (scroll / maxScroll)
        paint.style = Paint.Style.FILL
        paint.color = theme.title
        paint.alpha = 70
        val r = dp(1.5f)
        canvas.drawRoundRect(
            width - dp(4f), thumbTop, width - dp(1.5f), thumbTop + thumbH,
            r, r, paint
        )
        paint.alpha = 255
    }

    private fun drawRank(canvas: Canvas) {
        paint.color = theme.panelFill
        canvas.drawRect(0f, barH, width.toFloat(), height.toFloat(), paint)
        rankStepRects.clear()
        rankRowRects.clear()

        val headerTop = barH + KeyboardTheme.dp(context, 8f)
        // 编码
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = KeyboardTheme.dp(context, 15f)
        textPaint.typeface = Typeface.MONOSPACE
        textPaint.color = theme.title
        canvas.drawText(rankCode.ifEmpty { "—" }, KeyboardTheme.dp(context, 12f), headerTop + KeyboardTheme.dp(context, 16f), textPaint)
        textPaint.typeface = Typeface.DEFAULT

        // 幅度 5 / 10
        val step = KeyboardPrefs.candidateRankStep
        val stepY = headerTop
        val stepH = KeyboardTheme.dp(context, 28f)
        var sx = width - KeyboardTheme.dp(context, 150f)
        listOf(5, 10).forEach { v ->
            val r = RectF(sx, stepY, sx + KeyboardTheme.dp(context, 40f), stepY + stepH)
            rankStepRects.add(r to v)
            paint.color = if (step == v) theme.accent else theme.keyFill
            canvas.drawRoundRect(r, 8f, 8f, paint)
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = KeyboardTheme.dp(context, 14f)
            textPaint.color = if (step == v) android.graphics.Color.WHITE else theme.title
            canvas.drawText(v.toString(), r.centerX(), r.centerY() - (textPaint.descent() + textPaint.ascent()) / 2, textPaint)
            sx += KeyboardTheme.dp(context, 44f)
        }
        // 完成
        rankDoneRect.set(width - KeyboardTheme.dp(context, 56f), stepY, width - KeyboardTheme.dp(context, 8f), stepY + stepH)
        textPaint.color = theme.accent
        textPaint.textSize = KeyboardTheme.dp(context, 15f)
        canvas.drawText("完成", rankDoneRect.centerX(), rankDoneRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2, textPaint)

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = KeyboardTheme.dp(context, 12f)
        textPaint.color = theme.hint
        canvas.drawText("按住一行拖动调整顺序", KeyboardTheme.dp(context, 12f), headerTop + KeyboardTheme.dp(context, 40f), textPaint)

        val listTop = headerTop + KeyboardTheme.dp(context, 52f)
        rankListTop = listTop
        rankViewportBottom = height - KeyboardTheme.dp(context, 28f)
        maxRankScroll = max(0f, listTop + rankRows.size * rankRowH - rankViewportBottom)
        rankScrollY = rankScrollY.coerceIn(0f, maxRankScroll)
        val listSave = canvas.save()
        canvas.clipRect(0f, listTop, width.toFloat(), rankViewportBottom)
        canvas.translate(0f, -rankScrollY)
        textPaint.textSize = KeyboardTheme.dp(context, 16f)
        rankRows.forEachIndexed { i, row ->
            val top = listTop + i * rankRowH
            val rect = RectF(0f, top, width.toFloat(), top + rankRowH)
            rankRowRects.add(rect)
            val dragging = rankDragFrom == i
            if (rankDragTarget == i && rankDragFrom >= 0 && rankDragFrom != i) {
                paint.color = theme.accent
                paint.alpha = 40
                canvas.drawRect(rect, paint)
                paint.alpha = 255
            }
            textPaint.color = theme.title
            textPaint.alpha = if (dragging) 60 else 255
            val baseY = top + rankRowH / 2 - (textPaint.descent() + textPaint.ascent()) / 2
            val textClip = canvas.save()
            canvas.clipRect(dp(12f), top, width - dp(72f), top + rankRowH)
            canvas.drawText("☰  ${row.text}", KeyboardTheme.dp(context, 16f), baseY, textPaint)
            canvas.restoreToCount(textClip)
            textPaint.textAlign = Paint.Align.RIGHT
            textPaint.color = theme.hint
            canvas.drawText(row.weightText, width - KeyboardTheme.dp(context, 16f), baseY, textPaint)
            textPaint.textAlign = Paint.Align.LEFT
            textPaint.alpha = 255
        }
        canvas.restoreToCount(listSave)
        drawScrollThumb(canvas, listTop, rankViewportBottom, rankScrollY, maxRankScroll)
        // 拖动浮层
        if (rankDragFrom in rankRows.indices) {
            val row = rankRows[rankDragFrom]
            val y = (rankDragFingerY - rankDragOffsetY).coerceIn(listTop, height - rankRowH - KeyboardTheme.dp(context, 40f))
            paint.color = theme.panelFill
            paint.alpha = 230
            val floatR = RectF(KeyboardTheme.dp(context, 8f), y, width - KeyboardTheme.dp(context, 8f), y + rankRowH)
            canvas.drawRoundRect(floatR, 8f, 8f, paint)
            paint.alpha = 255
            paint.style = Paint.Style.STROKE
            paint.color = theme.accent
            paint.strokeWidth = 2f
            canvas.drawRoundRect(floatR, 8f, 8f, paint)
            paint.style = Paint.Style.FILL
            textPaint.color = theme.title
            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText("☰  ${row.text}", KeyboardTheme.dp(context, 20f), y + rankRowH / 2 - (textPaint.descent() + textPaint.ascent()) / 2, textPaint)
        }

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = KeyboardTheme.dp(context, 12f)
        textPaint.color = theme.hint
        canvas.drawText(rankStatus, width / 2f, height - KeyboardTheme.dp(context, 12f), textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                if (y < barH) {
                    beginBarTouch(x, y)
                    return true
                }
                if (rankVisible) {
                    handleRankDown(x, y)
                    return true
                }
                if (expandVisible) {
                    beginExpandTouch(x, y)
                    return true
                }
                if (sameH > 0f && y < barH + sameH) {
                    beginSameTouch(y)
                    return true
                }
                val idx = keys.indexOfFirst { it.cell.contains(x, y) }
                pressedIndex = idx
                if (idx >= 0) {
                    listener?.onTouchKey()
                    val spec = keys[idx].spec
                    if (spec.action == KeyAction.Character && spec.secondary == null) fire(spec)
                    if (spec.action == KeyAction.Delete) {
                        fire(spec)
                        armDelete()
                    }
                    if (spec.action == KeyAction.Space) {
                        lastSpaceX = x
                        spaceCursor = false
                        holdRunnable = Runnable {
                            spaceCursor = true
                        }.also { handler.postDelayed(it, 350) }
                    }
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (barTracking) {
                    handleBarMove(x)
                    return true
                }
                if (expandTracking) {
                    handleExpandMove(x, y)
                    return true
                }
                if (sameTracking) {
                    handleSameMove(y)
                    return true
                }
                if (rankTracking) {
                    handleRankMove(y)
                    return true
                }
                if (spaceCursor && pressedIndex >= 0 && keys.getOrNull(pressedIndex)?.spec?.action == KeyAction.Space) {
                    val dx = x - lastSpaceX
                    val step = dp(10f)
                    if (kotlin.math.abs(dx) >= step) {
                        listener?.onMoveCursor(if (dx > 0) 1 else -1)
                        lastSpaceX = x
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val commit = event.actionMasked == MotionEvent.ACTION_UP
                if (barTracking) {
                    endBarTouch(commit)
                    return true
                }
                if (expandTracking) {
                    endExpandTouch(x, y, commit)
                    return true
                }
                if (sameTracking) {
                    sameTracking = false
                    sameMoved = false
                    return true
                }
                if (rankTracking) {
                    endRankTouch(!commit)
                    return true
                }
                holdRunnable?.let { handler.removeCallbacks(it) }
                holdRunnable = null
                if (deleteArmed) {
                    deleteArmed = false
                    listener?.onDeleteRepeatStop()
                }
                val idx = pressedIndex
                pressedIndex = -1
                val spec = keys.getOrNull(idx)?.spec
                if (spec != null && commit && !spaceCursor) {
                    when (spec.action) {
                        KeyAction.Character -> if (spec.secondary != null) fire(spec)
                        KeyAction.Delete -> {}
                        else -> fire(spec)
                    }
                    if (spec.action == KeyAction.Character && shift && !caps && page == KeyboardPage.Letters) {
                        consumeOneShotShift()
                    }
                }
                spaceCursor = false
                val hold = KeyboardPrefs.keyPressHoldMillis
                if (hold > 0) handler.postDelayed({ invalidate() }, hold.toLong()) else invalidate()
            }
        }
        return true
    }

    // 候选栏：右侧按钮立刻响应；词条区先记下，抬起才点选，方便横滑
    private fun beginBarTouch(x: Float, y: Float) {
        listener?.onTouchKey()
        barTracking = false
        barMoved = false
        pickDragMoved = false
        pickAnchor = -1
        pendingBarTapX = Float.NaN
        pendingBarTapY = Float.NaN
        if (toolCapsuleRect.contains(x, y)) {
            handleTool(x, y)
            return
        }
        if (addWord) {
            if (addWordCodeRect.contains(x, y)) {
                editingCode = true
                invalidate()
                return
            }
            if (addWordTextRect.contains(x, y)) {
                editingCode = false
                invalidate()
                return
            }
        }
        if (pickingHanzi) {
            barTracking = true
            barDownX = x
            pickAnchor = pickIndexAt(x)
            pendingBarTapX = x
            return
        }
        barTracking = true
        barDownX = x
        barDownY = y
        barDownScroll = candidateScrollX
        pendingBarTapX = x
        pendingBarTapY = y
    }

    private fun handleTool(x: Float, y: Float) {
        val action = toolHitRects.firstOrNull { it.first.contains(x, y) }?.second ?: return
        when (action) {
            0 -> if (canExpand()) listener?.onExpand()
            1 -> if (rankAvailable) listener?.onRank()
            2 -> if (preedit.isNotEmpty() || recentAvailable) listener?.onAddWord()
            3 -> listener?.onCancelAddWord()
            4 -> if (addWordCode.isNotEmpty() && addWordText.isNotEmpty()) listener?.onSaveAddWord()
            5 -> commitPick()
        }
    }

    private fun handleBarMove(x: Float) {
        if (pickingHanzi) {
            val dx = x - barDownX
            if (!pickDragMoved && kotlin.math.abs(dx) < dp(8f)) return
            pickDragMoved = true
            barMoved = true
            val index = pickIndexAt(x)
            if (pickAnchor >= 0 && index >= 0) {
                val a = min(pickAnchor, index)
                val b = max(pickAnchor, index)
                for (i in a..b) pickSelected.add(i)
                invalidate()
            }
            return
        }
        val canScroll = !expandVisible && maxCandidateScroll > 0f
        if (!canScroll) return
        val dx = x - barDownX
        if (!barMoved && kotlin.math.abs(dx) < dp(6f)) return
        barMoved = true
        candidateScrollX = (barDownScroll - dx).coerceIn(0f, maxCandidateScroll)
        invalidate()
    }

    private fun endBarTouch(commitTap: Boolean) {
        val wasMoved = barMoved
        val tapX = pendingBarTapX
        val tapY = pendingBarTapY
        val anchor = pickAnchor
        val draggedPick = pickDragMoved
        barTracking = false
        barMoved = false
        pickDragMoved = false
        pickAnchor = -1
        pendingBarTapX = Float.NaN
        pendingBarTapY = Float.NaN
        if (pickingHanzi) {
            if (commitTap && !draggedPick && anchor >= 0) {
                if (anchor in pickSelected) pickSelected.remove(anchor) else pickSelected.add(anchor)
            }
            invalidate()
            return
        }
        if (!commitTap || wasMoved || tapX.isNaN()) {
            invalidate()
            return
        }
        handleBarTap(tapX, tapY)
    }

    private fun handleBarTap(x: Float, y: Float) {
        if (loneCodeRect.contains(x, y)) {
            listener?.onTapCode()
            return
        }
        if (firstCodeRect.contains(x, y)) {
            listener?.onTapCode()
            return
        }
        val ci = candidateRects.indexOfFirst { x in it.left..it.right }
        if (ci >= 0) listener?.onSelectCandidate(ci)
    }

    private fun pickIndexAt(x: Float): Int {
        val i = candidateRects.indexOfFirst { x in it.left..it.right }
        if (i < 0 || i >= pickIndexForRect.size) return -1
        return pickIndexForRect[i]
    }

    private fun beginExpandTouch(x: Float, y: Float) {
        listener?.onTouchKey()
        expandTracking = true
        expandMoved = false
        expandAxis = 0
        expandDownX = x
        expandDownY = y
        expandDownScrollX = expandScrollX
        expandDownScrollY = expandScrollY
        pendingExpandX = x
        pendingExpandY = y
    }

    private fun handleExpandMove(x: Float, y: Float) {
        val dx = x - expandDownX
        val dy = y - expandDownY
        if (!expandMoved && kotlin.math.hypot(dx.toDouble(), dy.toDouble()) < dp(6f)) return
        expandMoved = true
        if (expandAxis == 0) {
            expandAxis = if (maxExpandScrollX > 1f && kotlin.math.abs(dx) > kotlin.math.abs(dy)) 2 else 1
        }
        if (expandAxis == 2) {
            expandScrollX = (expandDownScrollX - dx).coerceIn(0f, maxExpandScrollX)
        } else {
            expandScrollY = (expandDownScrollY - dy).coerceIn(0f, maxExpandScrollY)
        }
        invalidate()
    }

    private fun endExpandTouch(x: Float, y: Float, commitTap: Boolean) {
        val moved = expandMoved
        val tapX = pendingExpandX
        val tapY = pendingExpandY
        expandTracking = false
        expandMoved = false
        expandAxis = 0
        if (!commitTap || moved) {
            invalidate()
            return
        }
        val contentX = tapX + expandScrollX
        val contentY = tapY - barH + expandScrollY
        val hit = expandRects.firstOrNull { it.first.contains(contentX, contentY) }
        if (hit != null) listener?.onSelectExpanded(hit.second)
        else {
            expandVisible = false
            invalidate()
        }
    }

    private fun beginSameTouch(y: Float) {
        sameTracking = true
        sameMoved = false
        sameDownY = y
        sameDownScroll = sameScrollY
    }

    private fun handleSameMove(y: Float) {
        val dy = y - sameDownY
        if (!sameMoved && kotlin.math.abs(dy) < dp(6f)) return
        sameMoved = true
        sameScrollY = (sameDownScroll - dy).coerceIn(0f, maxSameScroll)
        invalidate()
    }

    private fun handleRankDown(x: Float, y: Float) {
        listener?.onTouchKey()
        cancelRankHold()
        rankTracking = true
        rankScrolling = false
        rankDragFrom = -1
        rankDragTarget = -1
        rankStepRects.firstOrNull { it.first.contains(x, y) }?.let { (_, step) ->
            KeyboardPrefs.candidateRankStep = step
            rankStatus = "接下来拖动时，权重按 $step 调整。"
            rankTracking = false
            invalidate()
            return
        }
        if (rankDoneRect.contains(x, y)) {
            rankTracking = false
            listener?.onFinishRank()
            return
        }
        val contentY = y + rankScrollY
        val row = rankRowRects.indexOfFirst { contentY in it.top..it.bottom && x in it.left..it.right }
        rankPotential = row
        rankDownY = y
        rankLastY = y
        rankDownScroll = rankScrollY
        if (row !in rankRows.indices) return
        if (!rankRows[row].adjustable) {
            rankStatus = "「${rankRows[row].text}」不在词库里，不能改权重。"
            invalidate()
            return
        }
        rankHold = Runnable {
            if (!rankTracking || rankScrolling || rankPotential !in rankRows.indices) return@Runnable
            beginRankDrag(rankPotential, rankLastY)
        }.also { handler.postDelayed(it, 180) }
    }

    private fun beginRankDrag(row: Int, y: Float) {
        rankDragFrom = row
        rankDragTarget = row
        rankDragFingerY = y
        val rowTop = rankRowRects[row].top - rankScrollY
        rankDragOffsetY = y - rowTop
        invalidate()
    }

    private fun handleRankMove(y: Float) {
        rankLastY = y
        if (rankDragFrom >= 0) {
            rankDragFingerY = y
            val rowScreenTop = y - rankDragOffsetY
            val contentMid = rowScreenTop + rankScrollY + rankRowH / 2f
            val target = rankRowRects.indexOfFirst { contentMid in it.top..it.bottom }
                .takeIf { it >= 0 } ?: rankDragTarget
            rankDragTarget = target.coerceIn(0, rankRows.lastIndex.coerceAtLeast(0))
            invalidate()
            return
        }
        val dy = y - rankDownY
        if (!rankScrolling && kotlin.math.abs(dy) > dp(8f)) {
            cancelRankHold()
            if (maxRankScroll > 1f) {
                rankScrolling = true
            } else if (rankPotential in rankRows.indices && rankRows[rankPotential].adjustable) {
                beginRankDrag(rankPotential, y)
                return
            }
        }
        if (rankScrolling) {
            rankScrollY = (rankDownScroll - dy).coerceIn(0f, maxRankScroll)
            invalidate()
        }
    }

    private fun endRankTouch(cancelled: Boolean) {
        cancelRankHold()
        rankTracking = false
        rankScrolling = false
        if (rankDragFrom >= 0) endRankDrag(cancelled)
        else invalidate()
    }

    private fun cancelRankHold() {
        rankHold?.let { handler.removeCallbacks(it) }
        rankHold = null
    }

    private fun endRankDrag(cancelled: Boolean) {
        val from = rankDragFrom
        val to = rankDragTarget
        rankDragFrom = -1
        rankDragTarget = -1
        if (!cancelled && from >= 0 && to >= 0 && from != to) {
            listener?.onReorder(from, to)
        } else {
            invalidate()
        }
    }

    private fun fire(spec: KeySpec) {
        when (spec.action) {
            KeyAction.Shift -> toggleShift()
            KeyAction.SwitchLetters -> setPage(KeyboardPage.Letters)
            KeyAction.SwitchNumbers -> setPage(KeyboardPage.Numbers)
            KeyAction.SwitchSymbols -> setPage(KeyboardPage.Symbols)
            KeyAction.ToggleLanguage -> {
                if (pickingHanzi) endPick()
                if (addWord) endAddWord()
                toggleLanguage()
                listener?.onKey(spec)
            }
            KeyAction.Noop -> {}
            else -> listener?.onKey(spec)
        }
    }

    private fun armDelete() {
        deleteArmed = true
        holdRunnable = Runnable {
            listener?.onDeleteRepeatStart()
        }.also { handler.postDelayed(it, 200) }
    }
}
