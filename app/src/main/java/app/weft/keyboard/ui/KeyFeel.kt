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
        /** The level saved as [key]; Normal if nothing (or something unknown) is saved. */
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: Normal
    }
}

/**
 * Key row height (Settings › Keys). Stored in settings by [key]; Normal is
 * the default.
 *
 * Up to v1.0.0 there were three heights, with names one step larger than
 * today's for the same size: "compact" was 50 dp, "normal" 58 dp and "tall"
 * 66 dp. [of] reads a choice saved back then as the same size under its new
 * name, so nobody's keyboard changes size because of the renaming. Someone
 * who never chose gets today's Normal, which is one step taller than before.
 */
enum class KeyHeight(val key: String, val row: Dp) {
    Mini("mini", 50.dp),
    Compact("compact", 58.dp),
    Normal("normal", 66.dp),
    Tall("tall", 74.dp),
    ;

    companion object {
        /**
         * The height saved as [key]. If nothing is saved under today's names,
         * [legacyKey] (a choice saved by v1.0.0 or earlier) is read as the same
         * size; with neither, Normal.
         */
        fun of(key: String?, legacyKey: String? = null): KeyHeight =
            entries.firstOrNull { it.key == key } ?: when (legacyKey) {
                "compact" -> Mini
                "normal" -> Compact
                "tall" -> Normal
                else -> Normal
            }
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

    /**
     * Plays one of the vibrator's built-in effects, or the view's own feedback on a phone without
     * a vibrator.
     */
    private fun vibrate(effect: Int, view: View, fallback: Int) {
        val v = vibrator
        if (v == null) {
            view.performHapticFeedback(fallback)
        } else {
            v.vibrate(VibrationEffect.createPredefined(effect), touch)
        }
    }
}

/** The haptics for the keys below this point in the UI; null in previews. */
internal val LocalKeyHaptics = staticCompositionLocalOf<KeyHaptics?> { null }

/** The key row height for the keys below this point in the UI. */
internal val LocalRowHeight = compositionLocalOf { KeyHeight.Normal.row }

/** Height of one key row, from settings. */
internal val RowHeight: Dp
    @Composable @ReadOnlyComposable
    get() = LocalRowHeight.current
