package info.ibtaura.auraclarus.recovery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import info.ibtaura.aura.core.Mode
import info.ibtaura.aura.core.Shelf
import info.ibtaura.auraclarus.design.AuraColors
import info.ibtaura.auraclarus.design.AuraType
import info.ibtaura.auraclarus.design.GlassCircleButton
import info.ibtaura.auraclarus.design.ModeTile
import info.ibtaura.auraclarus.design.SearchCapsule
import info.ibtaura.auraclarus.design.SectionTitle
import info.ibtaura.auraclarus.design.auraIcon
import info.ibtaura.auraclarus.design.bottomPadding
import info.ibtaura.auraclarus.shell.AppState
import info.ibtaura.auraclarus.shell.AuraNav
import info.ibtaura.auraclarus.shell.Route
import java.time.LocalTime

/** Figma's order, with Focus added first. */
private val recoveryShelves = listOf(
    Shelf.focus, Shelf.sleep, Shelf.movement, Shelf.breathe, Shelf.meditate,
    Shelf.rest, Shelf.mind, Shelf.outside, Shelf.people, Shelf.fuel,
)

/**
 * Frame iPhone 18 Pro - 5: search, the break pills, the Now carousel and the shelves (iOS RecoveryView). `pushed` is
 * true when reached from Search > Modes: a back button joins the toolbar.
 */
@Composable
fun RecoveryScreen(app: AppState, nav: AuraNav, pushed: Boolean = false) {
    var query by rememberSaveable { mutableStateOf("") }
    var takingBreak by rememberSaveable { mutableStateOf(false) }
    var loggingBreak by rememberSaveable { mutableStateOf(false) }
    // The break the sheet asked for. It starts once the sheet has gone.
    var pendingBreak by remember { mutableStateOf<RecoveryBreakChoice?>(null) }
    val focusManager = LocalFocusManager.current
    val isSearching = query.trim().isNotEmpty()

    // Scrolling the page puts the keyboard away (iOS `.scrollDismissesKeyboard(.immediately)`).
    val dismissKeyboard = remember(focusManager) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) focusManager.clearFocus()
                return Offset.Zero
            }
        }
    }

    fun startPendingBreak() {
        val choice = pendingBreak ?: return
        pendingBreak = null
        var mode = app.mode("recovery-break") ?: return
        if (choice.note.isNotEmpty()) mode = mode.copy(note = choice.note)
        app.startRun(mode, choice.minutes, choice.title, choice.type)
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(AuraColors.background)) {
        // Exactly two tiles fill the width, with the third just past the edge.
        val tileWidth = maxOf(150.dp, (maxWidth - 66.dp) / 2)
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
            RecoveryToolbar(onBack = if (pushed) ({ nav.pop() }) else null)
            Column(
                Modifier
                    .fillMaxSize()
                    .nestedScroll(dismissKeyboard)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp + bottomPadding()),
            ) {
                SearchCapsule(
                    text = query,
                    onTextChange = { query = it },
                    modifier = Modifier.padding(horizontal = 22.dp).padding(top = 8.dp),
                    fieldModifier = Modifier.testTag("recovery-search"),
                )
                if (isSearching) {
                    RecoverySearchResults(app.modes.filter { it.matches(query) }, tileWidth) { open(nav, it, null) }
                } else {
                    RecoveryBreakButtons(onTake = { takingBreak = true }, onLog = { loggingBreak = true })
                    RecoveryNowSection(app.modes) { open(nav, it, it.nowTitle) }
                    recoveryShelves.forEach { shelf ->
                        RecoveryShelf(shelf, app.modes.filter { it.shelf == shelf }, tileWidth) { open(nav, it, null) }
                    }
                }
            }
        }
    }

    if (takingBreak) {
        RecoveryBreakSheet(
            app,
            onStart = { pendingBreak = it },
            onDismiss = {
                takingBreak = false
                startPendingBreak()
            },
        )
    }
    if (loggingBreak) {
        LogBreakSheet(app, onDismiss = { loggingBreak = false })
    }
}

private fun open(nav: AuraNav, mode: Mode, headline: String?) {
    nav.push(Route.ModeDetail(mode.id, headline))
}

/** The glass "Edit" capsule and the filter circle. No frame exists for these two yet, so they do nothing. */
@Composable
private fun RecoveryToolbar(onBack: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (onBack != null) {
            GlassCircleButton("chevron.left", onBack, Modifier.testTag("back"), contentDescription = "Back")
        }
        Box(
            Modifier
                .height(48.dp)
                .shadow(6.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.08f), spotColor = Color.Black.copy(alpha = 0.12f))
                .clip(CircleShape)
                .background(AuraColors.card)
                .clickable(role = Role.Button) {}
                .semantics { contentDescription = "Edit, not available yet" }
                .testTag("recovery-edit")
                .padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Edit", style = AuraType.body, color = Color.Black)
        }
        Spacer(Modifier.weight(1f))
        GlassCircleButton(
            "line.3.horizontal.decrease",
            onClick = {},
            modifier = Modifier.testTag("recovery-filter"),
            contentDescription = "Filter, not available yet",
        )
    }
}

/** "Take a recovery break" and "Add a break I took", between search and Now. */
@Composable
private fun RecoveryBreakButtons(onTake: () -> Unit, onLog: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(top = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RecoveryBreakButton("Take a recovery break", "leaf.fill", onTake, Modifier.weight(1f).testTag("recovery-take-break"))
        RecoveryBreakButton("Add a break I took", "plus", onLog, Modifier.weight(1f).testTag("recovery-log-break"))
    }
}

/** One of the two break pills: an icon and a title that may take two lines. */
@Composable
private fun RecoveryBreakButton(title: String, symbol: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .heightIn(min = 50.dp)
            .clip(CircleShape)
            .background(AuraColors.card)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(auraIcon(symbol), contentDescription = null, tint = AuraColors.blue, modifier = Modifier.size(18.dp))
        Text(title, style = AuraType.calloutSemibold, color = Color.Black, maxLines = 2, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun RecoveryNowSection(modes: List<Mode>, onOpen: (Mode) -> Unit) {
    val hour = LocalTime.now().hour
    val cards = remember(modes, hour) { Mode.nowCards(modes, hour, limit = 4) }
    Column(Modifier.padding(top = 19.dp, bottom = 13.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionTitle("Now", Modifier.padding(start = 42.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            cards.forEach { mode ->
                RecoveryNowCard(
                    mode,
                    Modifier
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(22.dp))
                        .clickable(role = Role.Button) { onOpen(mode) }
                        .testTag("now-card-${mode.id}"),
                )
            }
        }
    }
}

@Composable
private fun RecoveryShelf(shelf: Shelf, modes: List<Mode>, tileWidth: Dp, onOpen: (Mode) -> Unit) {
    val state = rememberLazyListState()
    Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        SectionTitle(shelf.raw, Modifier.padding(start = 42.dp))
        LazyRow(
            state = state,
            contentPadding = PaddingValues(horizontal = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
            flingBehavior = rememberSnapFlingBehavior(lazyListState = state),
        ) {
            items(modes, key = { it.id }) { mode -> RecoveryTileLink(mode, tileWidth) { onOpen(mode) } }
        }
    }
}

/** A tile that opens its mode. */
@Composable
private fun RecoveryTileLink(mode: Mode, width: Dp, onClick: () -> Unit) {
    ModeTile(
        mode,
        Modifier
            .width(width)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag("mode-${mode.id}"),
    )
}

/** While searching, the matches show together in two columns. */
@Composable
private fun RecoverySearchResults(modes: List<Mode>, tileWidth: Dp, onOpen: (Mode) -> Unit) {
    if (modes.isEmpty()) {
        Column(
            Modifier.fillMaxWidth().padding(top = 80.dp, start = 22.dp, end = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(auraIcon("magnifyingglass"), contentDescription = null, tint = AuraColors.gray, modifier = Modifier.size(44.dp))
            Text("No Results", style = AuraType.system(22, FontWeight.Bold), color = Color.Black)
            Text(
                "Check the spelling or try a new search.",
                style = AuraType.callout,
                color = AuraColors.gray,
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    Column(
        Modifier.padding(horizontal = 22.dp).padding(top = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        modes.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                row.forEach { mode -> RecoveryTileLink(mode, tileWidth) { onOpen(mode) } }
            }
        }
    }
}
