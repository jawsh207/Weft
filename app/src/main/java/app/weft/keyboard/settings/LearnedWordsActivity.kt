package app.weft.keyboard.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.weft.keyboard.R
import app.weft.keyboard.ime.LearnedStore
import app.weft.keyboard.theme.SystemTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings › Learned words: everything Weft has learned from typing, to look
 * through, edit and delete. Works on the same store the keyboard uses, so
 * changes apply to suggestions straight away.
 */
class LearnedWordsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            SystemTheme {
                LearnedWordsScreen(onBack = ::finish)
            }
        }
    }
}

private enum class LearnedTab(val title: Int, val empty: Int) {
    Words(R.string.learned_tab_words, R.string.learned_empty_words),
    Pairs(R.string.learned_tab_pairs, R.string.learned_empty_pairs),
    Kept(R.string.learned_tab_kept, R.string.learned_empty_kept),
    Removed(R.string.learned_tab_removed, R.string.learned_empty_removed),
}

/** One row on any tab: what it shows and what it was made from. */
private data class Item(val key: String, val title: String, val detail: String?, val first: String, val second: String = "")

/** What an add/edit dialog is editing. */
private data class Editing(val tab: LearnedTab, val row: Item?)

@Composable
private fun LearnedWordsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val learned = remember { LearnedStore.get(context) }
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var version by remember { mutableIntStateOf(0) }
    var loaded by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Editing?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { learned.ensureLoaded() }
        loaded = true
    }

    /** Apply a change, save it in the background, and redraw the lists. */
    fun change(block: () -> Unit) {
        block()
        version++
        scope.launch(Dispatchers.IO) { learned.save() }
    }

    val current = LearnedTab.entries[tab]
    val usesText = @Composable { n: Int -> pluralStringResource(R.plurals.learned_uses, n, n) }
    val rows: List<Item> = if (!loaded) emptyList() else {
        check(version >= 0) // reading it re-runs this after every change
        when (current) {
            LearnedTab.Words -> learned.newWords().map {
                Item(it.word, it.word, if (it.learned) usesText(it.uses) else stringResource(R.string.learned_word_pending), it.word)
            }
            LearnedTab.Pairs -> learned.wordPairs().map {
                Item("${it.first} ${it.second}", "${it.first} → ${it.second}", usesText(it.uses), it.first, it.second)
            }
            LearnedTab.Kept -> learned.keptAsTypedWords().map { Item(it, it, null, it) }
            LearnedTab.Removed -> learned.removedWords().map { Item(it, it, null, it) }
        }
    }
    val shown = if (query.isBlank()) rows else rows.filter { it.title.contains(query.trim(), ignoreCase = true) }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text(stringResource(R.string.learned_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.learned_back))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            if (current == LearnedTab.Words || current == LearnedTab.Pairs) {
                ExtendedFloatingActionButton(
                    onClick = { editing = Editing(current, null) },
                    icon = { Icon(painterResource(R.drawable.ic_add), null) },
                    text = {
                        Text(stringResource(if (current == LearnedTab.Words) R.string.learned_add_word else R.string.learned_add_pair))
                    },
                )
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
        ) {
            PrimaryTabRow(selectedTabIndex = tab) {
                LearnedTab.entries.forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(t.title), maxLines = 1) })
                }
            }
            Text(
                stringResource(
                    when (current) {
                        LearnedTab.Words -> R.string.learned_about_words
                        LearnedTab.Pairs -> R.string.learned_about_pairs
                        LearnedTab.Kept -> R.string.learned_about_kept
                        LearnedTab.Removed -> R.string.learned_about_removed
                    },
                ),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (rows.size > SEARCH_FROM) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    singleLine = true,
                    leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) },
                    placeholder = { Text(stringResource(R.string.learned_search)) },
                )
            }
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(if (loaded && rows.isEmpty()) current.empty else R.string.learned_no_matches),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                    items(shown, key = { it.key }) { row ->
                        LearnedRow(
                            row = row,
                            deleteLabel = stringResource(
                                when (current) {
                                    LearnedTab.Kept -> R.string.learned_allow_correcting
                                    LearnedTab.Removed -> R.string.learned_restore
                                    else -> R.string.learned_delete
                                },
                            ),
                            onEdit = { editing = Editing(current, row) },
                            onDelete = {
                                change {
                                    when (current) {
                                        LearnedTab.Words -> learned.deleteWord(row.first)
                                        LearnedTab.Pairs -> learned.deletePair(row.first, row.second)
                                        LearnedTab.Kept -> learned.stopKeepingAsTyped(row.first)
                                        LearnedTab.Removed -> learned.restore(row.first)
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    editing?.let { e ->
        EditDialog(
            editing = e,
            onDismiss = { editing = null },
            onSave = { first, second ->
                val row = e.row
                var ok = false
                change {
                    ok = when (e.tab) {
                        LearnedTab.Words -> if (row == null) learned.addWord(first) else learned.renameWord(row.first, first)
                        LearnedTab.Pairs -> if (row == null) learned.addPair(first, second) else learned.editPair(row.first, row.second, first, second)
                        LearnedTab.Kept -> learned.editKeptAsTyped(row!!.first, first)
                        LearnedTab.Removed -> learned.editRemoved(row!!.first, first)
                    }
                }
                if (ok) editing = null
                ok
            },
        )
    }
}

@Composable
private fun LearnedRow(row: Item, deleteLabel: String, onEdit: () -> Unit, onDelete: () -> Unit) {
    ListItem(
        headlineContent = { Text(row.title, style = MaterialTheme.typography.bodyLarge) },
        supportingContent = row.detail?.let { { Text(it) } },
        trailingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                IconButton(onClick = onEdit) {
                    Icon(painterResource(R.drawable.ic_edit), stringResource(R.string.learned_edit, row.title))
                }
                IconButton(onClick = onDelete) {
                    Icon(painterResource(R.drawable.ic_delete), "$deleteLabel: ${row.title}")
                }
            }
        },
    )
}

/** Add or edit: one word, or the two words of a pair. Returns false from [onSave] to keep it open. */
@Composable
private fun EditDialog(editing: Editing, onDismiss: () -> Unit, onSave: (String, String) -> Boolean) {
    val pair = editing.tab == LearnedTab.Pairs
    var first by remember { mutableStateOf(editing.row?.first.orEmpty()) }
    var second by remember { mutableStateOf(editing.row?.second.orEmpty()) }
    var invalid by remember { mutableStateOf(false) }
    val title = when {
        editing.row == null && pair -> R.string.learned_add_pair
        editing.row == null -> R.string.learned_add_word
        pair -> R.string.learned_edit_pair
        else -> R.string.learned_edit_word
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = first,
                    onValueChange = { first = it; invalid = false },
                    singleLine = true,
                    label = { Text(stringResource(if (pair) R.string.learned_first_word else R.string.learned_word)) },
                    isError = invalid,
                )
                if (pair) {
                    OutlinedTextField(
                        value = second,
                        onValueChange = { second = it; invalid = false },
                        singleLine = true,
                        label = { Text(stringResource(R.string.learned_next_word)) },
                        isError = invalid,
                    )
                }
                if (invalid) {
                    Text(
                        stringResource(R.string.learned_invalid),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { invalid = !onSave(first, second) }) { Text(stringResource(R.string.learned_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.suggestion_remove_cancel)) }
        },
    )
}

/** Show a search box once a list is longer than this. */
private const val SEARCH_FROM = 8
