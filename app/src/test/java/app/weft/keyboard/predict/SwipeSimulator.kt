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

    /** [times] (ms, one per point) is only set by [timed]. */
    data class Swipe(val xs: FloatArray, val ys: FloatArray, val times: LongArray? = null)

    /**
     * How the simulated finger speeds up and slows down in [timed]. A dip of 1 is
     * a full stop at a letter, 0 is passing it at cruising speed.
     */
    data class Pace(
        /** Typical dip at a letter that lies on a straight stretch (mean, spread). */
        val straightDip: Float = 0.35f,
        val straightSpread: Float = 0.2f,
        /** Extra dip per unit of turn (0 = straight on, 1 = full reversal). */
        val cornerDip: Float = 1.4f,
        /** Chance, per long stretch between letters, of a slowdown that means nothing. */
        val hesitation: Float = 0.1f,
        /** Time between touch samples, ms. */
        val sampleMs: Float = 12f,
    )

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
        val control = control(word, sloppiness) ?: return null
        val tail = control.last()
        // Sampled densely near letters (slow) and sparsely between them (fast).
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

    /**
     * The same kind of path as [natural], with a timestamp per point, sampled at
     * a steady rate like a touch screen. The finger starts from rest, slows at
     * each letter (a lot at sharp turns, a little or not at all on straight
     * stretches, less when hurried), sometimes hesitates for no reason, takes
     * longer over longer stretches, and lifts while still moving past the last
     * letter. How much real thumbs slow on straight stretches isn't well known:
     * [pace] sets it, and the tests try both generous and stingy values.
     */
    fun timed(word: String, sloppiness: Float = 1f, pace: Pace = Pace()): Swipe? {
        val control = control(word, sloppiness) ?: return null
        val letters = control.size - 1 // the last control point is the overshoot
        val n = sloppiness
        // How much the finger slows at each control point.
        val dip = FloatArray(control.size)
        dip[0] = 1f
        for (i in 1 until letters) {
            val turn = if (i < letters - 1) turnAngle(control[i - 1], control[i], control[i + 1]) / PI.toFloat() else 0.35f
            val straight = (pace.straightDip + gauss() * pace.straightSpread).coerceAtLeast(0f) / n
            dip[i] = (straight + turn * pace.cornerDip).coerceIn(0f, 1f)
        }
        dip[letters] = 0.4f
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        val ts = ArrayList<Long>()
        var clock = 0f // time at the start of the current stretch
        var next = 0f // time of the next sample
        for (i in 0 until control.size - 1) {
            val p0 = control[maxOf(0, i - 1)]
            val p1 = control[i]
            val p2 = control[i + 1]
            val p3 = control[minOf(control.size - 1, i + 2)]
            val length = hypot(p2[0] - p1[0], p2[1] - p1[1])
            val duration = (60f + 110f * (ln(1f + length) / ln(2f))) * kotlin.math.exp(gauss() * 0.2f) / n
            val hesitateAt = if (length > 1.5f && rnd.nextFloat() < pace.hesitation) 0.3f + rnd.nextFloat() * 0.4f else -1f
            val hesitateBy = 0.3f + rnd.nextFloat() * 0.4f
            // Speed along the stretch, then how far along the finger is at each moment.
            val fine = 200
            val progress = FloatArray(fine + 1)
            for (k in 1..fine) {
                val tau = (k - 0.5f) / fine
                val c = cos(tau * PI.toFloat() / 2)
                var v = 1f - dip[i] * c * c - dip[i + 1] * (1 - c * c)
                if (hesitateAt >= 0f) {
                    val z = (tau - hesitateAt) / 0.12f
                    v *= 1f - hesitateBy * kotlin.math.exp(-z * z)
                }
                progress[k] = progress[k - 1] + v.coerceAtLeast(0.03f)
            }
            while (next < clock + duration) {
                val f = (next - clock) / duration * fine
                val k = f.toInt().coerceIn(0, fine - 1)
                val u = (progress[k] + (progress[k + 1] - progress[k]) * (f - k)) / progress[fine]
                xs += catmull(p0[0], p1[0], p2[0], p3[0], u) + gauss() * 0.025f
                ys += catmull(p0[1], p1[1], p2[1], p3[1], u) + gauss() * 0.025f
                ts += next.toLong()
                next += pace.sampleMs + (rnd.nextFloat() - 0.5f) * 4f
            }
            clock += duration
        }
        xs += control.last()[0]
        ys += control.last()[1]
        ts += maxOf(clock.toLong(), ts.last() + 1)
        return Swipe(xs.toFloatArray(), ys.toFloatArray(), ts.toLongArray())
    }

    /** Where the finger actually goes: a point per letter (after aim error and corner cutting), plus the overshoot. */
    private fun control(word: String, sloppiness: Float): List<FloatArray>? {
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

        return cut + listOf(tail)
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
