package app.weft.keyboard.settings

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.weft.keyboard.R

/** The sections of settings, each on its own page. */
enum class SettingsPage(
    @StringRes val title: Int,
    @StringRes val summary: Int,
    @DrawableRes val icon: Int,
) {
    Typing(R.string.settings_page_typing, R.string.settings_page_typing_summary, R.drawable.ic_keyboard),
    Swipe(R.string.settings_page_swipe, R.string.settings_page_swipe_summary, R.drawable.ic_gesture),
    Learning(R.string.settings_page_learning, R.string.settings_page_learning_summary, R.drawable.ic_history),
    Keys(R.string.settings_page_keys, R.string.settings_page_keys_summary, R.drawable.ic_tune),
    Voice(R.string.settings_page_voice, R.string.settings_page_voice_summary, R.drawable.ic_mic),
    About(R.string.settings_page_about, R.string.settings_page_about_summary, R.drawable.ic_info),
}

/** The list of sections on the main settings page. */
@Composable
internal fun SettingsMenu(onOpen: (SettingsPage) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        SettingsPage.entries.forEachIndexed { i, page ->
            if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
            ListItem(
                modifier = Modifier.clickable { onOpen(page) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent = {
                    Icon(painterResource(page.icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                },
                headlineContent = { Text(stringResource(page.title), style = MaterialTheme.typography.titleMedium) },
                supportingContent = { Text(stringResource(page.summary)) },
                trailingContent = { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null) },
            )
        }
    }
}

/** One section of settings, with a back arrow to the main page. */
@Composable
internal fun SettingsPageScreen(page: SettingsPage, onBack: () -> Unit) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text(stringResource(page.title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.learned_back))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (page) {
                SettingsPage.Typing -> TypingSection()
                SettingsPage.Swipe -> SwipeSection()
                SettingsPage.Learning -> LearningSection()
                SettingsPage.Keys -> KeysSection()
                SettingsPage.Voice -> VoiceSection()
                SettingsPage.About -> AboutSection()
            }
        }
    }
}
