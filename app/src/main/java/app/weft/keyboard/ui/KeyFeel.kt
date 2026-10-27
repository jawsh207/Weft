package app.weft.keyboard.ui

import android.content.Context
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** How strongly keys buzz. Stored in settings by [key]. */
enum class HapticLevel(val key: String) {
    Off("off"),
    Light("light"),

    /** The system's own keyboard tap, as other keyboards feel. */
    Normal("normal"),
    Strong("strong"),
    ;

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: Normal
    }
}

/** Key row height. Stored in settings by [key]. */
enum class KeyHeight(val key: String, val row: Dp) {
    Compact("compact", 50.dp),
    Normal("normal", 58.dp),
    Tall("tall", 66.dp),
    ;

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: Normal
    }
}

/**
 * Key haptics at the user's chosen strength. Normal uses the system's keyboard
 * feedback; Light and Strong use the vibrator's predefined tick and click,
 * still scaled by the phone's touch-vibration setting.
 */
class KeyHaptics(context: Context, val level: HapticLevel) {
    private val vibrator: Vibrator? =
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator?.takeIf { it.hasVibrator() }
    private val touch = VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH)

    /** A key press. */
    fun tap(view: View) {
        when (level) {
            HapticLevel.Off -> Unit
            HapticLevel.Light -> vibrate(VibrationEffect.EFFECT_TICK, view, HapticFeedbackConstants.CLOCK_TICK)
            HapticLevel.Normal -> view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            HapticLevel.Strong -> vibrate(VibrationEffect.EFFECT_HEAVY_CLICK, view, HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    /** Small steps: cursor moves, a swipe starting. */
    fun tick(view: View) {
        if (level != HapticLevel.Off) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    /** A popup opening on long-press. */
    fun longPress(view: View) {
        if (level != HapticLevel.Off) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun vibrate(effect: Int, view: View, fallback: Int) {
        val v = vibrator
        if (v == null) {
            view.performHapticFeedback(fallback)
        } else {
            v.vibrate(VibrationEffect.createPredefined(effect), touch)
        }
    }
}

internal val LocalKeyHaptics = staticCompositionLocalOf<KeyHaptics?> { null }

internal val LocalRowHeight = compositionLocalOf { KeyHeight.Normal.row }

/** Height of one key row, from settings. */
internal val RowHeight: Dp
    @Composable @ReadOnlyComposable
    get() = LocalRowHeight.current
