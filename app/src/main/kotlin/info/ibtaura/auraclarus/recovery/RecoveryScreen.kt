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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import info.ibtaura.aura.core.Mode
import info.ibtaura.aura.core.Shelf
import info.ibtaura.auraclarus.design.AuraColors
import info.ibtaura.auraclarus.design.AuraType
import info.ibtaura.auraclarus.design.GlassCircleButton
import info.ibtaura.auraclarus.design.ModeTile
import info.ibtaura.auraclarus.design.PillButton
import info.ibtaura.auraclarus.design.PillStyle
import info.ibtaura.auraclarus.design.SearchCapsule
import info.ibtaura.auraclarus.design.SectionTitle
import info.ibtaura.auraclarus.design.auraIcon
import info.ibtaura.auraclarus.design.bottomPadding
import info.ibtaura.auraclarus.shell.AppState
import info.ibtaura.auraclarus.shell.AuraNav
import info.ibtaura.auraclarus.shell.Route
import java.time.LocalTime

/**
 * Frame iPhone 18 Pro - 5: search, the break pills, the Now carousel and the shelves (iOS RecoveryView). `pushed` is
 * true when reached from Search > Modes: a back button joins the toolbar. Edit reorders and hides the sections (kept
 * on the phone per account); Filter narrows the tiles by length and shelf.
 */
@Composable
fun RecoveryScreen(app: AppState, nav: AuraNav, pushed: Boolean = false) {
    var query by rememberSaveable { mutableStateOf("") }
    var takingBreak by rememberSaveable { mutableStateOf(false) }
    var loggingBreak by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var filtering by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable(stateSaver = RecoveryFilter.saver) { mutableStateOf(RecoveryFilter()) }
    val store = rememberRecoveryLayoutStore(app)
    var layout by remember(store) { mutableStateOf(store.load()) }
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
            RecoveryToolbar(
                onBack = if (pushed) ({ nav.pop() }) else null,
                filterActive = filter.isActive,
                onEdit = {
                    focusManager.clearFocus()
                    editing = true
                },
                onFilter = {
                    focusManager.clearFocus()
                    filtering = true
                },
            )
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
                if (filter.isActive) {
                    ActiveFilterPill(
                        summary = filter.summary,
                        count = app.modes.count { filter.matches(it) },
                        onOpen = { filtering = true },
                        onClear = { filter = RecoveryFilter() },
                    )
                }
                if (isSearching) {
                    RecoverySearchResults(
                        app.modes.filter { it.matches(query) && filter.matches(it) },
                        tileWidth,
                        filterActive = filter.isActive,
                        onClearFilter = { filter = RecoveryFilter() },
                    ) { open(nav, it, null) }
                } else {
                    RecoveryBreakButtons(onTake = { takingBreak = true }, onLog = { loggingBreak = true })
                    RecoverySections(
                        modes = app.modes,
                        layout = layout,
                        filter = filter,
                        tileWidth = tileWidth,
                        onOpenNow = { open(nav, it, it.nowTitle) },
                        onOpen = { open(nav, it, null) },
                        onClearFilter = { filter = RecoveryFilter() },
                        onEdit = { editing = true },
                    )
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
    if (editing) {
        RecoveryEditSheet(
            initial = layout,
            modes = app.modes,
            onSave = {
                layout = it
                store.save(it)
            },
            onDismiss = { editing = false },
        )
    }
    if (filtering) {
        RecoveryFilterSheet(
            filter = filter,
            modes = app.modes,
            onChange = { filter = it },
            onDismiss = { filtering = false },
        )
    }
}

private fun open(nav: AuraNav, mode: Mode, headline: String?) {
    nav.push(Route.ModeDetail(mode.id, headline))
}

/** The sections in the person's order, minus hidden ones, each narrowed by the filter. */
@Composable
private fun RecoverySections(
    modes: List<Mode>,
    layout: RecoveryLayout,
    filter: RecoveryFilter,
    tileWidth: Dp,
    onOpenNow: (Mode) -> Unit,
    onOpen: (Mode) -> Unit,
    onClearFilter: () -> Unit,
    onEdit: () -> Unit,
) {
    val hour = LocalTime.now().hour
    val nowCards = remember(modes, hour, filter) { Mode.nowCards(modes, hour, limit = 4).filter { filter.matches(it) } }
    val visible = layout.visible
    var shownAny = false
    visible.forEach { section ->
        val shelf = section.shelf
        if (shelf == null) {
            if (nowCards.isNotEmpty()) {
                shownAny = true
                RecoveryNowSection(nowCards, onOpenNow)
            }
        } else {
            val tiles = modes.filter { it.shelf == shelf && filter.matches(it) }
            if (tiles.isNotEmpty()) {
                shownAny = true
                RecoveryShelf(shelf, tiles, tileWidth, onOpen)
            }
        }
    }
    when {
        visible.isEmpty() -> RecoveryEmptyState(
            symbol = "square.grid.2x2.fill",
            title = "Everything is hidden",
            line = "Choose which sections Recovery shows.",
            action = "Edit Sections",
            actionTag = "recovery-empty-edit",
            onAction = onEdit,
        )
        !shownAny && filter.isActive -> RecoveryEmptyState(
            symbol = "line.3.horizontal.decrease",
            title = "No modes match",
            line = if (visible.size < layout.order.size) {
                "Try other lengths or shelves, or show hidden sections in Edit."
            } else {
                "Try other lengths or shelves."
            },
            action = "Clear Filter",
            actionTag = "recovery-empty-clear",
            onAction = onClearFilter,
        )
    }
}

/** The glass "Edit" capsule and the filter circle, which turns blue with a dot while a filter is on. */
@Composable
private fun RecoveryToolbar(onBack: (() -> Unit)?, filterActive: Boolean, onEdit: () -> Unit, onFilter: () -> Unit) {
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
                .clickable(role = Role.Button, onClick = onEdit)
                .semantics { contentDescription = "Edit sections" }
                .testTag("recovery-edit")
                .padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Edit", style = AuraType.body, color = Color.Black)
        }
        Spacer(Modifier.weight(1f))
        Box {
            GlassCircleButton(
                "line.3.horizontal.decrease",
                onClick = onFilter,
                modifier = Modifier.testTag("recovery-filter"),
                contentDescription = if (filterActive) "Filter, on" else "Filter",
                tint = if (filterActive) AuraColors.blue else Color.Black,
            )
            if (filterActive) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = (-2).dp, y = 2.dp)
                        .size(12.dp)
                        .background(Color.White, CircleShape)
                        .padding(2.dp)
                        .background(AuraColors.blue, CircleShape),
                )
            }
        }
    }
}

/** Under search while a filter is on: what it keeps, how many, and a clear button. */
@Composable
private fun ActiveFilterPill(summary: String, count: Int, onOpen: () -> Unit, onClear: () -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 22.dp)
            .padding(top = 12.dp)
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(CircleShape)
            .background(AuraColors.blue.copy(alpha = 0.12f))
            .padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onOpen)
                .testTag("recovery-filter-pill")
                .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(auraIcon("line.3.horizontal.decrease"), contentDescription = null, tint = AuraColors.blue, modifier = Modifier.size(18.dp))
            Text(
                summary,
                style = AuraType.calloutSemibold,
                color = AuraColors.blue,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                if (count == 1) "· 1 mode" else "· $count modes",
                style = AuraType.callout,
                color = AuraColors.blue.copy(alpha = 0.75f),
                maxLines = 1,
            )
        }
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onClear)
                .semantics { contentDescription = "Clear filter" }
                .testTag("recovery-filter-clear"),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(22.dp).background(AuraColors.blue, CircleShape), contentAlignment = Alignment.Center) {
                Icon(auraIcon("xmark"), contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/** "Take a recovery break" and "Add a break I took", between search and Now. */
@Composable
private fun RecoveryBreakButtons(onTake: () -> Unit, onLog: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(top = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RecoveryBreakButton(
            "Take a\nrecovery break",
            "leaf.fill",
            onTake,
            Modifier.weight(1f).testTag("recovery-take-break"),
        )
        RecoveryBreakButton("Add a break\nI took", "plus", onLog, Modifier.weight(1f).testTag("recovery-log-break"))
    }
}

/**
 * One of the two break pills: the icon centred against two balanced lines, as on iOS. The lines are fixed and shrink
 * a little on narrow phones or large fonts rather than wrapping a third time or being cut.
 */
@Composable
private fun RecoveryBreakButton(title: String, symbol: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .heightIn(min = 60.dp)
            .clip(CircleShape)
            .background(AuraColors.card)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 18.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(auraIcon(symbol), contentDescription = null, tint = AuraColors.blue, modifier = Modifier.size(20.dp))
        FitText(title, AuraType.calloutSemibold, Color.Black, Modifier.weight(1f), maxLines = 2, minScale = 0.78f)
    }
}

@Composable
private fun RecoveryNowSection(cards: List<Mode>, onOpen: (Mode) -> Unit) {
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
                        .clip(RoundedCornerShape(22.dp))
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

/** An icon, a title, one line and an action, in place of the shelves. */
@Composable
private fun RecoveryEmptyState(
    symbol: String,
    title: String,
    line: String,
    action: String,
    actionTag: String,
    onAction: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(top = 56.dp, start = 32.dp, end = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(64.dp).background(AuraColors.card, RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(auraIcon(symbol), contentDescription = null, tint = AuraColors.blue, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(title, style = AuraType.system(22, FontWeight.Bold), color = Color.Black, textAlign = TextAlign.Center)
        Text(line, style = AuraType.callout, color = AuraColors.gray, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        PillButton(action, onAction, Modifier.testTag(actionTag), style = PillStyle.Secondary)
    }
}

/** While searching, the matches show together in two columns. */
@Composable
private fun RecoverySearchResults(
    modes: List<Mode>,
    tileWidth: Dp,
    filterActive: Boolean,
    onClearFilter: () -> Unit,
    onOpen: (Mode) -> Unit,
) {
    if (modes.isEmpty()) {
        Column(
            Modifier.fillMaxWidth().padding(top = 80.dp, start = 22.dp, end = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(auraIcon("magnifyingglass"), contentDescription = null, tint = AuraColors.gray, modifier = Modifier.size(44.dp))
            Text("No Results", style = AuraType.system(22, FontWeight.Bold), color = Color.Black)
            Text(
                if (filterActive) "Nothing matches both the search and the filter." else "Check the spelling or try a new search.",
                style = AuraType.callout,
                color = AuraColors.gray,
                textAlign = TextAlign.Center,
            )
            if (filterActive) {
                Spacer(Modifier.height(6.dp))
                PillButton("Clear Filter", onClearFilter, Modifier.testTag("recovery-search-clear-filter"), style = PillStyle.Secondary)
            }
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
