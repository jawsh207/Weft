package app.weft.keyboard.emoji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Fixture built from tools/emojigen/testdata/emoji-test-sample.txt:
 *   python3 tools/emojigen/build_emoji.py \
 *     --input tools/emojigen/testdata/emoji-test-sample.txt \
 *     --out app/src/test/resources/emoji_fixture.txt
 */
class EmojiTest {

    private lateinit var text: String
    private lateinit var catalog: EmojiCatalog

    @Before
    fun setUp() {
        text = javaClass.classLoader!!.getResourceAsStream("emoji_fixture.txt")!!.readBytes().toString(Charsets.UTF_8)
        catalog = EmojiCatalog.parse(text)
    }

    @Test
    fun parsesGroupsInUnicodeOrderWithoutComponents() {
        assertEquals(listOf("Smileys & Emotion", "People & Body", "Food & Drink", "Flags"), catalog.groups.map { it.name })
        assertEquals("😀", catalog.groups[0].emoji[0].base)
        assertEquals("grinning face", catalog.groups[0].emoji[0].name)
    }

    @Test
    fun foldsSingleToneVariantsIntoBase() {
        val wave = catalog.find("👋")!!
        assertEquals(listOf("👋🏻", "👋🏼", "👋🏽", "👋🏾", "👋🏿"), wave.variants)
        assertEquals(wave, catalog.find("👋🏽"))
        // Toned forms written without the emoji-style selector still attach.
        assertEquals(listOf("☝🏻", "☝🏿"), catalog.find("☝️")!!.variants)
        // Mixed-tone pairs are left out.
        assertEquals(1, catalog.find("🧑‍🤝‍🧑")!!.variants.size)
    }

    @Test
    fun dropsWhatTheFontCannotDraw() {
        val noFlags = EmojiCatalog.parse(text) { !it.startsWith("🇨") && it != "👋🏿" }
        assertNull(noFlags.find("🇨🇦"))
        assertEquals(listOf("Smileys & Emotion", "People & Body", "Food & Drink"), noFlags.groups.map { it.name })
        assertEquals(4, noFlags.find("👋")!!.variants.size)
    }

    @Test
    fun searchesByName() {
        assertEquals("👍", catalog.search("thumbs").first().base)
        assertEquals("👍", catalog.search("thu up").first().base)
        assertEquals("❤️", catalog.search("heart").first().base)
        assertEquals("🇨🇦", catalog.search("canada").first().base)
        assertTrue(catalog.search("face").map { it.base }.containsAll(listOf("😀", "😂", "☺️")))
        assertTrue(catalog.search("zzz").isEmpty())
        assertTrue(catalog.search("  ").isEmpty())
    }

    @Test
    fun historyKeepsRecentsAndTones() {
        val h = EmojiHistory(maxRecents = 3)
        listOf("😀", "🍕", "👋", "😀", "❤️").forEach(h::use)
        assertEquals(listOf("❤️", "😀", "👋"), h.recents)

        h.setPreferred("👋", "👋🏽")
        assertEquals("👋🏽", h.preferred("👋"))
        assertEquals("🍕", h.preferred("🍕"))

        val copy = EmojiHistory(maxRecents = 3).apply { decode(h.encodeRecents(), h.encodeTones()) }
        assertEquals(h.recents, copy.recents)
        assertEquals("👋🏽", copy.preferred("👋"))

        h.setPreferred("👋", "👋")
        assertEquals("👋", h.preferred("👋"))
    }
}
