package app.weft.keyboard.predict

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
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
    val version: Int

    // Node arrays. chars and childCounts come in the narrowest width that fits
    // (version 3); version 1 and 2 files have i32 chars, u16 counts and a parent array.
    private val charWidth: Int
    private val chars: ByteBuffer
    private val countWidth: Int
    private val childCounts: ByteBuffer
    private val firstChild: IntBuffer
    private val parent: IntBuffer?
    private val freqs: ByteBuffer
    private val maxSubs: ByteBuffer
    private val flags: ByteBuffer

    private val bigramWordCount: Int
    private val bigramWord: IntBuffer
    private val bigramStart: IntBuffer
    private val bigramNext: IntBuffer
    private val bigramFreqs: ByteBuffer

    // Three-word context (version 2+). Version 3 groups contexts by first word.
    private val trigramWordCount: Int
    private val trigramWord: IntBuffer
    private val trigramWordStart: IntBuffer
    private val trigramContextCount: Int
    private val trigramFirst: IntBuffer // version 2 only: first word per context
    private val trigramSecond: IntBuffer
    private val trigramStart: IntBuffer
    private val trigramNext: IntBuffer
    private val trigramFreqs: ByteBuffer

    init {
        val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        b.position(0)
        val magic = ByteArray(4).also { b.get(it) }
        require(String(magic, Charsets.US_ASCII) == "EKD1") { "Not an .ekd dictionary" }
        version = b.int
        require(version in 1..3) { "Unsupported dictionary version $version" }
        nodeCount = b.int
        bigramWordCount = b.int
        val bigramEntries = b.int
        trigramWordCount = if (version >= 3) b.int else 0
        trigramContextCount = if (version >= 2) b.int else 0
        val trigramEntries = if (version >= 2) b.int else 0
        val layout = if (version >= 3) b.int else 0
        val metaLength = b.int
        meta = ByteArray(metaLength).also { b.get(it) }.toString(Charsets.UTF_8).trim()

        fun take(bytes: Int): ByteBuffer {
            val slice = b.slice().order(ByteOrder.LITTLE_ENDIAN)
            slice.limit(bytes)
            b.position(b.position() + bytes)
            return slice
        }
        val empty = ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN)

        if (version >= 3) {
            // Every section is padded to 4 bytes.
            fun section(bytes: Int): ByteBuffer = take(bytes).also { b.position(b.position() + (-bytes and 3)) }
            charWidth = when (layout and 3) { 0 -> 1; 1 -> 2; else -> 4 }
            countWidth = if (layout and 4 != 0) 2 else 1
            firstChild = section(nodeCount * 4).asIntBuffer()
            parent = null
            bigramWord = section(bigramWordCount * 4).asIntBuffer()
            bigramStart = section((bigramWordCount + 1) * 4).asIntBuffer()
            bigramNext = section(bigramEntries * 4).asIntBuffer()
            trigramWord = section(trigramWordCount * 4).asIntBuffer()
            trigramWordStart = section((trigramWordCount + 1) * 4).asIntBuffer()
            trigramFirst = empty.asIntBuffer()
            trigramSecond = section(trigramContextCount * 4).asIntBuffer()
            trigramStart = section((trigramContextCount + 1) * 4).asIntBuffer()
            trigramNext = section(trigramEntries * 4).asIntBuffer()
            chars = section(nodeCount * charWidth)
            childCounts = section(nodeCount * countWidth)
            freqs = section(nodeCount)
            maxSubs = section(nodeCount)
            flags = section(nodeCount)
            bigramFreqs = section(bigramEntries)
            trigramFreqs = section(trigramEntries)
        } else {
            charWidth = 4
            countWidth = 2
            chars = take(nodeCount * 4)
            firstChild = take(nodeCount * 4).asIntBuffer()
            parent = take(nodeCount * 4).asIntBuffer()
            bigramWord = take(bigramWordCount * 4).asIntBuffer()
            bigramStart = take((bigramWordCount + 1) * 4).asIntBuffer()
            bigramNext = take(bigramEntries * 4).asIntBuffer()
            childCounts = take(nodeCount * 2)
            freqs = take(nodeCount)
            maxSubs = take(nodeCount)
            flags = take(nodeCount)
            bigramFreqs = take(bigramEntries)
            if (version >= 2) b.position(b.position() + (-b.position() and 3)) // pad to 4
            trigramWord = empty.asIntBuffer()
            trigramWordStart = empty.asIntBuffer()
            trigramFirst = take(trigramContextCount * 4).asIntBuffer()
            trigramSecond = take(trigramContextCount * 4).asIntBuffer()
            trigramStart = take((if (version >= 2) trigramContextCount + 1 else 0) * 4).asIntBuffer()
            trigramNext = take(trigramEntries * 4).asIntBuffer()
            trigramFreqs = take(trigramEntries)
        }
    }

    fun char(node: Int): Int = when (charWidth) {
        1 -> chars.get(node).toInt() and 0xFF
        2 -> chars.getShort(node * 2).toInt() and 0xFFFF
        else -> chars.getInt(node * 4)
    }

    fun firstChild(node: Int): Int = firstChild.get(node)

    fun childCount(node: Int): Int =
        if (countWidth == 1) childCounts.get(node).toInt() and 0xFF else childCounts.getShort(node * 2).toInt() and 0xFFFF

    /**
     * The node above [node]. Version 3 has no parent array: firstChild never
     * decreases, so the parent is the last node whose firstChild is <= [node].
     */
    fun parent(node: Int): Int {
        parent?.let { return it.get(node) }
        if (node <= 0) return -1
        var lo = 0
        var hi = node - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (firstChild.get(mid) <= node) lo = mid else hi = mid - 1
        }
        return lo
    }

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
            cps.add(char(n))
            n = parent(n)
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
            n = parent(n)
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
            val c = char(mid)
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
                if (Character.toLowerCase(char(c)) == target[i]) walk(c, i + 1)
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

    val hasTrigrams: Boolean get() = trigramContextCount > 0

    /**
     * Words that are notably likely after [first] [second] ("I want" → "to"),
     * beyond what [second] alone predicts: (word node, strength 1..255).
     */
    fun trigrams(first: Int, second: Int): List<Pair<Int, Int>> {
        val context = if (version >= 3) contextV3(first, second) else contextV2(first, second)
        if (context < 0) return emptyList()
        val from = trigramStart.get(context)
        val to = trigramStart.get(context + 1)
        return (from until to).map { trigramNext.get(it) to (trigramFreqs.get(it).toInt() and 0xFF) }
    }

    /** Version 3: find the first word's group, then the second word inside it. */
    private fun contextV3(first: Int, second: Int): Int {
        val group = search(trigramWord, 0, trigramWordCount - 1, first)
        if (group < 0) return -1
        return search(trigramSecond, trigramWordStart.get(group), trigramWordStart.get(group + 1) - 1, second)
    }

    private fun contextV2(first: Int, second: Int): Int {
        var lo = 0
        var hi = trigramContextCount - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val a = trigramFirst.get(mid)
            val c = if (a != first) a.compareTo(first) else trigramSecond.get(mid).compareTo(second)
            when {
                c < 0 -> lo = mid + 1
                c > 0 -> hi = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    /** Index of [value] in the sorted range [lo]..[hi] of [buf], or -1. */
    private fun search(buf: IntBuffer, lo0: Int, hi0: Int, value: Int): Int {
        var lo = lo0
        var hi = hi0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val v = buf.get(mid)
            when {
                v < value -> lo = mid + 1
                v > value -> hi = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    /** Strength (0..255) of [next] following [first] [second]; 0 if nothing special. */
    fun trigramStrength(first: Int, second: Int, next: Int): Int =
        if (first < 0 || second < 0) 0 else trigrams(first, second).firstOrNull { it.first == next }?.second ?: 0

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
