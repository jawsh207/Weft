package app.weft.keyboard.ui

import android.view.HapticFeedbackConstants
import android.view.inputmethod.EditorInfo
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.weft.keyboard.R
import app.weft.keyboard.emoji.SystemEmoji
import app.weft.keyboard.ime.ChipStyle
import app.weft.keyboard.ime.KeyboardState
import app.weft.keyboard.ime.ShiftState
import app.weft.keyboard.ime.SuggestionChip
import app.weft.keyboard.layout.ActionKey
import app.weft.keyboard.layout.CharKey
import app.weft.keyboard.layout.Gap
import app.weft.keyboard.layout.Key
import app.weft.keyboard.layout.KeyAction
import app.weft.keyboard.layout.KeyRow
import app.weft.keyboard.layout.KeyboardPage
import app.weft.keyboard.layout.Layouts

private val RowHeight = 58.dp
private val KeyGap = 3.dp

/** Space between the bottom row and the system navigation bar / IME switcher. */
private val BottomClearance = 16.dp
private const val KEY_ROWS = 4

@Composable
fun KeyboardScreen(
    state: KeyboardState,
    onKey: (Key) -> Unit,
    onEmoji: (String) -> Unit,
    onMic: () -> Unit,
    onSuggestion: (SuggestionChip) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = KeyGap, end = KeyGap, top = KeyGap, bottom = BottomClearance),
    ) {
        TopStrip(state, onMic, onSuggestion)
        if (state.page == KeyboardPage.Emoji) {
            EmojiPanel(state, onKey, onEmoji)
        } else {
            Layouts.rowsFor(state.page).forEach { row -> KeyRowView(row, state, onKey) }
        }
    }
}

/** Suggestion strip plus the mic key. Voice status replaces suggestions while listening. */
@Composable
private fun TopStrip(
    state: KeyboardState,
    onMic: () -> Unit,
    onSuggestion: (SuggestionChip) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            when {
                state.listening -> Row(
                    Modifier.padding(start = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LoadingIndicator(Modifier.size(32.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.voice_listening),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                state.message != null -> Text(
                    state.message.orEmpty(),
                    modifier = Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.suggestions.isNotEmpty() -> SuggestionRow(state.suggestions, onSuggestion)
            }
        }
        if (state.voiceAvailable) {
            val description = stringResource(R.string.voice_input)
            if (state.listening) {
                FilledIconButton(onClick = onMic) {
                    Icon(painterResource(R.drawable.ic_mic), description)
                }
            } else {
                IconButton(onClick = onMic) {
                    Icon(
                        painterResource(R.drawable.ic_mic),
                        description,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Three equal slots; the center one is the best guess. */
@Composable
private fun SuggestionRow(chips: List<SuggestionChip?>, onSuggestion: (SuggestionChip) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until 3) {
            if (i > 0) {
                Box(
                    Modifier
                        .width(1.dp)
                        .height(20.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
            val chip = chips.getOrNull(i)
            Box(Modifier.weight(1f).height(40.dp), contentAlignment = Alignment.Center) {
                if (chip != null) SuggestionSlot(chip, onSuggestion)
            }
        }
    }
}

@Composable
private fun SuggestionSlot(chip: SuggestionChip, onSuggestion: (SuggestionChip) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val emphasized = chip.style == ChipStyle.Autocorrect
    Box(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 2.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(if (emphasized) cs.secondaryContainer else Color.Transparent)
            .clickable { onSuggestion(chip) }
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (chip.style == ChipStyle.Verbatim) "\u201C${chip.text}\u201D" else chip.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
            color = if (emphasized) cs.onSecondaryContainer else cs.onSurface,
        )
    }
}

@Composable
private fun KeyRowView(row: KeyRow, state: KeyboardState, onKey: (Key) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(RowHeight),
    ) {
        row.forEach { key -> KeySlot(key, state, onKey) }
    }
}

@Composable
private fun RowScope.KeySlot(key: Key, state: KeyboardState, onKey: (Key) -> Unit) {
    val slot = Modifier
        .weight(key.weight)
        .fillMaxHeight()
        .padding(KeyGap)
    when (key) {
        is Gap -> Spacer(slot)
        is CharKey -> {
            val label = if (state.shift != ShiftState.Off) key.label.uppercase() else key.label
            KeyButton(
                modifier = slot.semantics { contentDescription = label },
                style = KeyStyle.Letter,
                onTap = { onKey(key) },
            ) {
                Text(label, fontSize = 23.sp, color = it)
            }
        }
        is ActionKey -> ActionKeyView(key, state, slot, onKey)
    }
}

@Composable
private fun ActionKeyView(
    key: ActionKey,
    state: KeyboardState,
    modifier: Modifier,
    onKey: (Key) -> Unit,
) {
    val style = when {
        key.action == KeyAction.Enter -> KeyStyle.Primary
        key.action == KeyAction.Space -> KeyStyle.Letter
        key.action == KeyAction.Shift && state.shift != ShiftState.Off -> KeyStyle.Active
        else -> KeyStyle.Function
    }
    val description = when (key.action) {
        KeyAction.Shift -> stringResource(R.string.key_shift)
        KeyAction.Backspace -> stringResource(R.string.key_backspace)
        KeyAction.Enter -> stringResource(R.string.key_enter)
        KeyAction.Space -> stringResource(R.string.key_space)
        KeyAction.Emoji -> stringResource(R.string.key_emoji)
        else -> null
    }
    KeyButton(
        modifier = if (description != null) {
            modifier.semantics { contentDescription = description }
        } else {
            modifier
        },
        style = style,
        repeatable = key.action == KeyAction.Backspace,
        onTap = { onKey(key) },
    ) { tint ->
        when (key.action) {
            KeyAction.Shift -> KeyIcon(
                when (state.shift) {
                    ShiftState.Off -> R.drawable.ic_shift
                    ShiftState.Once -> R.drawable.ic_shift_on
                    ShiftState.Locked -> R.drawable.ic_shift_lock
                },
                tint,
            )
            KeyAction.Backspace -> KeyIcon(R.drawable.ic_backspace, tint)
            KeyAction.Enter -> KeyIcon(enterIcon(state.enterAction), tint)
            KeyAction.Emoji -> KeyIcon(R.drawable.ic_emoji, tint)
            KeyAction.Space -> Unit
            KeyAction.ToLetters -> KeyText("ABC", tint)
            KeyAction.ToSymbols -> KeyText("?123", tint)
            KeyAction.ToMoreSymbols -> KeyText("=\\<", tint)
        }
    }
}

@DrawableRes
private fun enterIcon(action: Int): Int = when (action) {
    EditorInfo.IME_ACTION_SEARCH -> R.drawable.ic_search
    EditorInfo.IME_ACTION_SEND -> R.drawable.ic_send
    EditorInfo.IME_ACTION_GO,
    EditorInfo.IME_ACTION_NEXT -> R.drawable.ic_arrow_forward
    EditorInfo.IME_ACTION_DONE -> R.drawable.ic_check
    else -> R.drawable.ic_enter
}

@Composable
private fun KeyIcon(@DrawableRes id: Int, tint: Color) {
    Icon(painterResource(id), contentDescription = null, tint = tint)
}

@Composable
private fun KeyText(text: String, tint: Color) {
    Text(text, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = tint)
}

// --- Emoji -------------------------------------------------------------------------------

@Composable
private fun EmojiPanel(state: KeyboardState, onKey: (Key) -> Unit, onEmoji: (String) -> Unit) {
    val emoji = remember { SystemEmoji.starter }
    Box(
        Modifier
            .fillMaxWidth()
            .height(RowHeight * (KEY_ROWS - 1)),
        contentAlignment = Alignment.Center,
    ) {
        if (emoji.isEmpty()) {
            Text(
                stringResource(R.string.emoji_none),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(46.dp),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(emoji) { e ->
                    KeyButton(
                        modifier = Modifier.aspectRatio(1f),
                        style = KeyStyle.Ghost,
                        onTap = { onEmoji(e) },
                    ) {
                        Text(e, fontSize = 26.sp) // system emoji font
                    }
                }
            }
        }
    }
    KeyRowView(Layouts.emojiBottom, state, onKey)
}

// --- Key button with Expressive press motion --------------------------------------

private enum class KeyStyle { Letter, Function, Active, Primary, Ghost }

private data class KeyColors(val container: Color, val content: Color, val pressed: Color)

@Composable
private fun keyColors(style: KeyStyle): KeyColors {
    val cs = MaterialTheme.colorScheme
    return when (style) {
        KeyStyle.Letter -> KeyColors(cs.surfaceContainerHighest, cs.onSurface, cs.primaryContainer)
        KeyStyle.Function -> KeyColors(cs.secondaryContainer, cs.onSecondaryContainer, cs.secondary)
        KeyStyle.Active -> KeyColors(cs.primaryContainer, cs.onPrimaryContainer, cs.primary)
        KeyStyle.Primary -> KeyColors(cs.primary, cs.onPrimary, cs.tertiary)
        KeyStyle.Ghost -> KeyColors(Color.Transparent, cs.onSurface, cs.surfaceContainerHighest)
    }
}

/**
 * A key that commits on release (so a finger can slide off to cancel), gives
 * system haptics on press, and morphs shape on press using the Expressive
 * motion scheme's spatial spring. [repeatable] keys auto-repeat while held.
 */
@Composable
private fun KeyButton(
    modifier: Modifier,
    style: KeyStyle,
    repeatable: Boolean = false,
    onTap: () -> Unit,
    content: @Composable (tint: Color) -> Unit,
) {
    val view = LocalView.current
    val currentOnTap by rememberUpdatedState(onTap)
    var pressed by remember { mutableStateOf(false) }

    val colors = keyColors(style)
    val motion = MaterialTheme.motionScheme
    val corner by animateDpAsState(
        targetValue = if (pressed) 24.dp else restCorner(style),
        animationSpec = motion.fastSpatialSpec(),
        label = "corner",
    )
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = motion.fastSpatialSpec(),
        label = "scale",
    )
    val container by animateColorAsState(
        targetValue = if (pressed) colors.pressed else colors.container,
        animationSpec = motion.fastEffectsSpec(),
        label = "container",
    )
    val tint = if (pressed && style == KeyStyle.Letter) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        colors.content
    }

    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(corner))
            .background(container)
            .pointerInput(repeatable) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

                    try {
                        if (repeatable) {
                            // Wait for a long-press; if still held, repeat until release.
                            var outcome = withTimeoutOrNull(REPEAT_DELAY_MS) {
                                Released(waitForUpOrCancellation() != null)
                            }
                            if (outcome == null) {
                                while (outcome == null) {
                                    currentOnTap()
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    outcome = withTimeoutOrNull(REPEAT_INTERVAL_MS) {
                                        Released(waitForUpOrCancellation() != null)
                                    }
                                }
                            } else if (outcome.normally) {
                                currentOnTap()
                            }
                        } else if (waitForUpOrCancellation() != null) {
                            currentOnTap()
                        }
                    } finally {
                        pressed = false // also runs if the keyboard hides mid-press
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        content(tint)
    }
}

private class Released(val normally: Boolean)

private fun restCorner(style: KeyStyle): Dp = when (style) {
    KeyStyle.Letter, KeyStyle.Ghost -> 12.dp
    KeyStyle.Function, KeyStyle.Active -> 16.dp
    KeyStyle.Primary -> 20.dp
}

private const val REPEAT_DELAY_MS = 400L
private const val REPEAT_INTERVAL_MS = 50L
