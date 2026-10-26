package app.weft.keyboard.ui

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.weft.keyboard.R
import app.weft.keyboard.emoji.Emoji
import app.weft.keyboard.emoji.EmojiCatalog
import app.weft.keyboard.ime.KeyboardState
import app.weft.keyboard.layout.ActionKey
import app.weft.keyboard.layout.KeyAction
import app.weft.keyboard.layout.Layouts
import kotlinx.coroutines.launch

/** What the emoji screens can ask the keyboard to do. */
class EmojiActions(
    /** Type an emoji (already in its chosen skin tone). */
    val onEmoji: (String) -> Unit,
    /** Type [chosen] from [base]'s skin-tone popup and remember it as the default. */
    val onVariant: (base: String, chosen: String) -> Unit,
    /** Open search, where the letter keys type into the search box. */
    val onSearch: () -> Unit,
)

private val CellSize = 46.dp
private val TabWidth = 44.dp

/** A section of the grid: recently used, or one Unicode group. */
private class Section(val title: String, val icon: String, val items: List<Cell>)

/** [text] is what's shown and typed; [emoji] gives the skin-tone options, if any. */
private class Cell(val text: String, val emoji: Emoji?)

private val GROUP_ICONS = mapOf(
    "Smileys & Emotion" to "😀",
    "People & Body" to "👋",
    "Animals & Nature" to "🐻",
    "Food & Drink" to "🍔",
    "Travel & Places" to "🚗",
    "Activities" to "⚽",
    "Objects" to "💡",
    "Symbols" to "🔣",
    "Flags" to "🏁",
)

/** Draws colour emoji in grey, for calm, icon-like category tabs. */
private val Monochrome = RenderEffect.createColorFilterEffect(
    ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }),
).asComposeRenderEffect()

private fun sectionsFor(
    catalog: EmojiCatalog,
    recents: List<String>,
    tones: Map<String, String>,
    recentTitle: String,
): List<Section> {
    val out = ArrayList<Section>()
    if (recents.isNotEmpty()) {
        out += Section(recentTitle, "🕘", recents.map { Cell(it, catalog.find(it)) })
    }
    for (group in catalog.groups) {
        out += Section(
            group.name,
            GROUP_ICONS[group.name] ?: group.emoji.first().base,
            group.emoji.map { Cell(tones[it.base] ?: it.base, it) },
        )
    }
    return out
}

/**
 * The search box in the suggestion strip on the emoji page. Tapping it starts
 * a search; while searching it shows the query with a caret.
 */
@Composable
internal fun EmojiSearchPill(query: String?, onSearch: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val label = stringResource(R.string.emoji_search)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .padding(end = 8.dp)
            .semantics { contentDescription = label },
        shape = RoundedCornerShape(20.dp),
        color = cs.surfaceContainerHighest,
        onClick = onSearch,
    ) {
        Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painterResource(R.drawable.ic_search),
                contentDescription = null,
                tint = cs.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            if (query.isNullOrEmpty()) {
                Text(
                    stringResource(R.string.emoji_search_hint),
                    style = MaterialTheme.typography.bodyLarge,
                    color = cs.onSurfaceVariant,
                )
            } else {
                Text(
                    query,
                    style = MaterialTheme.typography.bodyLarge,
                    color = cs.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            if (query != null) {
                // Caret, so it's clear the keys type here.
                Box(
                    Modifier
                        .padding(start = 2.dp)
                        .width(2.dp)
                        .height(20.dp)
                        .background(cs.primary),
                )
            }
        }
    }
}

/** The emoji grid in a rounded panel, then ABC · category tabs · delete. */
@Composable
internal fun EmojiPicker(state: KeyboardState, handlers: KeyHandlers, actions: EmojiActions) {
    val catalog = state.emojiCatalog
    val recentTitle = stringResource(R.string.emoji_recent)
    val sections = remember(catalog, state.emojiRecents, state.emojiTones, recentTitle) {
        if (catalog == null) emptyList() else sectionsFor(catalog, state.emojiRecents, state.emojiTones, recentTitle)
    }
    // Grid index where each section's header sits.
    val starts = remember(sections) {
        var i = 0
        sections.map { s -> i.also { i += 1 + s.items.size } }
    }
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val current by remember(starts) {
        derivedStateOf { starts.indexOfLast { it <= grid.firstVisibleItemIndex }.coerceAtLeast(0) }
    }

    Column {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(RowHeight * 3)
                .padding(KeyGap),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Box(contentAlignment = Alignment.Center) {
                when {
                    catalog == null -> LoadingIndicator(Modifier.size(40.dp))
                    sections.isEmpty() -> Text(
                        stringResource(R.string.emoji_none),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(CellSize),
                        state = grid,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 4.dp),
                    ) {
                        sections.forEachIndexed { si, section ->
                            item(key = "h$si", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                                Text(
                                    section.title,
                                    modifier = Modifier.padding(start = 8.dp, top = 10.dp, bottom = 4.dp),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            itemsIndexed(
                                section.items,
                                key = { i, cell -> "$si:$i:${cell.text}" },
                                contentType = { _, _ -> "emoji" },
                            ) { _, cell ->
                                EmojiCell(cell, handlers, actions, Modifier.aspectRatio(1f))
                            }
                        }
                    }
                }
            }
        }
        // ABC · tabs · delete, like most keyboards' emoji pages. No space bar here.
        Row(
            Modifier
                .fillMaxWidth()
                .height(RowHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KeySlot(ActionKey(KeyAction.ToLetters, 1.5f), state, handlers)
            EmojiTabs(
                sections = sections,
                current = current,
                onSelect = { i -> scope.launch { grid.scrollToItem(starts[i]) } },
                modifier = Modifier.weight(7f),
            )
            KeySlot(ActionKey(KeyAction.Backspace, 1.5f), state, handlers)
        }
    }
}

@Composable
private fun EmojiTabs(
    sections: List<Section>,
    current: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val tabs = rememberLazyListState()
    // Keep the highlighted tab in view as the grid scrolls.
    LaunchedEffect(current) {
        val visible = tabs.layoutInfo.visibleItemsInfo
        val shown = visible.any { it.index == current && it.offset >= 0 &&
            it.offset + it.size <= tabs.layoutInfo.viewportEndOffset }
        if (!shown && sections.isNotEmpty()) tabs.animateScrollToItem((current - 1).coerceAtLeast(0))
    }
    LazyRow(
        state = tabs,
        modifier = modifier.fillMaxHeight(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(sections, key = { i, s -> "$i:${s.title}" }) { i, section ->
            val selected = i == current
            Box(
                Modifier
                    .size(TabWidth)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(if (selected) cs.secondaryContainer else Color.Transparent)
                    .clickable { onSelect(i) }
                    .semantics { contentDescription = section.title },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    section.icon,
                    fontSize = 20.sp,
                    modifier = Modifier.graphicsLayer {
                        renderEffect = Monochrome
                        alpha = if (selected) 1f else 0.6f
                    },
                )
            }
        }
    }
}

/** One emoji. Long-press opens its skin tones, if it has any. */
@Composable
private fun EmojiCell(cell: Cell, handlers: KeyHandlers, actions: EmojiActions, modifier: Modifier) {
    val emoji = cell.emoji
    val gesture = if (emoji != null && emoji.variants.isNotEmpty()) {
        KeyGesture.Alternates(listOf(emoji.base) + emoji.variants) { actions.onVariant(emoji.base, it) }
    } else {
        KeyGesture.Tap
    }
    KeyButton(
        modifier = modifier.semantics { contentDescription = emoji?.name ?: cell.text },
        style = KeyStyle.Ghost,
        gesture = gesture,
        handlers = handlers,
        onTap = { actions.onEmoji(cell.text) },
    ) {
        Text(cell.text, fontSize = 26.sp) // the phone's own emoji font
    }
}

/**
 * Search: the query shows in the suggestion strip's search box; below it a row
 * of matches and the letter keys, which type into the query instead of the app.
 * Enter or the emoji key returns to the grid.
 */
@Composable
internal fun EmojiSearch(state: KeyboardState, handlers: KeyHandlers, actions: EmojiActions) {
    val cs = MaterialTheme.colorScheme
    val query = state.emojiQuery.orEmpty()
    val catalog = state.emojiCatalog
    val results = remember(catalog, query, state.emojiTones, state.emojiRecents) {
        when {
            catalog == null -> emptyList()
            query.isBlank() -> state.emojiRecents.map { Cell(it, catalog.find(it)) }
            else -> catalog.search(query).map { Cell(state.emojiTones[it.base] ?: it.base, it) }
        }
    }

    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .height(RowHeight),
            contentAlignment = Alignment.Center,
        ) {
            if (results.isEmpty() && query.isNotBlank()) {
                Text(stringResource(R.string.emoji_no_results), color = cs.onSurfaceVariant)
            } else {
                LazyRow(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Start) {
                    itemsIndexed(results, key = { i, cell -> "$i:${cell.text}" }) { _, cell ->
                        EmojiCell(cell, handlers, actions, Modifier.size(RowHeight))
                    }
                }
            }
        }
        Layouts.qwerty.take(3).forEach { row -> KeyRowView(row, state, handlers) }
        KeyRowView(Layouts.emojiSearchBottom, state, handlers)
    }
}
