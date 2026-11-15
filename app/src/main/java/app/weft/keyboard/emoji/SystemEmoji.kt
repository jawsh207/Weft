package app.weft.keyboard.emoji

import android.graphics.Paint

/**
 * Emoji are drawn with the system font — no EmojiCompat, no bundled font —
 * so they always match the user's device. [Paint.hasGlyph] filters out
 * anything the installed font can't render, so the picker never shows tofu.
 *
 * The real list comes from assets/emoji/emoji.txt (see [EmojiCatalog]). This
 * short starter set is only the fallback for a build that has no emoji asset,
 * so the picker is never empty.
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

    /** The starter emoji this phone's font can draw. */
    val starter: List<String> by lazy {
        val paint = Paint()
        STARTER.split(' ').filter { it.isNotBlank() && paint.hasGlyph(it) }
    }
}
