package app.weft.keyboard.ui

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
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

/** The trail shows only the most recent stretch of the swipe, tapering toward its tail. */
private const val TRAIL_POINTS = 48

/** How long a letter stays lit after the path crosses it. */
private const val GLOW_MS = 320L

/** A letter key the path crossed, and when. */
private class Glow(val rect: Rect, var at: Long)

/**
 * The letter rows with swipe typing on top. Taps still go to the keys; once a
 * finger starting on a letter moves far enough, the swipe takes over (the keys
 * see their touch cancelled) and the decoder's best guess previews in the
 * suggestion strip's middle word.
 *
 * With swipe visuals on (the default; see settings), the trail tapers and fades
 * toward its tail and letters light up briefly as the path crosses them. With
 * them off, a plain trail follows the finger, as before.
 */
@Composable
internal fun SwipeArea(rows: List<KeyRow>, state: KeyboardState, handlers: KeyHandlers) {
    val view = LocalView.current
    val haptics = LocalKeyHaptics.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val trail = remember { mutableStateListOf<Offset>() }
    val glows = remember { mutableStateListOf<Glow>() }
    val fade = remember { Animatable(1f) }
    var swiping by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(0L) }
    val cs = MaterialTheme.colorScheme
    val trailColor = cs.primary
    val glowColor = cs.primary
    val trailWidth = with(density) { 7.dp.toPx() }
    val plainWidth = with(density) { 6.dp.toPx() }
    val rowHeight = with(density) { RowHeight.toPx() }
    val gap = with(density) { KeyGap.toPx() }
    val corner = with(density) { 12.dp.toPx() }
    val visuals = state.swipeVisuals

    // Drive the fading while anything is lit, even if the finger holds still.
    LaunchedEffect(swiping, glows.size) {
        while (swiping || glows.isNotEmpty()) {
            withFrameMillis { }
            now = SystemClock.uptimeMillis()
            glows.removeAll { now - it.at > GLOW_MS }
        }
    }

    Box(
        Modifier.pointerInput(rows) {
            awaitEachGesture {
                // Initial pass: see the touch before the keys do, so a swipe can take it over.
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val keyWidth = size.width / 10f
                if (letterKeyAt(rows, down.position, keyWidth, rowHeight) == null || handlers.popup.isOpen) {
                    return@awaitEachGesture
                }
                val xs = ArrayList<Float>()
                val ys = ArrayList<Float>()
                val times = ArrayList<Long>() // when each point was touched: the decoder reads the rhythm
                fun add(p: Offset, at: Long) {
                    xs += p.x / keyWidth
                    ys += (p.y / rowHeight - 0.5f) * ROW_ASPECT
                    times += maxOf(at, times.lastOrNull() ?: at)
                }
                fun glowAt(p: Offset) {
                    val rect = letterKeyAt(rows, p, keyWidth, rowHeight)?.deflate(gap) ?: return
                    val t = SystemClock.uptimeMillis()
                    val existing = glows.lastOrNull()
                    if (existing != null && existing.rect == rect) existing.at = t else glows += Glow(rect, t)
                }
                add(down.position, down.uptimeMillis)
                var started = false
                var lastPreview = 0L
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes
                        .firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        if (started) {
                            add(change.position, change.uptimeMillis)
                            handlers.onSwipe(xs.toFloatArray(), ys.toFloatArray(), times.toLongArray(), true)
                            swiping = false
                            scope.launch {
                                fade.animateTo(0f)
                                trail.clear()
                            }
                        }
                        break
                    }
                    val p = change.position
                    if (!started) {
                        val moved = hypot(p.x - down.position.x, p.y - down.position.y) / keyWidth
                        if (moved < SWIPE_START || handlers.popup.isOpen) continue
                        started = true
                        swiping = true
                        scope.launch { fade.snapTo(1f) }
                        trail.clear()
                        trail += down.position
                        glowAt(down.position)
                        haptics?.tick(view)
                    }
                    change.consume() // the key under the finger won't type
                    // The screen samples faster than frames arrive: keep the points in between too.
                    for (h in change.historical) add(h.position, h.uptimeMillis)
                    add(p, change.uptimeMillis)
                    trail += p
                    glowAt(p)
                    val t = SystemClock.uptimeMillis()
                    if (t - lastPreview >= PREVIEW_MS) {
                        lastPreview = t
                        handlers.onSwipe(xs.toFloatArray(), ys.toFloatArray(), times.toLongArray(), false)
                    }
                }
            }
        },
    ) {
        Column { rows.forEach { row -> KeyRowView(row, state, handlers) } }
        if (!visuals) {
            // Plain trail: the whole path, fading out when the finger lifts.
            Canvas(Modifier.matchParentSize()) {
                if (trail.size < 2) return@Canvas
                val path = Path().apply {
                    moveTo(trail[0].x, trail[0].y)
                    for (i in 1 until trail.size) lineTo(trail[i].x, trail[i].y)
                }
                drawPath(
                    path,
                    color = trailColor.copy(alpha = 0.85f * fade.value),
                    style = Stroke(width = plainWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        } else {
            Canvas(Modifier.matchParentSize()) {
                // Letters the path crossed, fading out.
                for (g in glows) {
                    val a = (1f - (now - g.at).toFloat() / GLOW_MS).coerceIn(0f, 1f)
                    if (a <= 0f) continue
                    drawRoundRect(
                        color = glowColor.copy(alpha = 0.28f * a),
                        topLeft = g.rect.topLeft,
                        size = Size(g.rect.width, g.rect.height),
                        cornerRadius = CornerRadius(corner, corner),
                    )
                }
                // The trail: the latest stretch only, thin and faint at the tail, full at the finger.
                val from = (trail.size - TRAIL_POINTS).coerceAtLeast(0)
                val n = trail.size - from
                if (n >= 2) {
                    for (i in 1 until n) {
                        val t = i / (n - 1).toFloat()
                        drawLine(
                            color = trailColor.copy(alpha = (0.12f + 0.78f * t) * fade.value),
                            start = trail[from + i - 1],
                            end = trail[from + i],
                            strokeWidth = trailWidth * (0.3f + 0.7f * t),
                            cap = StrokeCap.Round,
                        )
                    }
                }
            }
        }
    }
}

/** The bounds of the letter key at [p] (not shift, delete or the bottom row), or null. */
private fun letterKeyAt(rows: List<KeyRow>, p: Offset, keyWidth: Float, rowHeight: Float): Rect? {
    if (p.y < 0) return null
    val r = (p.y / rowHeight).toInt()
    if (r !in 0..2 || r >= rows.size) return null
    var x = 0f
    for (key in rows[r]) {
        val w = key.weight * keyWidth
        if (p.x < x + w) {
            val isLetter = key is CharKey && key.output.length == 1 && key.output[0].isLetter()
            return if (isLetter) Rect(x, r * rowHeight, x + w, (r + 1) * rowHeight) else null
        }
        x += w
    }
    return null
}
