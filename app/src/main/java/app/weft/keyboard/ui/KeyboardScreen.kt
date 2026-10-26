package app.weft.keyboard.ui

import android.view.HapticFeedbackConstants
import android.view.inputmethod.EditorInfo
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.weft.keyboard.R
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
import kotlin.math.abs
import kotlin.math.sign

internal val RowHeight = 58.dp
internal val KeyGap = 3.dp

/** Space between the bottom row and the system navigation bar / IME switcher. */
private val BottomClearance = 18.dp
private const val KEY_ROWS = 4

/** Horizontal travel on the space bar per one-character cursor step. */
private val CursorStep = 12.dp

/** The tools row that slides over the suggestions. */
class ToolActions(
    val onToggle: () -> Unit,
    val onPaste: () -> Unit,
    val onClearClipboard: () -> Unit,
    val onSettings: () -> Unit,
)

/** What keys can do besides being tapped. */
internal class KeyHandlers(
    val onKey: (Key) -> Unit,
    val onCursor: (Int) -> Unit,
    val popup: AlternatesPopup,
    /** Swipe points in key units; done = finger lifted (commit), otherwise a live preview. */
    val onSwipe: (xs: FloatArray, ys: FloatArray, done: Boolean) -> Unit,
)

@Composable
fun KeyboardScreen(
    state: KeyboardState,
    onKey: (Key) -> Unit,
    emoji: EmojiActions,
    tools: ToolActions,
    onMic: () -> Unit,
    onSuggestion: (SuggestionChip) -> Unit,
    onAlternate: (String) -> Unit,
    onCursor: (Int) -> Unit,
    onSwipe: (FloatArray, FloatArray, Boolean) -> Unit,
) {
    val popup = remember { AlternatesPopup() }
    val currentOnAlternate by rememberUpdatedState(onAlternate)
    popup.onPick = { currentOnAlternate(it) }
    val handlers = KeyHandlers(onKey, onCursor, popup, onSwipe)

    // The Box is the coordinate root for the long-press popup.
    Box(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { popup.rootWidth = it.size.width.toFloat() },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = KeyGap, end = KeyGap, top = KeyGap, bottom = BottomClearance),
        ) {
            TopStrip(state, onMic, onSuggestion, emoji, tools)
            if (state.page == KeyboardPage.Emoji) {
                if (state.emojiQuery != null) {
                    EmojiSearch(state, handlers, emoji)
                } else {
                    EmojiPicker(state, handlers, emoji)
                }
            } else {
                val rows = Layouts.rowsFor(state.page)
                if (state.page == KeyboardPage.Letters && state.swipeEnabled) {
                    SwipeArea(rows, state, handlers)
                } else {
                    rows.forEach { row -> KeyRowView(row, state, handlers) }
                }
            }
        }
        AlternatesOverlay(popup)
    }
}

/**
 * Suggestion strip with the tools key on the left and the mic key on the right.
 * The tools key slides a row (paste, clear clipboard, settings) over the
 * suggestions. Voice status replaces suggestions while listening; on the emoji
 * page the strip holds the emoji search box instead.
 */
@Composable
private fun TopStrip(
    state: KeyboardState,
    onMic: () -> Unit,
    onSuggestion: (SuggestionChip) -> Unit,
    emoji: EmojiActions,
    tools: ToolActions,
) {
    val showTools = !state.listening && state.page != KeyboardPage.Emoji
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showTools) ToolsKey(state.toolsOpen, tools.onToggle)
        Box(Modifier.weight(1f).clip(RoundedCornerShape(20.dp)), contentAlignment = Alignment.CenterStart) {
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
                state.page == KeyboardPage.Emoji -> EmojiSearchPill(state.emojiQuery, emoji.onSearch)
                state.suggestions.isNotEmpty() -> SuggestionRow(state.suggestions, onSuggestion)
            }
            val motion = MaterialTheme.motionScheme
            AnimatedVisibility(
                visible = showTools && state.toolsOpen,
                enter = slideInHorizontally(motion.fastSpatialSpec()) { -it } + fadeIn(motion.fastEffectsSpec()),
                exit = slideOutHorizontally(motion.fastSpatialSpec()) { -it } + fadeOut(motion.fastEffectsSpec()),
            ) {
                ToolsRow(state.clipboardHasContent, tools)
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

/** Chevron that turns to point back once the tools row is out. */
@Composable
private fun ToolsKey(open: Boolean, onToggle: () -> Unit) {
    val rotation by animateFloatAsState(
        if (open) 180f else 0f,
        MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "toolsKey",
    )
    val description = stringResource(if (open) R.string.tools_close else R.string.tools_open)
    IconButton(onClick = onToggle) {
        Icon(
            painterResource(R.drawable.ic_chevron_right),
            description,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.graphicsLayer { rotationZ = rotation },
        )
    }
}

/** Paste, clear clipboard and settings, covering the suggestions. */
@Composable
private fun ToolsRow(hasClip: Boolean, tools: ToolActions) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolButton(R.drawable.ic_paste, R.string.tools_paste, R.string.tools_paste, hasClip, tools.onPaste)
        ToolButton(
            R.drawable.ic_clipboard_clear,
            R.string.tools_clear_clipboard,
            R.string.tools_clear_clipboard_description,
            hasClip,
            tools.onClearClipboard,
        )
        ToolButton(R.drawable.ic_settings, R.string.tools_settings, R.string.tools_settings_description, true, tools.onSettings)
    }
}

@Composable
private fun RowScope.ToolButton(
    @DrawableRes icon: Int,
    label: Int,
    description: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val spoken = stringResource(description)
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 10.dp),
        modifier = Modifier
            .weight(1f)
            .height(40.dp)
            .semantics { contentDescription = spoken },
    ) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            stringResource(label),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelLarge,
        )
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
internal fun KeyRowView(row: KeyRow, state: KeyboardState, handlers: KeyHandlers) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(RowHeight),
    ) {
        row.forEach { key -> KeySlot(key, state, handlers) }
    }
}

@Composable
internal fun RowScope.KeySlot(key: Key, state: KeyboardState, handlers: KeyHandlers) {
    val slot = Modifier
        .weight(key.weight)
        .fillMaxHeight()
        .padding(KeyGap)
    when (key) {
        is Gap -> Spacer(slot)
        is CharKey -> {
            val shifted = state.shift != ShiftState.Off
            val label = if (shifted) key.label.uppercase() else key.label
            val alternates = Layouts.alternatesFor(key.output)
                .map { if (shifted) it.uppercase() else it }
            KeyButton(
                modifier = slot.semantics { contentDescription = label },
                style = if (key.secondary) KeyStyle.Function else KeyStyle.Letter,
                gesture = if (alternates.isEmpty()) KeyGesture.Tap else KeyGesture.Alternates(alternates),
                handlers = handlers,
                onTap = { handlers.onKey(key) },
            ) {
                Text(label, fontSize = 23.sp, color = it)
            }
        }
        is ActionKey -> ActionKeyView(key, state, slot, handlers)
    }
}

@Composable
private fun ActionKeyView(
    key: ActionKey,
    state: KeyboardState,
    modifier: Modifier,
    handlers: KeyHandlers,
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
        gesture = when (key.action) {
            KeyAction.Backspace -> KeyGesture.Repeat
            KeyAction.Space -> KeyGesture.Cursor
            else -> KeyGesture.Tap
        },
        handlers = handlers,
        onTap = { handlers.onKey(key) },
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
            KeyAction.Enter -> KeyIcon(
                if (state.emojiQuery != null) R.drawable.ic_check else enterIcon(state.enterAction),
                tint,
            )
            KeyAction.Emoji -> KeyIcon(R.drawable.ic_emoji, tint)
            KeyAction.Space -> if (state.page == KeyboardPage.Numpad) {
                Text("\u2423", fontSize = 23.sp, color = tint)
            }
            KeyAction.ToLetters -> KeyText("ABC", tint)
            KeyAction.ToSymbols -> KeyText(if (state.page == KeyboardPage.Numpad) "!?#" else "?123", tint)
            KeyAction.ToMoreSymbols -> KeyText("=\\<", tint)
            KeyAction.ToNumpad -> Text(
                "1 2\n3 4",
                fontSize = 12.sp,
                lineHeight = 13.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                color = tint,
            )
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

// --- Key button with Expressive press motion --------------------------------------

internal enum class KeyStyle { Letter, Function, Active, Primary, Ghost }

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

/** How a key responds to touch beyond a simple tap. */
internal sealed interface KeyGesture {
    /** Commit on release. */
    data object Tap : KeyGesture

    /** Hold to repeat (backspace). */
    data object Repeat : KeyGesture

    /** Tap for space; slide sideways to move the cursor. */
    data object Cursor : KeyGesture

    /** Hold to open a popup of alternates, slide to choose, release to commit. */
    data class Alternates(
        val options: List<String>,
        /** Overrides what picking does (emoji remember their skin tone). */
        val onPick: ((String) -> Unit)? = null,
    ) : KeyGesture
}

/**
 * A key that commits on release (so a finger can slide off to cancel), gives
 * system haptics on press, and morphs shape on press using the Expressive
 * motion scheme's spatial spring.
 */
@Composable
internal fun KeyButton(
    modifier: Modifier,
    style: KeyStyle,
    handlers: KeyHandlers,
    gesture: KeyGesture = KeyGesture.Tap,
    onTap: () -> Unit,
    content: @Composable (tint: Color) -> Unit,
) {
    val view = LocalView.current
    val currentOnTap by rememberUpdatedState(onTap)
    val currentGesture by rememberUpdatedState(gesture)
    var pressed by remember { mutableStateOf(false) }
    var bounds by remember { mutableStateOf(Rect.Zero) }

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
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(corner))
            .background(container)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    val g = currentGesture
                    try {
                        when (g) {
                            KeyGesture.Tap -> if (waitForUpOrCancellation() != null) currentOnTap()

                            KeyGesture.Repeat -> {
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
                            }

                            KeyGesture.Cursor -> {
                                val slop = viewConfiguration.touchSlop
                                val step = CursorStep.toPx()
                                var sliding = false
                                var moved = 0
                                while (true) {
                                    val change = awaitPointerEvent().changes
                                        .firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) {
                                        if (!sliding) currentOnTap()
                                        break
                                    }
                                    val dx = change.position.x - down.position.x
                                    if (!sliding && abs(dx) > slop) sliding = true
                                    if (sliding) {
                                        change.consume()
                                        val target = ((dx - sign(dx) * slop) / step).toInt()
                                        if (target != moved) {
                                            handlers.onCursor(target - moved)
                                            moved = target
                                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                        }
                                    }
                                }
                            }

                            is KeyGesture.Alternates -> {
                                val released = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                    Released(waitForUpOrCancellation() != null)
                                }
                                if (released != null) {
                                    if (released.normally) currentOnTap()
                                } else {
                                    handlers.popup.open(g.options, bounds, g.onPick)
                                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                    while (true) {
                                        val change = awaitPointerEvent().changes
                                            .firstOrNull { it.id == down.id } ?: break
                                        if (!change.pressed) {
                                            handlers.popup.commit()
                                            break
                                        }
                                        change.consume()
                                        handlers.popup.track(bounds.left + change.position.x, bounds.top + change.position.y)
                                    }
                                }
                            }
                        }
                    } finally {
                        pressed = false // also runs if the keyboard hides mid-press
                        if (g is KeyGesture.Alternates) handlers.popup.dismiss()
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
