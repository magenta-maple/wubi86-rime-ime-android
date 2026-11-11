package cn.kylebing.meifeng.wubi.data

import android.graphics.Color

/** 键盘偏好，与 iOS KeyboardPrefs 同一套键名和默认值 */
object KeyboardPrefs {
    const val DEFAULT_ACCENT_HEX = "F95353"
    const val HAPTIC_MAX = 4
    const val KEY_PRESS_HOLD_MAX_MS = 400
    const val KEY_PRESS_HOLD_DEFAULT_MS = 80
    const val DELETE_REPEAT_MAX = 4
    const val DELETE_REPEAT_DEFAULT = 3

    val accentPresets = listOf(
        "F95353", "FF3B30", "FF9500", "FFCC00", "34C759",
        "00C7BE", "007AFF", "5856D6", "AF52DE", "FF2D55"
    )

    private val p get() = AppPaths.prefs

    var hapticEnabled: Boolean
        get() = if (!p.contains("keyboard.haptic.enabled")) true else p.getBoolean("keyboard.haptic.enabled", true)
        set(v) { p.edit().putBoolean("keyboard.haptic.enabled", v).apply() }

    var hapticIntensityLevel: Int
        get() {
            if (p.contains("keyboard.haptic.intensity.level")) {
                return p.getInt("keyboard.haptic.intensity.level", 1).coerceIn(0, HAPTIC_MAX)
            }
            return 1
        }
        set(v) { p.edit().putInt("keyboard.haptic.intensity.level", v.coerceIn(0, HAPTIC_MAX)).apply() }

    fun hapticIntensity(level: Int = hapticIntensityLevel): Float = when (level.coerceIn(0, HAPTIC_MAX)) {
        0 -> 0.28f
        1 -> 0.45f
        3 -> 0.88f
        4 -> 1.0f
        else -> 0.68f
    }

    fun hapticTitle(level: Int = hapticIntensityLevel): String = when (level.coerceIn(0, HAPTIC_MAX)) {
        0 -> "极轻"
        1 -> "轻"
        3 -> "重"
        4 -> "极重"
        else -> "中"
    }

    var closesSymbolPageAfterInput: Boolean
        get() = if (!p.contains("keyboard.symbolPage.dismissAfterInput")) false
        else p.getBoolean("keyboard.symbolPage.dismissAfterInput", false)
        // commit：设置页与键盘同进程也立刻落盘，避免切换输入框前读到旧值
        set(v) { p.edit().putBoolean("keyboard.symbolPage.dismissAfterInput", v).commit() }

    var candidateRankStep: Int
        get() = if (p.getInt("keyboard.candidateRank.step", 5) == 10) 10 else 5
        set(v) { p.edit().putInt("keyboard.candidateRank.step", if (v == 10) 10 else 5).commit() }

    // 四码唯一：打满四码且只有一个候选时自动上屏。默认开
    var autoSelectUniqueFourCode: Boolean
        get() = p.getBoolean("keyboard.fourCode.autoSelectUnique", true)
        set(v) { p.edit().putBoolean("keyboard.fourCode.autoSelectUnique", v).commit() }

    var clearEmptyCode: Boolean
        get() = p.getBoolean("keyboard.emptyCode.clear", false)
        set(v) { p.edit().putBoolean("keyboard.emptyCode.clear", v).commit() }

    var showCommentInPanel: Boolean
        get() = if (!p.contains("keyboard.candidate.showComment")) true
        else p.getBoolean("keyboard.candidate.showComment", true)
        set(v) { p.edit().putBoolean("keyboard.candidate.showComment", v).commit() }

    var centerCandidatesOnWideScreen: Boolean
        get() = p.getBoolean("keyboard.candidate.centerOnPad", false)
        set(v) { p.edit().putBoolean("keyboard.candidate.centerOnPad", v).commit() }

    var enterClearsCode: Boolean
        get() = p.getBoolean("keyboard.enter.clearsCode", false)
        set(v) { p.edit().putBoolean("keyboard.enter.clearsCode", v).commit() }

    var keyPressHoldMillis: Int
        get() = if (!p.contains("keyboard.keyPress.holdMillis")) KEY_PRESS_HOLD_DEFAULT_MS
        else p.getInt("keyboard.keyPress.holdMillis", KEY_PRESS_HOLD_DEFAULT_MS).coerceIn(0, KEY_PRESS_HOLD_MAX_MS)
        set(v) { p.edit().putInt("keyboard.keyPress.holdMillis", v.coerceIn(0, KEY_PRESS_HOLD_MAX_MS)).apply() }

    var deleteRepeatLevel: Int
        get() = if (!p.contains("keyboard.delete.repeatLevel")) DELETE_REPEAT_DEFAULT
        else p.getInt("keyboard.delete.repeatLevel", DELETE_REPEAT_DEFAULT).coerceIn(0, DELETE_REPEAT_MAX)
        set(v) { p.edit().putInt("keyboard.delete.repeatLevel", v.coerceIn(0, DELETE_REPEAT_MAX)).apply() }

    private val deleteIntervalsMs = intArrayOf(150, 115, 85, 60, 40)

    fun deleteRepeatIntervalMs(level: Int = deleteRepeatLevel): Long =
        deleteIntervalsMs[level.coerceIn(0, DELETE_REPEAT_MAX)].toLong()

    fun deleteRepeatTitle(level: Int = deleteRepeatLevel): String = when (level.coerceIn(0, DELETE_REPEAT_MAX)) {
        0 -> "慢"
        1 -> "稍慢"
        3 -> "快"
        4 -> "极快"
        else -> "默认"
    }

    var hdrKeyPressEnabled: Boolean
        get() = p.getBoolean("keyboard.keyPress.hdr", false)
        set(v) { p.edit().putBoolean("keyboard.keyPress.hdr", v).apply() }

    var accentColorHex: String
        get() = normalizeHex(p.getString("keyboard.accent.hex", DEFAULT_ACCENT_HEX)) ?: DEFAULT_ACCENT_HEX
        set(v) { p.edit().putString("keyboard.accent.hex", normalizeHex(v) ?: DEFAULT_ACCENT_HEX).apply() }

    val accentColor: Int get() = hexToColor(accentColorHex)

    fun hexToColor(hex: String): Int {
        val n = normalizeHex(hex) ?: DEFAULT_ACCENT_HEX
        return Color.parseColor("#$n")
    }

    fun normalizeHex(raw: String?): String? {
        var s = raw?.trim()?.uppercase()?.removePrefix("#") ?: return null
        if (s.length != 6 || !s.all { it in '0'..'9' || it in 'A'..'F' }) return null
        return s
    }
}
