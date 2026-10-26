package app.weft.keyboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * Long-press alternates ("e" → 3 è é ê ë ē …). Opened by a held key, which then
 * forwards its finger position here; releasing commits the highlighted option.
 * Positions are in the keyboard's root coordinates.
 */
@Stable
class AlternatesPopup {
    var options by mutableStateOf<List<String>>(emptyList())
        private set
    var selected by mutableIntStateOf(0)
        private set
    var anchor by mutableStateOf(Rect.Zero)
        private set
    var left by mutableFloatStateOf(0f)
        private set
    var cellWidth by mutableFloatStateOf(0f)
        private set

    /** Width of the keyboard, so the popup can be kept on screen. */
    var rootWidth = 0f

    var onPick: (String) -> Unit = {}

    val isOpen: Boolean get() = options.isNotEmpty()

    fun open(choices: List<String>, key: Rect) {
        if (choices.isEmpty()) return
        // Cells match the key, but shrink if that many wouldn't fit (wide number-pad keys).
        cellWidth = if (rootWidth > 0f) minOf(key.width, rootWidth / choices.size) else key.width
        anchor = key
        // First option sits over the key, shifted left if it would run off screen.
        val width = cellWidth * choices.size
        left = key.left.coerceAtMost(rootWidth - width).coerceAtLeast(0f)
        // Start on the option under the finger.
        selected = ((key.center.x - left) / cellWidth).toInt().coerceIn(0, choices.lastIndex)
        options = choices
    }

    /** Highlights the option under the finger ([x] in root coordinates). */
    fun track(x: Float) {
        if (!isOpen || cellWidth <= 0f) return
        selected = ((x - left) / cellWidth).toInt().coerceIn(0, options.lastIndex)
    }

    fun commit() {
        val choice = options.getOrNull(selected)
        dismiss()
        if (choice != null) onPick(choice)
    }

    fun dismiss() {
        options = emptyList()
    }
}

@Composable
fun AlternatesOverlay(popup: AlternatesPopup) {
    if (!popup.isOpen) return
    val density = LocalDensity.current
    val cs = MaterialTheme.colorScheme
    val cellWidth = with(density) { popup.cellWidth.toDp() }
    val cellHeight = with(density) { popup.anchor.height.toDp() }
    val lift = with(density) { 6.dp.toPx() }
    val top = (popup.anchor.top - popup.anchor.height - lift).coerceAtLeast(0f)

    Surface(
        modifier = Modifier.offset { IntOffset(popup.left.roundToInt(), top.roundToInt()) },
        shape = RoundedCornerShape(20.dp),
        color = cs.surfaceContainerHighest,
        shadowElevation = 6.dp,
        tonalElevation = 2.dp,
    ) {
        Row(Modifier.padding(2.dp)) {
            popup.options.forEachIndexed { i, option ->
                val chosen = i == popup.selected
                Box(
                    Modifier
                        .size(width = cellWidth - 1.dp, height = cellHeight - 4.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (chosen) cs.primary else Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        option,
                        fontSize = 23.sp,
                        fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (chosen) cs.onPrimary else cs.onSurface,
                    )
                }
            }
        }
    }
}
