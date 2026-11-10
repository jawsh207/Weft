package app.weft.keyboard.predict

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Turns a swipe across the letter keys into likely words.
 *
 * This is the classic shape-matching approach (often called SHARK²):
 *  1. Find candidates: walk the dictionary trie, keeping only words whose
 *     letters the finger passed near, in order, starting and ending near the
 *     first and last letters. This cuts 150k words to a few dozen.
 *  2. Score each candidate by comparing the swipe with the word's ideal path
 *     through its key centers, both by position and by overall shape.
 *  3. Rank by that match, word frequency and the previous word.
 *
 * It's deliberately self-contained so a learned model can replace [decode]
 * later without touching the keyboard (see docs/swipe-typing.md).
 *
 * Coordinates are in key units, matching [app.weft.keyboard.layout.KeyGeometry]:
 * x in key widths from the left edge, y in the same units with row centers at
 * 0, rowAspect, 2·rowAspect.
 */
class SwipeDecoder(
    private val dictionary: BinaryDictionary?,
    private val centers: Map<Int, FloatArray>,
    private val learned: LearnedWords,
    private val tuning: Tuning = Tuning(),
    private val proximity: KeyProximity = KeyProximity(centers),
) {

    /** Scoring weights; defaults were tuned on synthetic swipes (see SwipeDecoderTest). */
    data class Tuning(
        /** How much the swipe's distance from a word's ideal path counts against it. */
        val geometry: Float = 6f,
        /** Extra weight on where the swipe starts and ends vs the first and last letters. */
        val endpoints: Float = 2f,
        /** Weight of the normalized-shape term within the geometry distance. */
        val shape: Float = 0.6f,
        /** How close (key widths) the swipe must start / end to a word's first / last letter. */
        val startRadius: Float = START_RADIUS,
        val endRadius: Float = END_RADIUS,
        /** How close it must pass each letter in between. */
        val passRadius: Float = PASS_RADIUS,
        /** Small handicap for words the dictionary only has capitalized (names, places). */
        val namePenalty: Float = NAME_PENALTY,
        /** Cost of a slowdown in the swipe that none of a word's letters explains. */
        val unexplainedDip: Float = UNEXPLAINED_DIP,
        /** Cost of a middle letter the finger passed without slowing, when it slowed for others. */
        val unsupportedLetter: Float = UNSUPPORTED_LETTER,
        /** How near (key widths along the path) a slowdown must be to a letter to belong to it. */
        val dipReach: Float = DIP_REACH,
        /** Slowdowns shallower than this are ignored. */
        val minDip: Float = MIN_DIP,
    )

    data class Candidate(val word: String, val score: Float, val geometry: Float)

    /** Rough path length (key widths) below which a gesture is treated as a tap. */
    fun pathLength(xs: FloatArray, ys: FloatArray): Float {
        var len = 0f
        for (i in 1 until xs.size) len += dist(xs[i - 1], ys[i - 1], xs[i], ys[i])
        return len
    }

    /**
     * [previous] is the word before this one, [before] the one before that.
     * [times] (ms, one per point) is optional: with it, where the finger slowed
     * down helps choose between words whose paths look alike.
     */
    fun decode(
        rawXs: FloatArray,
        rawYs: FloatArray,
        previous: String?,
        limit: Int = 5,
        before: String? = null,
        times: LongArray? = null,
    ): List<Candidate> {
        if (rawXs.size < 2 || rawXs.size != rawYs.size) return emptyList()
        if (pathLength(rawXs, rawYs) < MIN_PATH) return emptyList()

        // Correct for where this user's thumb habitually lands (learned from their swipes).
        val offset = learned.swipeOffset()
        val xs = if (offset == null) rawXs else FloatArray(rawXs.size) { rawXs[it] - offset[0] }
        val ys = if (offset == null) rawYs else FloatArray(rawYs.size) { rawYs[it] - offset[1] }
        val dense = resample(xs, ys, DENSE_POINTS)
        val shape = resample(xs, ys, SHAPE_POINTS)
        val found = HashMap<String, Candidate>()

        val dict = dictionary
        val prevNode = if (dict != null && previous != null) dict.findIgnoreCase(previous) else -1
        val prev2Node = if (dict != null && before != null && prevNode >= 0 && dict.hasTrigrams) {
            dict.findIgnoreCase(before)
        } else {
            -1
        }
        if (dict != null) {
            CandidateSearch(dict, dense).run { node, word ->
                if (learned.isRemoved(word)) return@run
                val geometry = geometry(word, shape) ?: return@run
                var score = dict.freq(node) / 25.5f - tuning.geometry * geometry
                if (prevNode >= 0) score += dict.bigramStrength(prevNode, node) / 255f * BIGRAM_WEIGHT
                if (prev2Node >= 0) score += dict.trigramStrength(prev2Node, prevNode, node) / 255f * BIGRAM_WEIGHT
                // "world's" traces almost the same path as "world"; prefer the plain word.
                if (word.endsWith("'s")) score -= POSSESSIVE_PENALTY
                // The user's habits break ties between look-alike paths (for/four, to/too).
                score += learned.boost(word) + learned.pairBoost(previous, word)
                if (word[0].isUpperCase() && !learned.isKnown(word) && dict.find(word.lowercase()) < 0) {
                    score -= tuning.namePenalty
                }
                keepBest(found, Candidate(word, score, geometry))
            }
        }
        for ((word, freq) in learned.candidates()) {
            if (!passesThrough(word, dense)) continue
            val geometry = geometry(word, shape) ?: continue
            val score = freq / 25.5f - tuning.geometry * geometry + learned.boost(word) + learned.pairBoost(previous, word)
            keepBest(found, Candidate(word, score, geometry))
        }
        // "major" and "Major" trace the same path: offer the likelier one once.
        var ranked = found.values.sortedByDescending { it.score }.distinctBy { it.word.lowercase() }
        // Timing: among the front-runners, prefer the word whose letters are where the finger slowed.
        val dips = if (times != null && times.size == xs.size) Dips.of(xs, ys, times, tuning.minDip) else null
        if (dips != null) {
            ranked = ranked.take(TIMING_CANDIDATES)
                .map { c -> c.copy(score = c.score - timingCost(c.word, dense, dips)) }
                .sortedByDescending { it.score }
        }
        // Words this user picked over the first guess before ("four" over "for") move up.
        val top = ranked.firstOrNull()?.word
        if (top != null) {
            ranked = ranked
                .map { c -> learned.swipeChoiceBoost(top, c.word).let { if (it > 0f) c.copy(score = c.score + it) else c } }
                .sortedByDescending { it.score }
        }
        return ranked.take(limit)
    }

    /**
     * Learns from a finished swipe: [chosen] is what the user kept, [top] the
     * decoder's first guess, [xs]/[ys] the swipe as drawn (before any correction).
     * Records the pick if it differs, and where the swipe started and ended
     * relative to the word's first and last letters.
     */
    fun learnFrom(chosen: String, top: String, xs: FloatArray, ys: FloatArray) {
        if (!chosen.equals(top, ignoreCase = true)) learned.recordSwipeChoice(top, chosen)
        val keys = keysOf(chosen)
        if (keys.size < 2 || xs.size < 2 || xs.size != ys.size) return
        val first = keys.first()
        val last = keys.last()
        val startDx = xs.first() - first[0]
        val startDy = ys.first() - first[1]
        val endDx = xs.last() - last[0]
        val endDy = ys.last() - last[1]
        // A swipe that wandered far from its word says nothing about the user's aim.
        if (hypot(startDx, startDy) > MAX_LEARN_OFFSET || hypot(endDx, endDy) > MAX_LEARN_OFFSET) return
        learned.recordSwipeOffset((startDx + endDx) / 2, (startDy + endDy) / 2)
    }

    // --- Candidate search ----------------------------------------------------------

    /** Trie walk that only follows letters the path visits, in order. */
    private inner class CandidateSearch(val dict: BinaryDictionary, val path: Array<FloatArray>) {
        private val last = path.size - 1
        private val word = StringBuilder()
        private var visited = 0

        fun run(onWord: (node: Int, word: String) -> Unit) {
            walk(0, 0, -1, -1, onWord)
        }

        /**
         * [index]: where along the path the previous letter was matched.
         * [prevKey]: key code point of the previous letter, -1 at the start.
         * [lastKey]: last letter with a key (skipping apostrophes etc.).
         */
        private fun walk(node: Int, index: Int, prevKey: Int, lastKey: Int, onWord: (Int, String) -> Unit) {
            if (visited++ > MAX_VISITS) return
            val first = dict.firstChild(node)
            for (child in first until first + dict.childCount(node)) {
                val raw = dict.char(child)
                val key = proximity.fold(raw)
                val center = centers[key]
                val nextIndex: Int
                val nextLast: Int
                when {
                    center == null -> {
                        // Not on the letter keys (apostrophe, hyphen): skip without moving.
                        if (prevKey < 0) continue
                        nextIndex = index
                        nextLast = lastKey
                    }
                    prevKey < 0 -> {
                        if (distTo(path[0], center) > tuning.startRadius) continue
                        nextIndex = 0
                        nextLast = key
                    }
                    key == lastKey -> {
                        nextIndex = index // doubled letter: no extra movement
                        nextLast = key
                    }
                    else -> {
                        nextIndex = findNear(center, index)
                        if (nextIndex < 0) continue
                        nextLast = key
                    }
                }
                word.appendCodePoint(raw)
                if (dict.freq(child) > 0 && !dict.isOffensive(child) && nextLast >= 0) {
                    val end = centers[nextLast]!!
                    if (distTo(path[last], end) <= tuning.endRadius) onWord(child, word.toString())
                }
                walk(child, nextIndex, if (center == null) prevKey else key, nextLast, onWord)
                word.setLength(word.length - Character.charCount(raw))
            }
        }

        /** First path index at or after [from] that passes near [center], or -1. */
        private fun findNear(center: FloatArray, from: Int): Int {
            for (j in from..last) if (distTo(path[j], center) <= tuning.passRadius) return j
            return -1
        }
    }

    /** Same test as the trie walk, for a single word (learned words). */
    private fun passesThrough(word: String, path: Array<FloatArray>): Boolean {
        val keys = keysOf(word)
        if (keys.isEmpty()) return false
        if (distTo(path[0], keys.first()) > tuning.startRadius) return false
        if (distTo(path[path.size - 1], keys.last()) > tuning.endRadius) return false
        var index = 0
        for (k in 1 until keys.size) {
            if (keys[k] === keys[k - 1]) continue
            var j = index
            while (j < path.size && distTo(path[j], keys[k]) > tuning.passRadius) j++
            if (j == path.size) return false
            index = j
        }
        return true
    }

    // --- Scoring -----------------------------------------------------------------------

    /**
     * How far the swipe is from the word's ideal path: average point distance
     * (where it was drawn) plus the same after normalizing position and size
     * (what shape it was). Lower is better; null if the word has no keys.
     */
    private fun geometry(word: String, swipe: Array<FloatArray>): Float? {
        val keys = keysOf(word)
        if (keys.isEmpty()) return null
        val xs = FloatArray(keys.size) { keys[it][0] }
        val ys = FloatArray(keys.size) { keys[it][1] }
        val ideal = resample(xs, ys, swipe.size)
        val location = meanDistance(swipe, ideal)
        val shape = meanDistance(normalize(swipe), normalize(ideal))
        val ends = distTo(swipe.first(), ideal.first()) + distTo(swipe.last(), ideal.last())
        return location + tuning.shape * shape + tuning.endpoints / tuning.geometry * ends
    }

    // --- Timing --------------------------------------------------------------------

    /**
     * Places where the finger slowed down, by distance along the path (key widths).
     * A depth of 1 is a full stop, 0.35 a slight slowdown compared with the speed
     * just before and after. [weight] is the depth, counted up to three times over
     * when the finger lingered: a deliberate pause is stronger evidence than a
     * passing slowdown.
     */
    private class Dips(val at: FloatArray, val depth: FloatArray, val weight: FloatArray, val length: Float) {
        /** Typical depth of the slowdowns in the middle of the swipe; 0 if there are none. */
        val typical: Float = run {
            var sum = 0f
            var n = 0
            for (i in at.indices) if (at[i] > EDGE && at[i] < length - EDGE) { sum += depth[i]; n++ }
            if (n == 0) 0f else sum / n
        }

        companion object {
            const val EDGE = 0.3f

            fun of(xs: FloatArray, ys: FloatArray, times: LongArray, minDip: Float): Dips? {
                val m = xs.size
                val cum = FloatArray(m)
                for (i in 1 until m) {
                    if (times[i] < times[i - 1]) return null
                    cum[i] = cum[i - 1] + dist(xs[i - 1], ys[i - 1], xs[i], ys[i])
                }
                val total = cum[m - 1]
                if (total <= 0f || times[m - 1] - times[0] < MIN_DURATION_MS) return null
                val n = DENSE_POINTS
                val step = total / (n - 1)
                // Speed around each point: distance covered over the time it took,
                // from first reaching the window to last leaving it (so a pause counts).
                val h = max(SPEED_WINDOW, step)
                val speed = FloatArray(n)
                var lo = 0
                var hi = 0
                for (k in 0 until n) {
                    val s = k * step
                    val s1 = max(0f, s - h)
                    val s2 = minOf(total, s + h)
                    while (lo < m - 1 && cum[lo + 1] < s1) lo++ // cum[lo] <= s1 < ... first reach
                    while (hi < m - 1 && cum[hi + 1] <= s2) hi++ // last index at or before s2
                    val t1 = timeWithin(cum, times, lo, s1)
                    val t2 = timeWithin(cum, times, hi, s2)
                    speed[k] = (s2 - s1) / max(1f, t2 - t1)
                }
                // How much slower than the fastest nearby movement.
                val reach = (CRUISE_REACH / step).toInt().coerceAtLeast(1)
                val slow = FloatArray(n)
                for (k in 0 until n) {
                    var top = 0f
                    for (j in max(0, k - reach)..minOf(n - 1, k + reach)) top = max(top, speed[j])
                    slow[k] = if (top > 0f) 1f - speed[k] / top else 0f
                }
                // Keep the local peaks.
                val apart = (DIP_APART / step).toInt().coerceAtLeast(1)
                val at = ArrayList<Float>()
                val depth = ArrayList<Float>()
                val weight = ArrayList<Float>()
                for (k in 0 until n) {
                    if (slow[k] < minDip) continue
                    var peak = true
                    for (j in max(0, k - apart)..minOf(n - 1, k + apart)) {
                        if (slow[j] > slow[k] || (slow[j] == slow[k] && j < k)) { peak = false; break }
                    }
                    if (peak) {
                        val lingered = 2 * h / speed[k] // ms spent around this point
                        at += k * step
                        depth += slow[k]
                        weight += slow[k] * (1f + ((lingered - PAUSE_MS) / PAUSE_MS).coerceIn(0f, 2f))
                    }
                }
                return Dips(at.toFloatArray(), depth.toFloatArray(), weight.toFloatArray(), total)
            }

            /** The time at distance [s] along the path, within the segment starting at point [i]. */
            private fun timeWithin(cum: FloatArray, times: LongArray, i: Int, s: Float): Float {
                if (i >= cum.size - 1) return times[cum.size - 1].toFloat()
                val span = cum[i + 1] - cum[i]
                val f = if (span > 0f) ((s - cum[i]) / span).coerceIn(0f, 1f) else 0f
                return times[i] + (times[i + 1] - times[i]) * f
            }
        }
    }

    /**
     * How badly a word fits where the finger slowed: slowdowns away from all of
     * its letters, and middle letters passed at full speed (only counted when the
     * finger did slow for other things, so a steady-speed swiper isn't penalized).
     */
    private fun timingCost(word: String, path: Array<FloatArray>, dips: Dips): Float {
        val keys = keysOf(word)
        if (keys.isEmpty()) return 0f
        val where = align(keys, path)
        val step = dips.length / (path.size - 1)
        var cost = 0f
        for (d in dips.at.indices) {
            var near = 0f
            for (k in where) near = max(near, closeness(dips.at[d] - k * step))
            cost += tuning.unexplainedDip * dips.weight[d] * (1f - near)
        }
        if (dips.typical > 0f) {
            for (i in 1 until keys.size - 1) {
                var support = 0f
                for (d in dips.at.indices) support = max(support, dips.depth[d] * closeness(dips.at[d] - where[i] * step))
                cost += tuning.unsupportedLetter * max(0f, dips.typical - support)
            }
        }
        return cost
    }

    private fun closeness(distance: Float): Float {
        val z = distance / tuning.dipReach
        return kotlin.math.exp(-z * z / 2)
    }

    /** For each key, the index of the path point it best matches, keeping the keys in order. */
    private fun align(keys: List<FloatArray>, path: Array<FloatArray>): IntArray {
        val n = path.size
        val m = keys.size
        // best[i][j]: least total squared distance with key i matched at point j.
        val from = Array(m) { IntArray(n) }
        var prev = FloatArray(n)
        var cur = FloatArray(n)
        for (i in 0 until m) {
            var bestBefore = Float.MAX_VALUE
            var bestIndex = 0
            for (j in 0 until n) {
                val dx = path[j][0] - keys[i][0]
                val dy = path[j][1] - keys[i][1]
                val d = dx * dx + dy * dy
                if (i == 0) {
                    cur[j] = d
                } else {
                    if (prev[j] < bestBefore) { bestBefore = prev[j]; bestIndex = j }
                    cur[j] = bestBefore + d
                    from[i][j] = bestIndex
                }
            }
            val t = prev; prev = cur; cur = t
        }
        val out = IntArray(m)
        var j = 0
        for (k in 1 until n) if (prev[k] < prev[j]) j = k
        for (i in m - 1 downTo 0) {
            out[i] = j
            if (i > 0) j = from[i][j]
        }
        return out
    }

    /** Key centers for a word's letters, consecutive repeats collapsed. */
    private fun keysOf(word: String): List<FloatArray> {
        val out = ArrayList<FloatArray>(word.length)
        var i = 0
        while (i < word.length) {
            val cp = word.codePointAt(i)
            val c = centers[proximity.fold(cp)]
            if (c != null && (out.isEmpty() || out.last() !== c)) out += c
            i += Character.charCount(cp)
        }
        return out
    }

    private fun keepBest(map: HashMap<String, Candidate>, c: Candidate) {
        val old = map[c.word]
        if (old == null || c.score > old.score) map[c.word] = c
    }

    companion object {
        const val MIN_PATH = 0.6f
        // Radii and the name handicap were tuned on human-like synthetic swipes
        // (SwipeSimulator: curved, corner-cutting, shifted, overshooting). Wider radii
        // keep hurried swipes' words in the running; ranking still prefers close paths.
        const val START_RADIUS = 1.4f
        const val END_RADIUS = 1.4f
        const val PASS_RADIUS = 1.4f
        const val BIGRAM_WEIGHT = 3f
        const val POSSESSIVE_PENALTY = 1f
        const val NAME_PENALTY = 1f

        // Timing (see docs/swipe-typing.md). Costs are in the same units as the score.
        const val UNEXPLAINED_DIP = 1f
        const val UNSUPPORTED_LETTER = 1f
        /** Only the front-runners are compared by timing. */
        const val TIMING_CANDIDATES = 40
        /** Swipes quicker than this (ms) are too short to read a rhythm from. */
        const val MIN_DURATION_MS = 60L
        /** Speed is averaged over this distance (key widths) either side of a point. */
        const val SPEED_WINDOW = 0.12f
        /** A slowdown is judged against the fastest movement within this distance. */
        const val CRUISE_REACH = 1.2f
        const val MIN_DIP = 0.35f
        /** Lingering longer than this (ms) around a point starts to count as a deliberate pause. */
        const val PAUSE_MS = 120f
        /** Two slowdowns closer than this count once. */
        const val DIP_APART = 0.3f
        const val DIP_REACH = 0.5f

        /** Start/end farther than this from the letters isn't used to learn the user's offset. */
        const val MAX_LEARN_OFFSET = 1.2f
        const val DENSE_POINTS = 120
        const val SHAPE_POINTS = 48
        const val MAX_VISITS = 200_000

        private fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float {
            val dx = x1 - x2
            val dy = y1 - y2
            return sqrt(dx * dx + dy * dy)
        }

        private fun distTo(p: FloatArray, q: FloatArray) = dist(p[0], p[1], q[0], q[1])

        /** [n] points evenly spaced along the polyline (a single point repeats). */
        fun resample(xs: FloatArray, ys: FloatArray, n: Int): Array<FloatArray> {
            val m = xs.size
            if (m == 1) return Array(n) { floatArrayOf(xs[0], ys[0]) }
            val cum = FloatArray(m)
            for (i in 1 until m) cum[i] = cum[i - 1] + dist(xs[i - 1], ys[i - 1], xs[i], ys[i])
            val total = cum[m - 1]
            if (total <= 0f) return Array(n) { floatArrayOf(xs[0], ys[0]) }
            val out = Array(n) { FloatArray(2) }
            var seg = 1
            for (k in 0 until n) {
                val target = total * k / (n - 1)
                while (seg < m - 1 && cum[seg] < target) seg++
                val span = cum[seg] - cum[seg - 1]
                val t = if (span > 0f) (target - cum[seg - 1]) / span else 0f
                out[k][0] = xs[seg - 1] + (xs[seg] - xs[seg - 1]) * t
                out[k][1] = ys[seg - 1] + (ys[seg] - ys[seg - 1]) * t
            }
            return out
        }

        private fun meanDistance(a: Array<FloatArray>, b: Array<FloatArray>): Float {
            var sum = 0f
            for (i in a.indices) sum += distTo(a[i], b[i])
            return sum / a.size
        }

        /** Centered on the centroid and scaled so the larger bounding-box side is 1. */
        private fun normalize(p: Array<FloatArray>): Array<FloatArray> {
            var cx = 0f
            var cy = 0f
            var minX = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            for (q in p) {
                cx += q[0]; cy += q[1]
                minX = minOf(minX, q[0]); maxX = max(maxX, q[0])
                minY = minOf(minY, q[1]); maxY = max(maxY, q[1])
            }
            cx /= p.size
            cy /= p.size
            val scale = max(maxX - minX, maxY - minY).takeIf { it > 1e-3f } ?: 1f
            return Array(p.size) { floatArrayOf((p[it][0] - cx) / scale, (p[it][1] - cy) / scale) }
        }
    }
}
