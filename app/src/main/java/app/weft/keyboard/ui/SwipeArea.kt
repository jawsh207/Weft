package app.weft.keyboard.ui

import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import app.weft.keyboard.ime.KeyboardState
import app.weft.keyboard.layout.CharKey
import app.weft.keyboard.layout.KeyRow
import kotlinx.coroutines.launch
import kotlin.math.hypot

/** Must match KeyGeometry's default row aspect, so swipes and key centers line up. */
private const val ROW_ASPECT = 1.45f

/** Finger travel (key widths) before a touch counts as a swipe rather than a tap. */
private const val SWIPE_START = 0.45f

/** How often the live preview re-decodes while swiping. */
private const val PREVIEW_MS = 70L

/**
 * The letter rows with swipe typing on top. Taps still go to the keys; once a
 * finger starting on a letter moves far enough, the swipe takes over (the keys
 * see their touch cancelled), a trail follows the finger, and the decoder's
 * best guess previews in the suggestion strip.
 */
@Composable
internal fun SwipeArea(rows: List<KeyRow>, state: KeyboardState, handlers: KeyHandlers) {
    val view = LocalView.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val trail = remember { mutableStateListOf<Offset>() }
    val fade = remember { Animatable(1f) }
    val trailColor = MaterialTheme.colorScheme.primary
    val trailWidth = with(density) { 6.dp.toPx() }
    val rowHeight = with(density) { RowHeight.toPx() }

    Box(
        Modifier.pointerInput(rows) {
            awaitEachGesture {
                // Initial pass: see the touch before the keys do, so a swipe can take it over.
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val keyWidth = size.width / 10f
                if (!startsOnLetter(rows, down.position, keyWidth, rowHeight) || handlers.popup.isOpen) {
                    return@awaitEachGesture
                }
                val xs = ArrayList<Float>()
                val ys = ArrayList<Float>()
                fun add(p: Offset) {
                    xs += p.x / keyWidth
                    ys += (p.y / rowHeight - 0.5f) * ROW_ASPECT
                }
                add(down.position)
                var swiping = false
                var lastPreview = 0L
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes
                        .firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        if (swiping) {
                            add(change.position)
                            handlers.onSwipe(xs.toFloatArray(), ys.toFloatArray(), true)
                            scope.launch {
                                fade.animateTo(0f)
                                trail.clear()
                            }
                        }
                        break
                    }
                    val p = change.position
                    if (!swiping) {
                        val moved = hypot(p.x - down.position.x, p.y - down.position.y) / keyWidth
                        if (moved < SWIPE_START || handlers.popup.isOpen) continue
                        swiping = true
                        scope.launch { fade.snapTo(1f) }
                        trail.clear()
                        trail += down.position
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                    change.consume() // the key under the finger won't type
                    add(p)
                    trail += p
                    val now = SystemClock.uptimeMillis()
                    if (now - lastPreview >= PREVIEW_MS) {
                        lastPreview = now
                        handlers.onSwipe(xs.toFloatArray(), ys.toFloatArray(), false)
                    }
                }
            }
        },
    ) {
        Column { rows.forEach { row -> KeyRowView(row, state, handlers) } }
        Canvas(Modifier.matchParentSize()) {
            if (trail.size < 2) return@Canvas
            val path = Path().apply {
                moveTo(trail[0].x, trail[0].y)
                for (i in 1 until trail.size) lineTo(trail[i].x, trail[i].y)
            }
            drawPath(
                path,
                color = trailColor.copy(alpha = 0.85f * fade.value),
                style = Stroke(width = trailWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }
}

/** True if [p] is on a letter key (not shift, delete or the bottom row). */
private fun startsOnLetter(rows: List<KeyRow>, p: Offset, keyWidth: Float, rowHeight: Float): Boolean {
    val r = (p.y / rowHeight).toInt()
    if (r !in 0..2 || r >= rows.size) return false
    var x = 0f
    for (key in rows[r]) {
        val w = key.weight * keyWidth
        if (p.x < x + w) return key is CharKey && key.output.length == 1 && key.output[0].isLetter()
        x += w
    }
    return false
}
