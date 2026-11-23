package app.weft.keyboard.predict

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.tanh

/**
 * A small neural network that guesses the next word from the whole sentence so far, not just the
 * last one or two words. Suggester mixes its guesses into next-word predictions (see
 * [Suggester.suggest]), so "I'm going to the" can lean towards places and "Can you pick up the"
 * towards things.
 *
 * The model is trained by tools/nextword/train.py and ships as an int8 .wlm file (layout in
 * tools/nextword/wlm.py). It reads words one at a time with an LSTM (a layer that keeps a running
 * summary of the sentence) and scores all 30,000 words it knows through an "adaptive softmax":
 * the 2,000 most common words directly, rarer ones in two smaller groups. Everything runs here in
 * plain Kotlin, about two million multiply-adds per word, with no native libraries.
 *
 * The word vectors stay in the memory-mapped file and are read one row at a time; the other
 * weights (about 2 MB) are copied into arrays for speed. The summary after each word of the last
 * sentence is kept, so typing one more word costs one step, not a re-read of the sentence.
 * Thread-safe: [predict] is synchronized.
 */
class NextWordModel(buffer: ByteBuffer) {

    /** Number of words the model knows, including "<s>" (start of sentence) and "<unk>". */
    val vocab: Int
    private val embDim: Int
    private val hid: Int
    private val cutoffs: IntArray

    /** How strongly the model's log-probabilities count against the dictionary's scores. */
    val weight: Float

    /** How far below the dictionary's weakest candidate a word only the model suggests starts. */
    val offset: Float

    /** Taken off "<unk>"'s log-probability for a word the model doesn't know. */
    val unkPenalty: Float

    /** The text the model was trained on and its licenses, for Settings › About. */
    val sources: String

    private val buf: ByteBuffer = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
    private val wordOffsets: IntArray
    private val sortedIds: IntArray
    private val wordBase: Int

    /** An int8 matrix: row r is data[r·cols until (r+1)·cols] × scale[r]. */
    private class Matrix(val rows: Int, val cols: Int, val scale: FloatArray, val data: ByteArray?, val at: Int)

    private val embedding: Matrix
    private val wIh: Matrix
    private val wHh: Matrix
    private val head: Matrix
    private val tails: List<Pair<Matrix, Matrix>>
    private val bias: FloatArray

    init {
        val b = buf
        b.position(0)
        val magic = ByteArray(4).also { b.get(it) }
        require(String(magic, Charsets.US_ASCII) == "WLM1") { "Not a next-word model" }
        require(b.int == 1) { "Unsupported next-word model version" }
        vocab = b.int
        embDim = b.int
        hid = b.int
        cutoffs = IntArray(b.int) { 0 }
        for (i in cutoffs.indices) cutoffs[i] = b.int
        weight = b.float
        offset = b.float
        unkPenalty = b.float
        val srcLen = b.int
        sources = ByteArray(srcLen).also { b.get(it) }.toString(Charsets.UTF_8)
        align(b)
        wordOffsets = IntArray(vocab + 1) { b.int }
        sortedIds = IntArray(vocab) { b.int }
        wordBase = b.position()
        b.position(wordBase + wordOffsets[vocab])
        align(b)
        embedding = matrix(b, copy = false)
        wIh = matrix(b, copy = true)
        wHh = matrix(b, copy = true)
        head = matrix(b, copy = true)
        tails = cutoffs.map { matrix(b, copy = true) to matrix(b, copy = true) }
        bias = FloatArray(b.int) { b.float }
        require(wIh.rows == 4 * hid && bias.size == 4 * hid && embedding.cols == embDim) { "Malformed next-word model" }
    }

    private fun align(b: ByteBuffer) {
        b.position((b.position() + 3) and 3.inv())
    }

    private fun matrix(b: ByteBuffer, copy: Boolean): Matrix {
        val rows = b.int
        val cols = b.int
        val scale = FloatArray(rows) { b.float }
        val at = b.position()
        val data = if (copy) ByteArray(rows * cols).also { b.get(it) } else null
        b.position(at + rows * cols)
        align(b)
        return Matrix(rows, cols, scale, data, at)
    }

    /** The word with id [id] (0 is "<s>", 1 is "<unk>"). */
    fun word(id: Int): String {
        val start = wordOffsets[id]
        val bytes = ByteArray(wordOffsets[id + 1] - start)
        for (i in bytes.indices) bytes[i] = buf.get(wordBase + start + i)
        return String(bytes, Charsets.UTF_8)
    }

    /** The id of [word] (lowercase, straight apostrophes), or -1 if the model doesn't know it. */
    fun id(word: String): Int {
        val key = word.toByteArray(Charsets.UTF_8)
        var lo = 0
        var hi = vocab - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = compare(sortedIds[mid], key)
            when {
                c < 0 -> lo = mid + 1
                c > 0 -> hi = mid - 1
                else -> return sortedIds[mid]
            }
        }
        return -1
    }

    /** Compares word [id]'s UTF-8 bytes with [key] as unsigned bytes (the order tools/nextword sorts in). */
    private fun compare(id: Int, key: ByteArray): Int {
        val start = wordBase + wordOffsets[id]
        val len = wordOffsets[id + 1] - wordOffsets[id]
        for (i in 0 until minOf(len, key.size)) {
            val d = (buf.get(start + i).toInt() and 0xFF) - (key[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return len - key.size
    }

    /** What the model expects after one sentence prefix: a log-probability for every word id. */
    class Prediction internal constructor(private val model: NextWordModel, val logProbs: FloatArray) {
        /** Log-probability of [word] coming next; words the model doesn't know share "<unk>"'s, less a penalty. */
        fun logProb(word: String): Float {
            val id = model.id(normalize(word))
            return if (id >= 0) logProbs[id] else logProbs[UNK] - model.unkPenalty
        }

        /** The model's [n] likeliest next words with their log-probabilities, best first. */
        fun top(n: Int): List<Pair<String, Float>> {
            val ids = IntArray(n) { -1 }
            var count = 0
            for (i in 2 until logProbs.size) {
                val v = logProbs[i]
                if (count == n && v <= logProbs[ids[n - 1]]) continue
                var j = if (count < n) count++ else n - 1
                while (j > 0 && logProbs[ids[j - 1]] < v) {
                    ids[j] = ids[j - 1]
                    j--
                }
                ids[j] = i
            }
            return (0 until count).map { model.word(ids[it]) to logProbs[ids[it]] }
        }
    }

    // The running summary after each word of the last sentence read, so the next call can resume.
    private val seen = ArrayList<Int>()
    private val hs = ArrayList<FloatArray>()
    private val cs = ArrayList<FloatArray>()
    private var lastPrediction: Prediction? = null

    /**
     * What comes after [sentence] (the words of the current sentence so far, any case). Only the
     * last [MAX_CONTEXT] words are read.
     */
    @Synchronized
    fun predict(sentence: List<String>): Prediction {
        val ids = sentence.takeLast(MAX_CONTEXT).map { id(normalize(it)).let { i -> if (i < 0) UNK else i } }
        var keep = 0
        while (keep < ids.size && keep < seen.size && ids[keep] == seen[keep]) keep++
        if (keep == ids.size && keep == seen.size) lastPrediction?.let { return it }
        if (hs.isEmpty()) {
            val h = FloatArray(hid)
            val c = FloatArray(hid)
            step(START, h, c)
            hs.add(h)
            cs.add(c)
        }
        while (seen.size > keep) {
            seen.removeAt(seen.size - 1)
            hs.removeAt(hs.size - 1)
            cs.removeAt(cs.size - 1)
        }
        for (i in keep until ids.size) {
            val h = hs.last().copyOf()
            val c = cs.last().copyOf()
            step(ids[i], h, c)
            seen.add(ids[i])
            hs.add(h)
            cs.add(c)
        }
        return Prediction(this, logProbs(hs.last())).also { lastPrediction = it }
    }

    /** One LSTM step: reads word [id] into the summary ([h], [c]), in place. */
    private fun step(id: Int, h: FloatArray, c: FloatArray) {
        val e = FloatArray(embDim)
        val s = embedding.scale[id]
        val at = embedding.at + id * embDim
        for (j in 0 until embDim) e[j] = buf.get(at + j) * s
        val g = FloatArray(4 * hid)
        matVec(wIh, e, g, 0)
        val r = FloatArray(4 * hid)
        matVec(wHh, h, r, 0)
        for (k in 0 until hid) {
            val i = sigmoid(g[k] + r[k] + bias[k])
            val f = sigmoid(g[hid + k] + r[hid + k] + bias[hid + k])
            val z = tanh(g[2 * hid + k] + r[2 * hid + k] + bias[2 * hid + k])
            val o = sigmoid(g[3 * hid + k] + r[3 * hid + k] + bias[3 * hid + k])
            c[k] = f * c[k] + i * z
            h[k] = o * tanh(c[k])
        }
    }

    /** Log-probability of every word after the summary [h] (the adaptive softmax). */
    private fun logProbs(h: FloatArray): FloatArray {
        val out = FloatArray(vocab)
        val headOut = FloatArray(head.rows)
        matVec(head, h, headOut, 0)
        logSoftmax(headOut, 0, headOut.size)
        val short = cutoffs[0]
        System.arraycopy(headOut, 0, out, 0, short)
        for ((k, pair) in tails.withIndex()) {
            val (proj, outW) = pair
            val p = FloatArray(proj.rows)
            matVec(proj, h, p, 0)
            val start = cutoffs[k]
            matVec(outW, p, out, start)
            val end = if (k + 1 < cutoffs.size) cutoffs[k + 1] else vocab
            logSoftmax(out, start, end)
            val add = headOut[short + k]
            for (i in start until end) out[i] += add
        }
        return out
    }

    /** out[outAt + r] = row r of [m] · [x]. */
    private fun matVec(m: Matrix, x: FloatArray, out: FloatArray, outAt: Int) {
        val d = m.data!!
        val cols = m.cols
        var base = 0
        for (r in 0 until m.rows) {
            var s = 0f
            for (j in 0 until cols) s += d[base + j] * x[j]
            out[outAt + r] = s * m.scale[r]
            base += cols
        }
    }

    private fun logSoftmax(v: FloatArray, from: Int, to: Int) {
        var max = Float.NEGATIVE_INFINITY
        for (i in from until to) if (v[i] > max) max = v[i]
        var sum = 0.0
        for (i in from until to) sum += exp((v[i] - max).toDouble())
        val shift = max + ln(sum).toFloat()
        for (i in from until to) v[i] -= shift
    }

    private fun sigmoid(v: Float): Float = 1f / (1f + exp(-v))

    companion object {
        private const val START = 0
        private const val UNK = 1

        /** Longest sentence prefix read; the model trained on 32-word windows. */
        const val MAX_CONTEXT = 30

        /** Lowercase with straight apostrophes, as the model's words are stored. */
        fun normalize(word: String): String = word.replace('’', '\'').lowercase()

        /** Opens a .wlm file by memory-mapping it. */
        fun map(file: File): NextWordModel =
            RandomAccessFile(file, "r").use { raf ->
                NextWordModel(raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length()))
            }
    }
}
