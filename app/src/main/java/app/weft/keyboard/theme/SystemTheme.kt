package app.weft.keyboard.theme

import android.content.res.Configuration
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/**
 * Material 3 Expressive theme whose colors come entirely from the system.
 *
 * - Colors: the user's Material You palette (wallpaper or chosen theme colors).
 *   On Android 14+ dynamic schemes read the system's role-based color resources,
 *   so the system "contrast" setting is honored automatically.
 * - Dark/light: follows the system dark-theme setting.
 * - Typography: Material defaults on FontFamily.Default, i.e. the device font,
 *   scaled by the user's font-size setting.
 *
 * [epoch] is bumped by the IME service whenever the configuration or input view
 * changes, forcing the palette to be re-read. Wallpaper/theme changes arrive as
 * configuration changes, so the keyboard repaints without a restart.
 */
@Composable
fun SystemTheme(
    epoch: Int = 0,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val dark = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

    val colorScheme = remember(epoch, dark, configuration) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
