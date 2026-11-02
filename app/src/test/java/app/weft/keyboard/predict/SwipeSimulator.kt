package app.weft.keyboard.predict

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Synthetic swipes for testing (and, later, training) the swipe decoder.
 * Coordinates are key units, like [SwipeDecoder].
 *
 * [straight] is the original benchmark: straight lines between slightly
 * off-centre key points. [natural] imitates real thumbs, after what gesture
 * keyboard studies report about human swipes:
 *  - the whole gesture is shifted (thumbs land low) and scaled a little;
 *  - each letter is aimed at with Gaussian error;
 *  - sharp turns get their corners cut, by more the sharper they are;
 *  - the path is a smooth curve through the letters, not a polyline;
 *  - the finger overshoots the last letter as it lifts;
 *  - it moves slowly near letters and fast between them (uneven sampling);
 *  - plus a little sensor jitter.
 */
class SwipeSimulator(private val centers: Map<Int, FloatArray>, private val rnd: Random) {

    data class Swipe(val xs: FloatArray, val ys: FloatArray)

    private fun keys(word: String): List<FloatArray> {
        val out = ArrayList<FloatArray>()
        var last = -1
        for (c in word) {
            val k = centers[c.code] ?: continue
            if (c.code == last) continue
            last = c.code
            out += k
        }
        return out
    }

    fun straight(word: String, wobble: Float): Swipe? {
        val corners = keys(word).map {
            floatArrayOf(it[0] + (rnd.nextFloat() * 2 - 1) * wobble, it[1] + (rnd.nextFloat() * 2 - 1) * wobble)
        }.toMutableList()
        if (corners.isEmpty()) return null
        if (corners.size == 1) corners += floatArrayOf(corners[0][0] + 0.05f, corners[0][1])
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        for (i in 0 until corners.size - 1) {
            for (s in 0 until 6) {
                val t = s / 6f
                xs += corners[i][0] + (corners[i + 1][0] - corners[i][0]) * t + (rnd.nextFloat() - 0.5f) * 0.08f
                ys += corners[i][1] + (corners[i + 1][1] - corners[i][1]) * t + (rnd.nextFloat() - 0.5f) * 0.08f
            }
        }
        xs += corners.last()[0]
        ys += corners.last()[1]
        return Swipe(xs.toFloatArray(), ys.toFloatArray())
    }

    /** [sloppiness] scales every error: 1 = a typical relaxed thumb, 1.5 = hurried. */
    fun natural(word: String, sloppiness: Float = 1f): Swipe? {
        val ks = keys(word)
        if (ks.size < 2) return null
        val n = sloppiness

        // Whole-gesture shift and scale around its centre.
        val cx = ks.map { it[0] }.average().toFloat()
        val cy = ks.map { it[1] }.average().toFloat()
        val shiftX = gauss() * 0.12f * n
        val shiftY = (0.08f + gauss() * 0.10f) * n
        val scale = 1f + gauss() * 0.06f * n
        val pts = ks.map {
            floatArrayOf(
                cx + (it[0] - cx) * scale + shiftX + clip(gauss()) * 0.20f * n,
                cy + (it[1] - cy) * scale + shiftY + clip(gauss()) * 0.20f * n,
            )
        }.toMutableList()

        // Cut corners at sharp turns: pull the point toward its neighbours' midpoint.
        val cut = pts.map { it.copyOf() }
        for (i in 1 until pts.size - 1) {
            val turn = turnAngle(pts[i - 1], pts[i], pts[i + 1]) / PI.toFloat() // 0 straight .. 1 reversal
            val mx = (pts[i - 1][0] + pts[i + 1][0]) / 2
            val my = (pts[i - 1][1] + pts[i + 1][1]) / 2
            val toMid = hypot(mx - pts[i][0], my - pts[i][1]).coerceAtLeast(1e-3f)
            // Thumbs round a corner but still head for the key: about a third of a key
            // at a sharp reversal, more when hurried, never most of the way to the midpoint.
            val amount = (turn * (0.2f + rnd.nextFloat() * 0.25f) * n).coerceAtMost(0.6f * n)
            val f = (amount / toMid).coerceAtMost(0.5f)
            cut[i][0] = pts[i][0] + (mx - pts[i][0]) * f
            cut[i][1] = pts[i][1] + (my - pts[i][1]) * f
        }

        // Overshoot past the last letter while lifting.
        val a = cut[cut.size - 2]
        val b = cut.last()
        val len = hypot(b[0] - a[0], b[1] - a[1]).coerceAtLeast(1e-3f)
        val over = rnd.nextFloat() * 0.3f * n
        val tail = floatArrayOf(b[0] + (b[0] - a[0]) / len * over, b[1] + (b[1] - a[1]) / len * over)

        // Smooth curve through the points, sampled densely near letters (slow) and
        // sparsely between them (fast).
        val control = cut + listOf(tail)
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        for (i in 0 until control.size - 1) {
            val p0 = control[maxOf(0, i - 1)]
            val p1 = control[i]
            val p2 = control[i + 1]
            val p3 = control[minOf(control.size - 1, i + 2)]
            val steps = 8
            for (s in 0 until steps) {
                val u = s / steps.toFloat()
                val t = (1 - cos(u * PI.toFloat())) / 2 // ease in and out
                xs += catmull(p0[0], p1[0], p2[0], p3[0], t) + gauss() * 0.025f
                ys += catmull(p0[1], p1[1], p2[1], p3[1], t) + gauss() * 0.025f
            }
        }
        xs += tail[0]
        ys += tail[1]
        return Swipe(xs.toFloatArray(), ys.toFloatArray())
    }

    private fun turnAngle(a: FloatArray, b: FloatArray, c: FloatArray): Float {
        val ux = b[0] - a[0]
        val uy = b[1] - a[1]
        val vx = c[0] - b[0]
        val vy = c[1] - b[1]
        val d = hypot(ux, uy) * hypot(vx, vy)
        if (d < 1e-6f) return 0f
        return acos(((ux * vx + uy * vy) / d).coerceIn(-1f, 1f))
    }

    private fun catmull(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
        val t2 = t * t
        val t3 = t2 * t
        return 0.5f * (2 * p1 + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3)
    }

    /** Standard normal (Box–Muller). */
    private fun gauss(): Float {
        val u = rnd.nextDouble().coerceAtLeast(1e-12)
        val v = rnd.nextDouble()
        return (sqrt(-2 * ln(u)) * cos(2 * PI * v)).toFloat()
    }

    private fun clip(g: Float) = if (abs(g) > 2.5f) 2.5f * kotlin.math.sign(g) else g
}
