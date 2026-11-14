package info.ibtaura.auraclarus.design

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import info.ibtaura.aura.core.Mode
import java.util.Locale

// Compose ports of DesignSystem/Components.swift and Search/SearchStyle.swift, with the same names and sizes
// (iOS points = dp/sp). Test tags go on the `modifier` you pass: `PillButton("Save", ..., Modifier.testTag("x"))`.

/** How much of the bottom the floating tab bar (and mini run bar) covers. RootScreen sets it; pages pad by it. */
val LocalBottomInset = compositionLocalOf { 0.dp }

/** The bottom padding a scrolling page needs: the tab bar's height inside the tabs, else the navigation bar. */
@Composable
fun bottomPadding(): Dp = max(LocalBottomInset.current, WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())

private val cardShape = RoundedCornerShape(30.dp)

/** A white rounded card, the Figma's main surface. */
@Composable
fun AuraCard(
    modifier: Modifier = Modifier,
    padding: Dp = 24.dp,
    spacing: Dp = 12.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(AuraColors.card, cardShape)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

/** "Insights", "Today", "Sleep": the big bold section title. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = AuraType.title,
        color = Color.Black,
        modifier = modifier.fillMaxWidth().semantics { heading() },
    )
}

/** The small grey line under a card. Pass `linkTitle` for the blue words at its end. */
@Composable
fun Footnote(text: String, modifier: Modifier = Modifier, linkTitle: String? = null, onLink: (() -> Unit)? = null) {
    val words = buildAnnotatedString {
        append(text)
        if (linkTitle != null) {
            append(" ")
            withStyle(SpanStyle(color = AuraColors.blue)) { append(linkTitle) }
        }
    }
    Text(
        words,
        style = AuraType.callout,
        color = AuraColors.footnote,
        modifier = modifier
            .fillMaxWidth()
            .then(if (onLink != null) Modifier.clickable(onClick = onLink) else Modifier),
    )
}

enum class PillStyle { Primary, Secondary }

/** Start (blue) and Skip (clear) pills. `fills` takes the row's whole width (wrap the pill in `Modifier.weight(1f)`). */
@Composable
fun PillButton(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: PillStyle = PillStyle.Primary,
    fills: Boolean = false,
    enabled: Boolean = true,
) {
    val fill = if (style == PillStyle.Primary) AuraColors.startBlue else AuraColors.glass
    val textColor = if (style == PillStyle.Primary) Color.White else Color.Black
    Box(
        modifier
            .then(if (fills) Modifier.fillMaxWidth() else Modifier)
            .heightIn(min = 48.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .shadow(if (style == PillStyle.Secondary) 6.dp else 2.dp, CircleShape, clip = false,
                ambientColor = Color.Black.copy(alpha = 0.08f), spotColor = Color.Black.copy(alpha = 0.12f))
            .clip(CircleShape)
            .background(fill)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (fills) 12.dp else 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, style = AuraType.pill, color = textColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The white capsule row: icon, title, an optional value and a chevron ("Calendar", "Step Count"). */
@Composable
fun ListRowCard(
    title: String,
    modifier: Modifier = Modifier,
    symbol: String? = null,
    symbolColor: Color = AuraColors.green,
    value: String? = null,
    unit: String? = null,
    showsChevron: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 62.dp)
            .clip(CircleShape)
            .background(AuraColors.card)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 26.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (symbol != null) {
            Icon(auraIcon(symbol), contentDescription = null, tint = symbolColor, modifier = Modifier.size(26.dp))
        }
        Text(title, style = AuraType.row, color = Color.Black, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.weight(1f))
        if (value != null) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(value, style = AuraType.row, color = Color.Black, maxLines = 1)
                if (!unit.isNullOrEmpty()) {
                    Text(unit, style = AuraType.callout, color = AuraColors.footnote, maxLines = 1,
                        modifier = Modifier.padding(bottom = 2.dp))
                }
            }
        }
        if (showsChevron) Chevron()
    }
}

/** The grey chevron at a row's end. */
@Composable
fun Chevron(modifier: Modifier = Modifier) {
    Icon(auraIcon("chevron.right"), contentDescription = null, tint = AuraColors.footnote, modifier = modifier.size(22.dp))
}

/** The grey search capsule with a magnifier and a microphone. Tag the field itself with `fieldModifier`. */
@Composable
fun SearchCapsule(
    text: String,
    onTextChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    prompt: String = "Search",
    fieldModifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().height(44.dp).clip(CircleShape).background(AuraColors.searchField).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(auraIcon("magnifyingglass"), contentDescription = null, tint = AuraColors.secondary, modifier = Modifier.size(20.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (text.isEmpty()) Text(prompt, style = AuraType.field, color = AuraColors.secondary)
            BasicTextField(
                value = text,
                onValueChange = onTextChange,
                singleLine = true,
                textStyle = AuraType.field.copy(color = Color.Black),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                modifier = fieldModifier.fillMaxWidth(),
            )
        }
        Icon(auraIcon("mic.fill"), contentDescription = null, tint = AuraColors.secondary, modifier = Modifier.size(20.dp))
    }
}

/** A capsule text field on a card (Sign in's email and code). Tag it with `modifier`. */
@Composable
fun AuraTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    background: Color = AuraColors.background,
) {
    Box(
        Modifier.fillMaxWidth().height(48.dp).clip(CircleShape).background(background).padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) Text(placeholder, style = AuraType.field, color = AuraColors.secondary)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = AuraType.field.copy(color = Color.Black),
            keyboardOptions = keyboardOptions,
            modifier = modifier.fillMaxWidth(),
        )
    }
}

/** A rounded white square holding a mode's icon. */
@Composable
fun IconTile(symbol: String, color: Color, modifier: Modifier = Modifier, size: Dp = 66.dp) {
    Box(
        modifier.size(size).background(AuraColors.card, RoundedCornerShape(size * 0.27f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(auraIcon(symbol), contentDescription = null, tint = color, modifier = Modifier.size(size * 0.46f))
    }
}

/** One tile on a Recovery shelf: icon, coloured word, then its length. */
@Composable
fun ModeTile(mode: Mode, modifier: Modifier = Modifier) {
    val length = when {
        mode.minutes == 0 -> "Open"
        mode.minutes >= 120 -> "${mode.minutes / 60} Hours"
        else -> String.format(Locale.ROOT, "%02d Minutes", mode.minutes)
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        IconTile(mode.shelf.symbol, mode.shelf.color)
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(mode.word, style = AuraType.calloutMedium, color = mode.shelf.color, maxLines = 1)
            Text(length, style = AuraType.calloutMedium, color = Color.Black, maxLines = 1)
        }
    }
}

/** A white circle with an icon, like the iOS glass toolbar buttons (the back button, Recovery's toolbar). */
@Composable
fun GlassCircleButton(
    symbol: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    size: Dp = 48.dp,
    tint: Color = Color.Black,
) {
    Box(
        modifier
            .size(size)
            .shadow(6.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.08f), spotColor = Color.Black.copy(alpha = 0.12f))
            .clip(CircleShape)
            .background(AuraColors.card)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(auraIcon(symbol), contentDescription = contentDescription, tint = tint, modifier = Modifier.size(size * 0.42f))
    }
}

/**
 * The Figma's pushed pages (`metricBackBar`): a round back button and the big title beside it. The button is tagged
 * "back" and described "Back".
 */
@Composable
fun BackBar(title: String, onBack: () -> Unit, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        GlassCircleButton("chevron.left", onBack, Modifier.testTag("back"), contentDescription = "Back")
        Text(
            title,
            style = AuraType.title,
            color = Color.Black,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        trailing()
    }
}

/**
 * The page behind everything: a scrolling column, 22 dp sides, 18 dp between items, on the grey background. With
 * `title` and `onBack` it is a pushed page with the BackBar fixed on top; without, put a SectionTitle in `content`.
 * Pads for the status bar, the floating tab bar and the keyboard.
 */
@Composable
fun AuraPage(
    modifier: Modifier = Modifier,
    title: String? = null,
    onBack: (() -> Unit)? = null,
    scrollState: ScrollState = rememberScrollState(),
    spacing: Dp = 18.dp,
    trailing: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxSize().background(AuraColors.background).windowInsetsPadding(WindowInsets.statusBars).imePadding()) {
        if (title != null && onBack != null) BackBar(title, onBack, trailing = trailing)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(start = 22.dp, end = 22.dp, top = 12.dp, bottom = 12.dp + bottomPadding()),
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content,
        )
    }
}

/** AuraPage for long lists: the same frame around a LazyColumn. */
@Composable
fun AuraLazyPage(
    modifier: Modifier = Modifier,
    title: String? = null,
    onBack: (() -> Unit)? = null,
    state: LazyListState = rememberLazyListState(),
    spacing: Dp = 18.dp,
    trailing: @Composable () -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    Column(modifier.fillMaxSize().background(AuraColors.background).windowInsetsPadding(WindowInsets.statusBars).imePadding()) {
        if (title != null && onBack != null) BackBar(title, onBack, trailing = trailing)
        LazyColumn(
            Modifier.fillMaxSize(),
            state = state,
            contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 12.dp, bottom = 12.dp + bottomPadding()),
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content,
        )
    }
}

/** For screens the Figma doesn't have yet. */
@Composable
fun NotDesignedYet(title: String, onBack: (() -> Unit)? = null) {
    Column(Modifier.fillMaxSize().background(AuraColors.background).windowInsetsPadding(WindowInsets.statusBars)) {
        if (onBack != null) BackBar(title, onBack)
        Column(
            Modifier.fillMaxSize().padding(bottom = bottomPadding()),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(auraIcon("paintbrush"), contentDescription = null, tint = AuraColors.gray, modifier = Modifier.size(44.dp))
            Spacer(Modifier.height(12.dp))
            Text(title, style = AuraType.system(22, FontWeight.Bold), color = Color.Black)
            Text("Not designed yet.", style = AuraType.callout, color = AuraColors.gray)
        }
    }
}

/** A centred spinner on the page background (the iOS `ProgressView()` page). */
@Composable
fun LoadingPage(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(AuraColors.background), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = AuraColors.gray, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
    }
}

// MARK: Search style

/** The white card around a list of rows. */
@Composable
fun SearchGroupCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(cardShape).background(AuraColors.card).padding(vertical = 7.dp),
        content = content,
    )
}

/** The thin line between rows, starting under the row's title. */
@Composable
fun SearchRowDivider(leading: Dp = 72.dp) {
    Box(Modifier.fillMaxWidth().padding(start = leading, end = 20.dp).height(1.dp).background(AuraColors.divider))
}

/** One row of a SearchGroupCard: an optional icon, the title and a chevron. */
@Composable
fun SearchGroupRow(
    title: String,
    modifier: Modifier = Modifier,
    symbol: String? = null,
    symbolColor: Color = AuraColors.green,
    showsChevron: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(start = if (symbol == null) 20.dp else 26.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (symbol != null) {
            Icon(auraIcon(symbol), contentDescription = null, tint = symbolColor, modifier = Modifier.size(26.dp))
        }
        Text(title, style = AuraType.row, color = Color.Black, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f))
        if (showsChevron) Chevron()
    }
}

/** A calm message in place of a list or chart. */
@Composable
fun MetricEmptyState(text: String = "No data yet", modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
        Text(text, style = AuraType.bodyMedium, color = AuraColors.metricGrey)
    }
}

/** Shown when a load fails for any reason other than consent or a lost session. The button is "metric-retry". */
@Composable
fun MetricRetryState(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("This didn't load.", style = AuraType.bodyMedium, color = AuraColors.metricGrey)
        PillButton("Try again", onRetry, Modifier.testTag("metric-retry"), style = PillStyle.Secondary)
    }
}

// MARK: Shared rows and controls

/** The Work hours row on Calendar and Settings: clock, title, the hours below it, chevron. Tag it with `modifier`. */
@Composable
fun WorkHoursRow(title: String, value: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 66.dp)
            .clip(cardShape)
            .background(AuraColors.card)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(auraIcon("clock.fill"), contentDescription = null, tint = AuraColors.blue, modifier = Modifier.size(26.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = AuraType.bodyMedium, color = Color.Black, maxLines = 1)
            if (value != null) {
                Text(value, style = AuraType.callout, color = AuraColors.gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Chevron()
    }
}

/** A thin grey line inside a card (SwiftUI `Divider()`). */
@Composable
fun CardDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(0.5.dp).background(AuraColors.separator))
}

/**
 * SwiftUI's `.pickerStyle(.menu)`: the chosen value in blue with up/down arrows; a tap opens the choices.
 * Tag the anchor with `modifier`; each choice is tagged "<tag>-option-<label>" when `tag` is given.
 */
@Composable
fun <T> AuraMenuPicker(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    tag: String? = null,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.DropdownList) { open = true }
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(label(selected), style = AuraType.body, color = AuraColors.blue)
            Icon(auraIcon("chevron.up.chevron.down"), contentDescription = null, tint = AuraColors.blue, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 360.dp)) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(label(option), style = AuraType.body, color = if (option == selected) AuraColors.blue else Color.Black) },
                    onClick = {
                        open = false
                        onSelect(option)
                    },
                    modifier = if (tag != null) Modifier.testTag("$tag-option-${label(option)}") else Modifier,
                )
            }
        }
    }
}

/**
 * A sheet over the page (SwiftUI `.sheet`), full height, on the grey background. `dismissible = false` stops a swipe
 * or back from closing it (`interactiveDismissDisabled`). Test tags inside it work with UiAutomator as elsewhere.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun AuraSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dismissible: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { dismissible || it != androidx.compose.material3.SheetValue.Hidden })
    ModalBottomSheet(
        onDismissRequest = { if (dismissible) onDismiss() },
        sheetState = state,
        containerColor = AuraColors.background,
        modifier = modifier.semantics { testTagsAsResourceId = true },
        content = content,
    )
}

/**
 * SwiftUI's `confirmationDialog` / `alert`: a title, a message and two buttons. The confirm button is red when
 * `destructive`. Tags: `confirmTag` on the confirm button, "<confirmTag>-cancel" on Cancel.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AuraConfirmDialog(
    title: String,
    message: String?,
    confirmTitle: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = true,
    cancelTitle: String = "Cancel",
    confirmTag: String? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AuraColors.card,
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        title = { Text(title, style = AuraType.system(17, FontWeight.SemiBold), color = Color.Black) },
        text = message?.let { { Text(it, style = AuraType.callout, color = AuraColors.gray) } },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = if (confirmTag != null) Modifier.testTag(confirmTag) else Modifier,
            ) { Text(confirmTitle, style = AuraType.bodyMedium, color = if (destructive) AuraColors.red else AuraColors.blue) }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = if (confirmTag != null) Modifier.testTag("$confirmTag-cancel") else Modifier,
            ) { Text(cancelTitle, style = AuraType.bodyMedium, color = AuraColors.blue) }
        },
    )
}
