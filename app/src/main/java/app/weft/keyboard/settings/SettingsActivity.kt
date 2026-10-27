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
import java.io.File
import app.weft.keyboard.R
import app.weft.keyboard.predict.LearnedWords
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
                SetupScreen(requestMicOnLaunch = requestMic)
            }
        }
    }

    companion object {
        /** Set by the keyboard when the mic key is tapped without permission. */
        const val EXTRA_REQUEST_MIC = "request_mic"
    }
}

@Composable
private fun SetupScreen(requestMicOnLaunch: Boolean) {
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
                title = { Text(stringResource(R.string.setup_title)) },
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
            StepCard(
                number = 1,
                title = stringResource(R.string.setup_enable_title),
                body = stringResource(R.string.setup_enable_body),
                done = enabled,
                actionLabel = stringResource(R.string.setup_enable_action),
                onAction = {
                    context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                },
            )
            StepCard(
                number = 2,
                title = stringResource(R.string.setup_select_title),
                body = stringResource(R.string.setup_select_body),
                done = selected,
                actionEnabled = enabled,
                actionLabel = stringResource(R.string.setup_select_action),
                onAction = { imm.showInputMethodPicker() },
            )
            StepCard(
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

            TypingSettings()
            KeysSettings()
            DictionaryInfo()
            SectionCard(stringResource(R.string.settings_emoji_title)) {
                Text(
                    stringResource(R.string.settings_emoji_body),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

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
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            Text(
                title,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            content()
        }
    }
}

@Composable
private fun TypingSettings() {
    val context = LocalContext.current
    val prefs = remember { Prefs.of(context) }
    var suggestions by remember { mutableStateOf(prefs.getBoolean(Prefs.SHOW_SUGGESTIONS, true)) }
    var autocorrect by remember { mutableStateOf(prefs.getBoolean(Prefs.AUTOCORRECT, true)) }
    var learn by remember { mutableStateOf(prefs.getBoolean(Prefs.LEARN_WORDS, true)) }
    var swipe by remember { mutableStateOf(prefs.getBoolean(Prefs.SWIPE, true)) }
    val learnedFile = remember { File(context.filesDir, LearnedWords.WORDS_FILE) }
    val historyFile = remember { File(context.filesDir, LearnedWords.HISTORY_FILE) }
    var learnedCount by remember { mutableIntStateOf(countLines(learnedFile)) }
    var pairCount by remember { mutableIntStateOf(countLines(historyFile, prefix = "p\t")) }

    SectionCard(stringResource(R.string.settings_typing_title)) {
        SwitchRow(stringResource(R.string.settings_suggestions), stringResource(R.string.settings_suggestions_body), suggestions) {
            suggestions = it
            prefs.edit { putBoolean(Prefs.SHOW_SUGGESTIONS, it) }
        }
        SwitchRow(stringResource(R.string.settings_autocorrect), stringResource(R.string.settings_autocorrect_body), autocorrect, enabled = suggestions) {
            autocorrect = it
            prefs.edit { putBoolean(Prefs.AUTOCORRECT, it) }
        }
        SwitchRow(stringResource(R.string.settings_swipe), stringResource(R.string.settings_swipe_body), swipe) {
            swipe = it
            prefs.edit { putBoolean(Prefs.SWIPE, it) }
        }
        SwitchRow(stringResource(R.string.settings_learn), stringResource(R.string.settings_learn_body), learn) {
            learn = it
            prefs.edit { putBoolean(Prefs.LEARN_WORDS, it) }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.settings_learned_count, learnedCount, pairCount),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = {
                    learnedFile.delete()
                    historyFile.delete()
                    prefs.edit { putInt(Prefs.LEARNED_RESET, prefs.getInt(Prefs.LEARNED_RESET, 0) + 1) }
                    learnedCount = 0
                    pairCount = 0
                },
                enabled = learnedCount > 0 || historyFile.exists(),
            ) { Text(stringResource(R.string.settings_clear_learned)) }
        }
    }
}

@Composable
private fun KeysSettings() {
    val context = LocalContext.current
    val prefs = remember { Prefs.of(context) }
    var haptics by remember { mutableStateOf(prefs.haptics()) }
    var height by remember { mutableStateOf(prefs.keyHeight()) }
    var numberRow by remember { mutableStateOf(prefs.numberRow()) }
    var offline by remember { mutableStateOf(prefs.voiceOffline()) }

    SectionCard(stringResource(R.string.settings_keys_title)) {
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
        SwitchRow(stringResource(R.string.settings_voice_offline), stringResource(R.string.settings_voice_offline_body), offline) {
            offline = it
            prefs.edit { putBoolean(Prefs.VOICE_OFFLINE, it) }
        }
    }
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
    val meta = remember {
        runCatching { JSONObject(DictionaryLoader.load(context, "en_US")!!.meta) }.getOrNull()
    }
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

private fun countLines(file: File, prefix: String = ""): Int =
    if (file.exists()) file.useLines { lines -> lines.count { it.isNotBlank() && it.startsWith(prefix) } } else 0

private fun Context.hasMic(): Boolean =
    checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
