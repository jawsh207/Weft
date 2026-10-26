package app.weft.keyboard.predict

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import java.nio.ShortBuffer
import java.nio.channels.FileChannel

/**
 * Read-only view over an .ekd dictionary built by tools/dictgen/build_dict.py.
 *
 * The file is a breadth-first trie stored as flat arrays, so it can be memory
 * mapped straight from the APK (assets are stored uncompressed) with no parsing
 * and almost no heap. A word's id is the index of its terminal node.
 */
class BinaryDictionary(buffer: ByteBuffer) {

    val nodeCount: Int
    val meta: String

    private val chars: IntBuffer
    private val firstChild: IntBuffer
    private val parent: IntBuffer
    private val bigramWord: IntBuffer
    private val bigramStart: IntBuffer
    private val bigramNext: IntBuffer
    private val childCounts: ShortBuffer
    private val freqs: ByteBuffer
    private val maxSubs: ByteBuffer
    private val flags: ByteBuffer
    private val bigramFreqs: ByteBuffer
    private val bigramWordCount: Int

    init {
        val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        b.position(0)
        val magic = ByteArray(4).also { b.get(it) }
        require(String(magic, Charsets.US_ASCII) == "EKD1") { "Not an .ekd dictionary" }
        val version = b.int
        require(version == 1) { "Unsupported dictionary version $version" }
        nodeCount = b.int
        bigramWordCount = b.int
        val bigramEntries = b.int
        val metaLength = b.int
        meta = ByteArray(metaLength).also { b.get(it) }.toString(Charsets.UTF_8).trim()

        fun take(bytes: Int): ByteBuffer {
            val slice = b.slice().order(ByteOrder.LITTLE_ENDIAN)
            slice.limit(bytes)
            b.position(b.position() + bytes)
            return slice
        }

        chars = take(nodeCount * 4).asIntBuffer()
        firstChild = take(nodeCount * 4).asIntBuffer()
        parent = take(nodeCount * 4).asIntBuffer()
        bigramWord = take(bigramWordCount * 4).asIntBuffer()
        bigramStart = take((bigramWordCount + 1) * 4).asIntBuffer()
        bigramNext = take(bigramEntries * 4).asIntBuffer()
        childCounts = take(nodeCount * 2).asShortBuffer()
        freqs = take(nodeCount)
        maxSubs = take(nodeCount)
        flags = take(nodeCount)
        bigramFreqs = take(bigramEntries)
    }

    fun char(node: Int): Int = chars.get(node)
    fun firstChild(node: Int): Int = firstChild.get(node)
    fun childCount(node: Int): Int = childCounts.get(node).toInt() and 0xFFFF
    fun parent(node: Int): Int = parent.get(node)

    /** 1..255 if a word ends at [node], else 0. */
    fun freq(node: Int): Int = freqs.get(node).toInt() and 0xFF

    /** Highest word frequency in the subtree rooted at [node]. */
    fun maxSub(node: Int): Int = maxSubs.get(node).toInt() and 0xFF

    fun isOffensive(node: Int): Boolean = (flags.get(node).toInt() and FLAG_OFFENSIVE) != 0

    /** Rebuilds the word ending at [node]. */
    fun word(node: Int): String {
        val cps = ArrayList<Int>(16)
        var n = node
        while (n > 0) {
            cps.add(chars.get(n))
            n = parent.get(n)
        }
        val sb = StringBuilder(cps.size)
        for (i in cps.indices.reversed()) sb.appendCodePoint(cps[i])
        return sb.toString()
    }

    /** Length of the word ending at [node], in code points. */
    fun depth(node: Int): Int {
        var d = 0
        var n = node
        while (n > 0) {
            d++
            n = parent.get(n)
        }
        return d
    }

    /** Child of [node] with code point [cp], or -1. Children are sorted, so binary search. */
    fun child(node: Int, cp: Int): Int {
        var lo = firstChild(node)
        var hi = lo + childCount(node) - 1
        if (childCount(node) == 0) return -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = chars.get(mid)
            when {
                c < cp -> lo = mid + 1
                c > cp -> hi = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    /** Terminal node for exactly [word] (case-sensitive), or -1. */
    fun find(word: String): Int {
        var node = 0
        var i = 0
        while (i < word.length) {
            val cp = word.codePointAt(i)
            node = child(node, cp)
            if (node < 0) return -1
            i += Character.charCount(cp)
        }
        return if (node > 0 && freq(node) > 0) node else -1
    }

    /** Most frequent word matching [word] case-insensitively ("monday" → "Monday"), or -1. */
    fun findIgnoreCase(word: String): Int {
        val target = word.lowercase().codePoints().toArray()
        var best = -1
        fun walk(node: Int, i: Int) {
            if (i == target.size) {
                if (freq(node) > 0 && (best < 0 || freq(node) > freq(best))) best = node
                return
            }
            val start = firstChild(node)
            for (c in start until start + childCount(node)) {
                if (Character.toLowerCase(chars.get(c)) == target[i]) walk(c, i + 1)
            }
        }
        if (target.isNotEmpty()) walk(0, 0)
        return best
    }

    /** Next-word predictions after the word at [node]: (word node, strength 1..255). */
    fun bigrams(node: Int): List<Pair<Int, Int>> {
        var lo = 0
        var hi = bigramWordCount - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val w = bigramWord.get(mid)
            when {
                w < node -> lo = mid + 1
                w > node -> hi = mid - 1
                else -> {
                    val from = bigramStart.get(mid)
                    val to = bigramStart.get(mid + 1)
                    return (from until to).map {
                        bigramNext.get(it) to (bigramFreqs.get(it).toInt() and 0xFF)
                    }
                }
            }
        }
        return emptyList()
    }

    /** Strength (0..255) of [next] following [previous]. */
    fun bigramStrength(previous: Int, next: Int): Int =
        bigrams(previous).firstOrNull { it.first == next }?.second ?: 0

    companion object {
        const val FLAG_OFFENSIVE = 0x01

        /** Memory-maps a dictionary file. */
        fun map(file: File): BinaryDictionary =
            RandomAccessFile(file, "r").use { raf ->
                BinaryDictionary(raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length()))
            }

        /** Memory-maps a region of a file, e.g. an uncompressed APK asset. */
        fun map(channel: FileChannel, offset: Long, length: Long): BinaryDictionary =
            BinaryDictionary(channel.map(FileChannel.MapMode.READ_ONLY, offset, length))
    }
}
