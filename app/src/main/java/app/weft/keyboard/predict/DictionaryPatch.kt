package app.weft.keyboard.predict

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Rebuilds a country's English dictionary from the shared one. The app ships the English words
 * once (main_en.ekdz) plus a small patch per country (patch_en_GB.ekdp and so on), which saves
 * about 9 MB; the UK, Canadian and Australian dictionaries are the US one re-spelled, so they
 * differ in only a few thousand word frequencies and spellings.
 *
 * tools/dictgen/share_dicts.py makes the patches and describes their layout: the country's
 * description and sources, then runs of bytes that replace the base's. DictionaryLoader applies
 * a patch once, the first time that dictionary is used, and saves the result like any other
 * dictionary.
 */
object DictionaryPatch {
    private const val HEADER = 40 // "EKD1" and nine int32s, the last being the meta length

    /** The dictionary [patch] makes from [base] (both unpacked, not gzipped). */
    fun apply(base: ByteArray, patch: ByteArray): ByteArray {
        val p = ByteBuffer.wrap(patch).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4).also { p.get(it) }
        require(String(magic, Charsets.US_ASCII) == "EKP1") { "Not a dictionary patch" }
        val metaLength = p.int
        val meta = ByteArray(metaLength).also { p.get(it) }
        val b = ByteBuffer.wrap(base).order(ByteOrder.LITTLE_ENDIAN)
        val baseMeta = b.getInt(HEADER - 4)
        val bodyStart = HEADER + baseMeta
        val out = ByteArray(HEADER + metaLength + base.size - bodyStart)
        System.arraycopy(base, 0, out, 0, HEADER - 4)
        ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).putInt(HEADER - 4, metaLength)
        System.arraycopy(meta, 0, out, HEADER, metaLength)
        val outBody = HEADER + metaLength
        System.arraycopy(base, bodyStart, out, outBody, base.size - bodyStart)
        repeat(p.int) {
            val offset = p.int
            val length = p.int
            require(offset >= 0 && length >= 0 && outBody + offset + length <= out.size) { "Patch doesn't fit" }
            p.get(out, outBody + offset, length)
            p.position((p.position() + 3) and 3.inv())
        }
        return out
    }
}
