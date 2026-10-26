package app.weft.keyboard.ui

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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

private val TabsHeight = 40.dp
private val CellSize = 46.dp

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

/** Category tabs, a scrolling grid with section headers, and the ABC/space/delete row. */
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
        Box(
            Modifier
                .fillMaxWidth()
                .height(RowHeight * 3 - TabsHeight),
            contentAlignment = Alignment.Center,
        ) {
            when {
                catalog == null -> LoadingIndicator(Modifier.size(40.dp))
                sections.isEmpty() -> Text(
                    stringResource(R.string.emoji_none),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(CellSize),
                    state = grid,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    sections.forEachIndexed { si, section ->
                        item(key = "h$si", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                            Text(
                                section.title,
                                modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 4.dp),
                                style = MaterialTheme.typography.labelLarge,
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
        EmojiTabs(
            sections = sections,
            current = current,
            onSearch = actions.onSearch,
            onSelect = { i -> scope.launch { grid.scrollToItem(starts[i]) } },
        )
        KeyRowView(Layouts.emojiBottom, state, handlers)
    }
}

@Composable
private fun EmojiTabs(
    sections: List<Section>,
    current: Int,
    onSearch: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .height(TabsHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val searchLabel = stringResource(R.string.emoji_search)
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(CircleShape)
                .clickable(onClick = onSearch)
                .semantics { contentDescription = searchLabel },
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_search), null, tint = cs.onSurfaceVariant)
        }
        sections.forEachIndexed { i, section ->
            val selected = i == current
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(horizontal = 1.dp, vertical = 3.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (selected) cs.secondaryContainer else Color.Transparent)
                    .clickable { onSelect(i) }
                    .semantics { contentDescription = section.title },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    section.icon,
                    fontSize = 18.sp,
                    modifier = Modifier.alpha(if (selected) 1f else 0.6f),
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
 * Search: a query box, a row of matches, and the letter keys, which type into
 * the query instead of the app. Enter or the emoji key returns to the grid.
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
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .padding(horizontal = KeyGap, vertical = 2.dp),
            shape = RoundedCornerShape(22.dp),
            color = cs.surfaceContainerHighest,
        ) {
            Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_search), null, tint = cs.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                if (query.isEmpty()) {
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
                // Caret, so it's clear the keys type here.
                Box(
                    Modifier
                        .padding(start = 2.dp)
                        .width(2.dp)
                        .height(22.dp)
                        .background(cs.primary),
                )
            }
        }
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
