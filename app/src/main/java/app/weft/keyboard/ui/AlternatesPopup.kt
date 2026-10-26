package app.weft.keyboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
 *
 * Options wrap into rows rather than running off screen. The first options sit
 * in the bottom row, nearest the finger; later rows stack above. Positions are
 * in the keyboard's root coordinates.
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
    var top by mutableFloatStateOf(0f)
        private set
    var cellWidth by mutableFloatStateOf(0f)
        private set
    var cellHeight by mutableFloatStateOf(0f)
        private set
    var columns by mutableIntStateOf(1)
        private set

    /** Width of the keyboard, so the popup can be kept on screen. */
    var rootWidth = 0f

    /** Gap between the popup and the key it came from, in px. */
    var lift = 0f

    var onPick: (String) -> Unit = {}

    val isOpen: Boolean get() = options.isNotEmpty()
    val rows: Int get() = (options.size + columns - 1) / columns

    private var pickOverride: ((String) -> Unit)? = null

    fun open(choices: List<String>, key: Rect, onPickThis: ((String) -> Unit)? = null) {
        if (choices.isEmpty()) return
        pickOverride = onPickThis
        val screen = if (rootWidth > 0f) rootWidth else key.width * choices.size
        // Cells match the key, but very wide keys (number pad) get normal-sized cells.
        cellWidth = minOf(key.width, screen / STANDARD_KEYS_PER_ROW * MAX_CELL_SCALE)
        cellHeight = key.height
        anchor = key
        columns = (screen / cellWidth).toInt().coerceIn(1, choices.size)
        val rowCount = (choices.size + columns - 1) / columns
        // First option sits over the key, shifted left if it would run off screen.
        val width = cellWidth * columns
        left = key.left.coerceAtMost(screen - width).coerceAtLeast(0f)
        top = (key.top - lift - cellHeight * rowCount).coerceAtLeast(0f)
        options = choices
        // Start on the option under the finger, in the bottom row.
        selected = ((key.center.x - left) / cellWidth).toInt().coerceIn(0, minOf(columns, choices.size) - 1)
    }

    /** Highlights the option under the finger (root coordinates). */
    fun track(x: Float, y: Float) {
        if (!isOpen || cellWidth <= 0f || cellHeight <= 0f) return
        val column = ((x - left) / cellWidth).toInt().coerceIn(0, columns - 1)
        val bottom = top + cellHeight * rows
        // Row 0 is the bottom row; a finger below the popup stays on it.
        val rowFromBottom = ((bottom - y) / cellHeight).toInt().coerceIn(0, rows - 1)
        selected = (rowFromBottom * columns + column).coerceAtMost(options.lastIndex)
    }

    fun commit() {
        val choice = options.getOrNull(selected)
        val pick = pickOverride ?: onPick
        dismiss()
        if (choice != null) pick(choice)
    }

    fun dismiss() {
        options = emptyList()
    }

    private companion object {
        const val STANDARD_KEYS_PER_ROW = 10f
        const val MAX_CELL_SCALE = 1.25f
    }
}

@Composable
fun AlternatesOverlay(popup: AlternatesPopup) {
    val density = LocalDensity.current
    popup.lift = with(density) { 6.dp.toPx() }
    if (!popup.isOpen) return
    val cs = MaterialTheme.colorScheme
    val cellWidth = with(density) { popup.cellWidth.toDp() }
    val cellHeight = with(density) { popup.cellHeight.toDp() }

    Surface(
        modifier = Modifier.offset { IntOffset(popup.left.roundToInt(), popup.top.roundToInt()) },
        shape = RoundedCornerShape(20.dp),
        color = cs.surfaceContainerHighest,
        shadowElevation = 6.dp,
        tonalElevation = 2.dp,
    ) {
        Column {
            // Drawn top to bottom, so the last row of options comes first.
            for (row in popup.rows - 1 downTo 0) {
                Row {
                    val first = row * popup.columns
                    val count = minOf(popup.columns, popup.options.size - first)
                    for (column in 0 until count) {
                        val i = first + column
                        val option = popup.options[i]
                        val chosen = i == popup.selected
                        Box(
                            Modifier
                                .size(width = cellWidth, height = cellHeight)
                                .padding(2.dp)
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
    }
}
