package app.weft.keyboard.emoji

import android.graphics.Paint

/**
 * Emoji are drawn with the system font — no EmojiCompat, no bundled font —
 * so they always match the user's device. [Paint.hasGlyph] filters out
 * anything the installed font can't render, so the picker never shows tofu.
 *
 * This starter set is a placeholder. The full picker should be generated from
 * Unicode's emoji-test.txt (categories, names for search, skin-tone variants).
 */
object SystemEmoji {
    private const val STARTER =
        "😀 😃 😄 😁 😆 😅 🤣 😂 🙂 🙃 🫠 😉 😊 😇 🥰 😍 🤩 😘 😗 😚 😙 🥲 " +
            "😋 😛 😜 🤪 😝 🤑 🤗 🤭 🫢 🫣 🤫 🤔 🫡 🤐 🤨 😐 😑 😶 🫥 😏 😒 🙄 " +
            "😬 😮‍💨 🤥 🫨 😌 😔 😪 🤤 😴 😷 🤒 🤕 🤢 🤮 🤧 🥵 🥶 🥴 😵 🤯 🤠 " +
            "🥳 🥸 😎 🤓 🧐 😕 🫤 😟 🙁 😮 😯 😲 😳 🥺 🥹 😦 😧 😨 😰 😥 😢 😭 " +
            "😱 😖 😣 😞 😓 😩 😫 🥱 😤 😡 😠 🤬 😈 👿 💀 💩 🤡 👻 👽 🤖 " +
            "👋 🤚 ✋ 🖖 👌 🤌 🤏 ✌️ 🤞 🫰 🤟 🤘 🤙 👈 👉 👆 👇 ☝️ 🫵 👍 👎 " +
            "✊ 👊 🤛 🤜 👏 🙌 🫶 👐 🤲 🤝 🙏 💪 " +
            "❤️ 🧡 💛 💚 💙 🩵 💜 🤎 🖤 🩶 🤍 🩷 💔 ❤️‍🔥 💕 💞 💓 💗 💖 💘 💝 " +
            "🔥 ✨ ⭐ 🌟 💯 🎉 🎊 👀 ✅ ❌ ⚡ 🌈 ☀️ 🌙 ☕ 🍕 🍔 🌮 🍣 🍩 🎂 🍻"

    val starter: List<String> by lazy {
        val paint = Paint()
        STARTER.split(' ').filter { it.isNotBlank() && paint.hasGlyph(it) }
    }
}
