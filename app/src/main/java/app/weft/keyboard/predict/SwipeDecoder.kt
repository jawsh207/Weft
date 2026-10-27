package app.weft.keyboard.predict

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
    )

    data class Candidate(val word: String, val score: Float, val geometry: Float)

    /** Rough path length (key widths) below which a gesture is treated as a tap. */
    fun pathLength(xs: FloatArray, ys: FloatArray): Float {
        var len = 0f
        for (i in 1 until xs.size) len += dist(xs[i - 1], ys[i - 1], xs[i], ys[i])
        return len
    }

    fun decode(xs: FloatArray, ys: FloatArray, previous: String?, limit: Int = 5): List<Candidate> {
        if (xs.size < 2 || xs.size != ys.size) return emptyList()
        if (pathLength(xs, ys) < MIN_PATH) return emptyList()

        val dense = resample(xs, ys, DENSE_POINTS)
        val shape = resample(xs, ys, SHAPE_POINTS)
        val found = HashMap<String, Candidate>()

        val dict = dictionary
        val prevNode = if (dict != null && previous != null) dict.findIgnoreCase(previous) else -1
        if (dict != null) {
            CandidateSearch(dict, dense).run { node, word ->
                if (learned.isRemoved(word)) return@run
                val geometry = geometry(word, shape) ?: return@run
                var score = dict.freq(node) / 25.5f - tuning.geometry * geometry
                if (prevNode >= 0) score += dict.bigramStrength(prevNode, node) / 255f * BIGRAM_WEIGHT
                // "world's" traces almost the same path as "world"; prefer the plain word.
                if (word.endsWith("'s")) score -= POSSESSIVE_PENALTY
                // The user's habits break ties between look-alike paths (for/four, to/too).
                score += learned.boost(word) + learned.pairBoost(previous, word)
                keepBest(found, Candidate(word, score, geometry))
            }
        }
        for ((word, freq) in learned.candidates()) {
            if (!passesThrough(word, dense)) continue
            val geometry = geometry(word, shape) ?: continue
            val score = freq / 25.5f - tuning.geometry * geometry + learned.boost(word) + learned.pairBoost(previous, word)
            keepBest(found, Candidate(word, score, geometry))
        }
        return found.values.sortedByDescending { it.score }.take(limit)
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
                        if (distTo(path[0], center) > START_RADIUS) continue
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
                    if (distTo(path[last], end) <= END_RADIUS) onWord(child, word.toString())
                }
                walk(child, nextIndex, if (center == null) prevKey else key, nextLast, onWord)
                word.setLength(word.length - Character.charCount(raw))
            }
        }

        /** First path index at or after [from] that passes near [center], or -1. */
        private fun findNear(center: FloatArray, from: Int): Int {
            for (j in from..last) if (distTo(path[j], center) <= PASS_RADIUS) return j
            return -1
        }
    }

    /** Same test as the trie walk, for a single word (learned words). */
    private fun passesThrough(word: String, path: Array<FloatArray>): Boolean {
        val keys = keysOf(word)
        if (keys.isEmpty()) return false
        if (distTo(path[0], keys.first()) > START_RADIUS) return false
        if (distTo(path[path.size - 1], keys.last()) > END_RADIUS) return false
        var index = 0
        for (k in 1 until keys.size) {
            if (keys[k] === keys[k - 1]) continue
            var j = index
            while (j < path.size && distTo(path[j], keys[k]) > PASS_RADIUS) j++
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
        const val START_RADIUS = 1.1f
        const val END_RADIUS = 1.1f
        const val PASS_RADIUS = 0.95f
        const val BIGRAM_WEIGHT = 3f
        const val POSSESSIVE_PENALTY = 1f
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
