package app.weft.keyboard.predict

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The shared English dictionary and its per-country patches (tools/dictgen/share_dicts.py) must
 * rebuild each country's dictionary: the patch format itself, and the real UK, Canadian and
 * Australian dictionaries keeping their own spellings.
 */
class DictionaryPatchTest {

    private fun le(vararg ints: Int): ByteArray =
        ByteBuffer.allocate(4 * ints.size).order(ByteOrder.LITTLE_ENDIAN).apply { ints.forEach { putInt(it) } }.array()

    @Test
    fun replacesMetaAndPatchesBody() {
        // A fake dictionary: "EKD1", nine ints (the last is the meta length 4), meta "base", body 0..9.
        val base = "EKD1".toByteArray() + le(3, 1, 2, 3, 4, 5, 6, 7, 4) + "base".toByteArray() + ByteArray(10) { it.toByte() }
        // Patch: new meta "country!" (8 bytes), two runs: body[2..3] = 42 43, body[9] = 99.
        val patch = "EKP1".toByteArray() + le(8) + "country!".toByteArray() + le(2) +
            le(2, 2) + byteArrayOf(42, 43, 0, 0) + le(9, 1) + byteArrayOf(99, 0, 0, 0)
        val out = DictionaryPatch.apply(base, patch)
        val expected = "EKD1".toByteArray() + le(3, 1, 2, 3, 4, 5, 6, 7, 8) + "country!".toByteArray() +
            byteArrayOf(0, 1, 42, 43, 4, 5, 6, 7, 8, 99)
        assertArrayEquals(expected, out)
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesPatchesThatDontFit() {
        val base = "EKD1".toByteArray() + le(3, 1, 2, 3, 4, 5, 6, 7, 0) + ByteArray(4)
        DictionaryPatch.apply(base, "EKP1".toByteArray() + le(0) + le(1) + le(3, 4) + ByteArray(4))
    }

    @Test
    fun rebuildsEachCountry() {
        val us = RealDictionary.load("en_US")
        assumeTrue("dictionaries not built", us != null)
        us!!
        val gb = RealDictionary.load("en_GB")!!
        val au = RealDictionary.load("en_AU")!!
        val ca = RealDictionary.load("en_CA")!!
        assertTrue(gb.meta.contains("\"locale\": \"en_GB\""))
        assertTrue(us.meta.contains("\"locale\": \"en_US\""))
        // Regional spellings belong to their countries only.
        assertTrue(us.find("colour") < 0 && us.find("color") >= 0)
        assertTrue(gb.find("colour") >= 0 && au.find("colour") >= 0 && ca.find("colour") >= 0)
        assertTrue(us.find("realise") < 0 && gb.find("realise") >= 0)
        // Word pairs the US doesn't have aren't offered there.
        val colourNode = gb.find("colour")
        assertTrue(gb.bigrams(gb.find("the")).any { it.first == colourNode } || gb.bigrams(colourNode).isNotEmpty())
        val usThe = us.find("the")
        assertTrue(us.bigrams(usThe).all { us.freq(it.first) > 0 && it.second > 0 })
    }
}
