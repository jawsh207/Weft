package app.weft.keyboard.settings

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.speech.SpeechRecognizer
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import app.weft.keyboard.ime.LearnedStore
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import app.weft.keyboard.ui.HapticLevel
import app.weft.keyboard.ui.KeyHeight
import androidx.compose.ui.semantics.Role
import androidx.core.content.edit
import app.weft.keyboard.ime.DictionaryLoader
import org.json.JSONObject
import app.weft.keyboard.R
import app.weft.keyboard.ime.WeftKeyboardService
import app.weft.keyboard.theme.SystemTheme

/** Launcher screen: walks the user through enabling the keyboard and voice input. */
class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val requestMic = intent.getBooleanExtra(EXTRA_REQUEST_MIC, false)
        setContent {
            SystemTheme {
                // The main page, or one section of settings opened from it.
                var page by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
                BackHandler(enabled = page != null) { page = null }
                AnimatedContent(
                    targetState = page,
                    transitionSpec = {
                        val forward = targetState != null
                        (slideInHorizontally { if (forward) it / 4 else -it / 4 } + fadeIn()) togetherWith
                            (slideOutHorizontally { if (forward) -it / 4 else it / 4 } + fadeOut())
                    },
                    label = "settings",
                ) { current ->
                    if (current == null) {
                        SetupScreen(requestMicOnLaunch = requestMic, onOpen = { page = it })
                    } else {
                        SettingsPageScreen(current, onBack = { page = null })
                    }
                }
            }
        }
    }

    companion object {
        /** Set by the keyboard when the mic key is tapped without permission. */
        const val EXTRA_REQUEST_MIC = "request_mic"
    }
}

@Composable
private fun SetupScreen(requestMicOnLaunch: Boolean, onOpen: (SettingsPage) -> Unit) {
    val context = LocalContext.current
    val imm = remember { context.getSystemService(InputMethodManager::class.java) }
    val imeId = remember {
        ComponentName(context, WeftKeyboardService::class.java).flattenToShortString()
    }

    // Re-check status whenever we come back from system settings or the picker closes.
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose { }
    }
    val windowFocused = LocalWindowInfo.current.isWindowFocused

    val enabled = remember(refresh, windowFocused) {
        imm.enabledInputMethodList.any { it.id == imeId }
    }
    val selected = remember(refresh, windowFocused) {
        Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD,
        ) == imeId
    }
    val engineAvailable = remember(refresh) { SpeechRecognizer.isRecognitionAvailable(context) }
    var micGranted by remember { mutableStateOf(context.hasMic()) }
    LaunchedEffect(refresh) { micGranted = context.hasMic() }

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> micGranted = granted }

    LaunchedEffect(requestMicOnLaunch) {
        if (requestMicOnLaunch && !context.hasMic()) {
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                // Shrink above the keyboard so the test field never sits behind it;
                // otherwise every keystroke scrolls it back into view and the page jumps.
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Setup steps stay at the top until they're all done.
            val allSet = enabled && selected && (micGranted || !engineAvailable)
            if (!allSet) StepCard(
                number = 1,
                title = stringResource(R.string.setup_enable_title),
                body = stringResource(R.string.setup_enable_body),
                done = enabled,
                actionLabel = stringResource(R.string.setup_enable_action),
                onAction = {
                    context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                },
            )
            if (!allSet) StepCard(
                number = 2,
                title = stringResource(R.string.setup_select_title),
                body = stringResource(R.string.setup_select_body),
                done = selected,
                actionEnabled = enabled,
                actionLabel = stringResource(R.string.setup_select_action),
                onAction = { imm.showInputMethodPicker() },
            )
            if (!allSet) StepCard(
                number = 3,
                title = stringResource(R.string.setup_mic_title),
                body = stringResource(
                    if (engineAvailable) R.string.setup_mic_body else R.string.setup_mic_no_engine,
                ),
                done = micGranted,
                actionEnabled = engineAvailable,
                actionLabel = stringResource(R.string.setup_mic_action),
                onAction = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
            )

            SettingsMenu(onOpen)

            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.setup_try_title),
                style = MaterialTheme.typography.titleMedium,
            )
            var text by remember { mutableStateOf("") }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.setup_try_hint)) },
                minLines = 3,
            )
        }
    }
}

@Composable
private fun StepCard(
    number: Int,
    title: String,
    body: String,
    done: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
    actionEnabled: Boolean = true,
) {
    val cs = MaterialTheme.colorScheme
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (done) cs.secondaryContainer else cs.surfaceContainerHigh,
        ),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = if (done) cs.primary else cs.surfaceContainerHighest,
                contentColor = if (done) cs.onPrimary else cs.onSurface,
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (done) {
                        Icon(painterResource(R.drawable.ic_check), contentDescription = null)
                    } else {
                        Text(number.toString(), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                if (!done) {
                    Spacer(Modifier.height(4.dp))
                    Button(onClick = onAction, enabled = actionEnabled) { Text(actionLabel) }
                } else {
                    Text(
                        stringResource(R.string.setup_done),
                        style = MaterialTheme.typography.labelLarge,
                        color = cs.primary,
                    )
                }
            }
        }
    }
}

@Composable
internal fun SectionCard(title: String?, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            if (title != null) {
                Text(
                    title,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            content()
        }
    }
}

/** Settings › Typing: what the suggestion strip does. */
@Composable
internal fun TypingSection() {
    val context = LocalContext.current
    val prefs = remember { Prefs.of(context) }
    var suggestions by remember { mutableStateOf(prefs.getBoolean(Prefs.SHOW_SUGGESTIONS, true)) }
    var autocorrect by remember { mutableStateOf(prefs.getBoolean(Prefs.AUTOCORRECT, true)) }
    var emoji by remember { mutableStateOf(prefs.emojiSuggestions()) }
    SectionCard(null) {
        SwitchRow(stringResource(R.string.settings_suggestions), stringResource(R.string.settings_suggestions_body), suggestions) {
            suggestions = it
            prefs.edit { putBoolean(Prefs.SHOW_SUGGESTIONS, it) }
        }
        SwitchRow(stringResource(R.string.settings_autocorrect), stringResource(R.string.settings_autocorrect_body), autocorrect, enabled = suggestions) {
            autocorrect = it
            prefs.edit { putBoolean(Prefs.AUTOCORRECT, it) }
        }
        SwitchRow(stringResource(R.string.settings_emoji_suggestions), stringResource(R.string.settings_emoji_suggestions_body), emoji, enabled = suggestions) {
            emoji = it
            prefs.edit { putBoolean(Prefs.EMOJI_SUGGESTIONS, it) }
        }
    }
}

/** Settings › Swipe typing. */
@Composable
internal fun SwipeSection() {
    val context = LocalContext.current
    val prefs = remember { Prefs.of(context) }
    var swipe by remember { mutableStateOf(prefs.getBoolean(Prefs.SWIPE, true)) }
    var visuals by remember { mutableStateOf(prefs.swipeVisuals()) }
    SectionCard(null) {
        SwitchRow(stringResource(R.string.settings_swipe), stringResource(R.string.settings_swipe_body), swipe) {
            swipe = it
            prefs.edit { putBoolean(Prefs.SWIPE, it) }
        }
        SwitchRow(stringResource(R.string.settings_swipe_visuals), stringResource(R.string.settings_swipe_visuals_body), visuals, enabled = swipe) {
            visuals = it
            prefs.edit { putBoolean(Prefs.SWIPE_VISUALS, it) }
        }
    }
    Note(stringResource(R.string.settings_swipe_learning_note))
}

/** Settings › Learning: what Weft learns from typing, and managing it. */
@Composable
internal fun LearningSection() {
    val context = LocalContext.current
    val prefs = remember { Prefs.of(context) }
    var learn by remember { mutableStateOf(prefs.getBoolean(Prefs.LEARN_WORDS, true)) }
    val learned = remember { LearnedStore.get(context) }
    val scope = rememberCoroutineScope()
    var learnedCount by remember { mutableIntStateOf(0) }
    var pairCount by remember { mutableIntStateOf(0) }
    var hasAny by remember { mutableStateOf(false) }
    // Counts refresh whenever the screen comes back (e.g. from Learned words).
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose { }
    }
    LaunchedEffect(refresh) {
        withContext(Dispatchers.IO) { learned.ensureLoaded() }
        learnedCount = learned.size
        pairCount = learned.pairCount
        hasAny = learned.size > 0 || learned.pairCount > 0 || learned.swipeSampleCount > 0 ||
            learned.keptAsTypedWords().isNotEmpty() || learned.removedWords().isNotEmpty()
    }
    SectionCard(null) {
        SwitchRow(stringResource(R.string.settings_learn), stringResource(R.string.settings_learn_body), learn) {
            learn = it
            prefs.edit { putBoolean(Prefs.LEARN_WORDS, it) }
        }
        Text(
            stringResource(R.string.settings_learned_count, learnedCount, pairCount),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            OutlinedButton(
                onClick = {
                    learned.clear()
                    scope.launch(Dispatchers.IO) { learned.save() }
                    learnedCount = 0
                    pairCount = 0
                    hasAny = false
                },
                enabled = hasAny,
            ) { Text(stringResource(R.string.settings_clear_learned)) }
            Button(onClick = { context.startActivity(Intent(context, LearnedWordsActivity::class.java)) }) {
                Text(stringResource(R.string.settings_manage_learned))
            }
        }
    }
}

/** Settings › Keys and feel. */
@Composable
internal fun KeysSection() {
    val context = LocalContext.current
    val prefs = remember { Prefs.of(context) }
    var haptics by remember { mutableStateOf(prefs.haptics()) }
    var height by remember { mutableStateOf(prefs.keyHeight()) }
    var numberRow by remember { mutableStateOf(prefs.numberRow()) }
    SectionCard(null) {
        ChoiceRow(
            title = stringResource(R.string.settings_haptics),
            options = HapticLevel.entries,
            selected = haptics,
            label = {
                stringResource(
                    when (it) {
                        HapticLevel.Off -> R.string.settings_haptics_off
                        HapticLevel.Light -> R.string.settings_haptics_light
                        HapticLevel.Normal -> R.string.settings_haptics_normal
                        HapticLevel.Strong -> R.string.settings_haptics_strong
                    },
                )
            },
        ) {
            haptics = it
            prefs.edit { putString(Prefs.HAPTICS, it.key) }
        }
        ChoiceRow(
            title = stringResource(R.string.settings_key_height),
            options = KeyHeight.entries,
            selected = height,
            label = {
                stringResource(
                    when (it) {
                        KeyHeight.Compact -> R.string.settings_key_height_compact
                        KeyHeight.Normal -> R.string.settings_key_height_normal
                        KeyHeight.Tall -> R.string.settings_key_height_tall
                    },
                )
            },
        ) {
            height = it
            prefs.edit { putString(Prefs.KEY_HEIGHT, it.key) }
        }
        SwitchRow(stringResource(R.string.settings_number_row), stringResource(R.string.settings_number_row_body), numberRow) {
            numberRow = it
            prefs.edit { putBoolean(Prefs.NUMBER_ROW, it) }
        }
    }
}

/** Settings › Voice input. */
@Composable
internal fun VoiceSection() {
    val context = LocalContext.current
    val prefs = remember { Prefs.of(context) }
    var offline by remember { mutableStateOf(prefs.voiceOffline()) }
    SectionCard(null) {
        SwitchRow(stringResource(R.string.settings_voice_offline), stringResource(R.string.settings_voice_offline_body), offline) {
            offline = it
            prefs.edit { putBoolean(Prefs.VOICE_OFFLINE, it) }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            OutlinedButton(
                onClick = {
                    // The system's "Voice input" / default speech engine screen; not every phone has it.
                    runCatching { context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
                        .recoverCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
                },
            ) { Text(stringResource(R.string.settings_voice_engine)) }
        }
    }
    Note(stringResource(R.string.settings_voice_note))
}

/** Settings › About: where the dictionary and emoji data come from. */
@Composable
internal fun AboutSection() {
    DictionaryInfo()
    SectionCard(stringResource(R.string.settings_emoji_title)) {
        Text(
            stringResource(R.string.settings_emoji_body),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A short explanation under a group of settings. */
@Composable
private fun Note(text: String) {
    Text(
        text,
        modifier = Modifier.padding(horizontal = 8.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A title over a row of connected buttons, one of which is chosen. */
@Composable
private fun <T> ChoiceRow(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, option ->
                SegmentedButton(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                ) { Text(label(option), maxLines = 1) }
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    body: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** Shows what the bundled dictionary was built from: required attribution for CC BY-SA data. */
@Composable
private fun DictionaryInfo() {
    val context = LocalContext.current
    // The dictionary for the keyboard language in use (US English if Weft isn't the current keyboard).
    val metaState = produceState<JSONObject?>(null) {
        value = withContext(Dispatchers.IO) {
            val imm = context.getSystemService(InputMethodManager::class.java)
            val tag = imm?.currentInputMethodSubtype?.languageTag?.takeIf {
                it.isNotEmpty() && isWeftCurrent(context)
            }
            val locale = tag?.replace('-', '_') ?: "en_US"
            runCatching { JSONObject(DictionaryLoader.load(context, locale)!!.meta) }.getOrNull()
        }
    }
    val meta = metaState.value
    SectionCard(stringResource(R.string.settings_dictionary_title)) {
        Column(
            Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (meta == null) {
                Text(
                    stringResource(R.string.settings_dictionary_missing),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            Text(
                stringResource(
                    R.string.settings_dictionary_words,
                    meta.optString("description"),
                    meta.optInt("wordCount"),
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                stringResource(R.string.settings_dictionary_sources),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val sources = meta.optJSONArray("sources")
            for (i in 0 until (sources?.length() ?: 0)) {
                val src = sources!!.getJSONObject(i)
                Text(
                    "• ${src.optString("name")} (${src.optString("license")})",
                    style = MaterialTheme.typography.bodyMedium,
                )
                val url = src.optString("url")
                if (url.isNotEmpty()) {
                    Text(
                        url,
                        modifier = Modifier.padding(start = 12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}


private fun Context.hasMic(): Boolean =
    checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

/** True if Weft is the keyboard currently selected in system settings. */
private fun isWeftCurrent(context: Context): Boolean =
    Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ==
        ComponentName(context, WeftKeyboardService::class.java).flattenToShortString()
