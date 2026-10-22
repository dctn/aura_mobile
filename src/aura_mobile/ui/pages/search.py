"""Search: a search field, then Logging, Modes and Sessions.

Logging is water (NI-04, in glasses and ml), mood (NI-02), connection (NI-03), a strength session (PB-04) and the
sleep check (DX-03), with the last week's history. Modes are the Session service's four: Timer, Flowtime, Walk,
Meeting. Sessions lists the running one (pause, resume, stop, complete) and the last week's; a recovery session's
"Stress change" shows its stress before and after (DX-07). The field filters every row on the page.
"""

from __future__ import annotations

from datetime import date, timedelta

from PyQt6.QtCore import Qt
from PyQt6.QtWidgets import QFrame, QHBoxLayout, QLabel, QLineEdit, QVBoxLayout, QWidget

from aura_mobile.ui import actions, kit, layers
from aura_mobile.ui import tokens as t
from aura_mobile.ui.context import AppContext
from aura_mobile.ui.pages.base import Page
from aura_mobile.ui.widgets import label, set_shown

#: (label, session mode, session kind, default minutes, what it does)
MODES: tuple[tuple[str, str, str, int, str], ...] = (
    ("Timer", "timer", "focus", 25, "Counts down, then completes on its own."),
    ("Flowtime", "flowtime", "focus", 0, "Counts up until you stop it: no set length."),
    ("Walk", "walk", "walk", 20, "A walk session; the band adds the steps."),
    ("Meeting", "meeting", "meeting", 30, "Marks the time as a meeting."),
)

#: the tile on a session row, by session kind
KIND_TILES: dict[str, tuple[str, str]] = {
    "focus": ("timer", t.BLUE),
    "walk": ("walk", t.FITNESS),
    "meeting": ("people", t.TEAL),
    "recovery": ("recover", t.RECOVERY),
}

#: the tile on a history row, by the start of its text
HISTORY_TILES: tuple[tuple[str, str, str], ...] = (
    ("Water", "drop", t.BLUE),
    ("Strength", "dumbbell", t.FITNESS),
    ("Mood", "bubble", t.SLEEP),
    ("Connection", "people", t.TEAL),
    ("Sleep check", "sleep", t.SLEEP),
)


def when_text(value: str, today: date) -> str:
    """``2026-10-22T10:00…`` as "Today 10:00", "Yesterday 10:00" or "Mon 10:00"."""
    try:
        day = date.fromisoformat(value[:10])
    except ValueError:
        return value[:16].replace("T", " ")
    clock = value[11:16]
    gap = (today - day).days
    name = "Today" if gap == 0 else "Yesterday" if gap == 1 else day.strftime("%a")
    return f"{name} {clock}".strip()


class WaterCard(kit.ChartCard):
    """Today's water as a big number, the week as bars and today's share of the goal as a ring."""

    def __init__(self) -> None:
        super().__init__("Water", "drop", when="Today")
        self.bars = kit.Bars(96)
        self.ring = kit.Ring(84, 11)
        self.body.addWidget(self.bars, 1)
        self.body.addWidget(self.ring, 0, Qt.AlignmentFlag.AlignBottom)

    def text(self) -> str:
        return " · ".join(x for x in (f"{self.big.text()} {self.unit.text()}".strip(), self.note.text()) if x)


class HistoryRow(QLabel):
    """A history line drawn as a list row: tile, what, and when on the right. Still a ``QLabel`` (``text()`` is
    what was logged)."""

    has_tile = True

    def __init__(self, what: str, when: str) -> None:
        super().__init__(what)
        self.setProperty("class", "rowtitle")
        self.setMinimumHeight(t.ROW_MIN)
        icon, accent = next(((i, a) for start, i, a in HISTORY_TILES if what.startswith(start)), ("note", None))
        self.tile = kit.Tile(icon, accent)
        self.tile.setParent(self)
        self.when = label(when, "rowvalue")
        self.when.setParent(self)
        self.setContentsMargins(57, 0, 120, 0)

    def resizeEvent(self, event) -> None:
        super().resizeEvent(event)
        self.tile.move(16, (self.height() - self.tile.height()) // 2)
        self.when.adjustSize()
        right = 16 + self.when.width() + 8
        if self.contentsMargins().right() != right:
            self.setContentsMargins(57, 0, right, 0)
        self.when.move(self.width() - 16 - self.when.width(), (self.height() - self.when.height()) // 2)


class RunningRow(QFrame):
    """The session running now: a row, then small Pause or Resume, Stop and Complete buttons."""

    has_tile = True

    def __init__(self, active: dict) -> None:
        super().__init__()
        self.setProperty("class", "row")
        lay = QVBoxLayout(self)
        lay.setContentsMargins(0, 0, 0, 12)
        lay.setSpacing(0)
        state = str(active.get("state") or "running")
        title = str(active.get("title") or active.get("kind") or "Session")
        sub = f"{state.capitalize()} · {active.get('mode')}"
        if active.get("plannedMinutes"):
            sub += f" · {active.get('plannedMinutes')} min"
        icon = "pause" if state == "paused" else "play"
        self.row = kit.Row(title, sub, icon=icon, accent=t.RECOVERY)
        lay.addWidget(self.row)
        buttons = QHBoxLayout()
        buttons.setContentsMargins(57, 0, 16, 0)
        buttons.setSpacing(8)
        verbs = ("resume" if state == "paused" else "pause", "stop", "complete")
        self.buttons: list = []
        for verb in verbs:
            b = kit.text_button(verb.capitalize(), "smprimary" if verb == "complete" else "sm")
            b.setProperty("verb", verb)
            b.setProperty("session_id", active.get("id"))
            buttons.addWidget(b)
            self.buttons.append(b)
        buttons.addStretch(1)
        lay.addLayout(buttons)
        self.setProperty("search", f"running {title} {active.get('mode')} {state}".lower())


class SearchPage(Page):
    parts = ("checkins", "logs", "active", "sessions", "me")
    heading = "Search"

    def __init__(self, ctx: AppContext) -> None:
        super().__init__(ctx)
        # the search field sits right under the large title
        field = QFrame()
        field.setProperty("class", "search")
        field.setFixedHeight(36)
        row = QHBoxLayout(field)
        row.setContentsMargins(8, 0, 6, 0)
        row.setSpacing(6)
        row.addWidget(kit.Glyph("search", 17, "label3", stroke=2.0))
        self.query = QLineEdit()
        self.query.setProperty("class", "plain")
        self.query.setPlaceholderText("Search logs, modes, sessions")
        self.query.setClearButtonEnabled(True)
        self.query.textChanged.connect(self._filter)
        row.addWidget(self.query, 1)
        top = self.column.itemAt(0).layout()
        top.addSpacing(10)
        top.addWidget(field)
        self.nothing = label("", "muted", wrap=True)
        self.nothing.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.nothing.hide()
        self.column.addWidget(self.nothing)

        # 1. logging
        self.water_card = WaterCard()
        self.water_card.setProperty("search", "water hydration glass logging ml")
        self.water_label = self.water_card
        self.column.addWidget(self.water_card)

        self.log_group = kit.Group("Logging")
        self.water_row = self._log_row("+1 glass", f"{actions.WATER_GLASS_ML} ml of water", "drop", t.BLUE,
                                       self._water, "water glass hydration")
        self.mood_row = self._log_row("Mood", "How you feel right now, 1 to 5", "bubble", t.SLEEP, self._mood,
                                      "mood check-in")
        self.connection_row = self._log_row("Connection", "Real contact with people lately", "people", t.TEAL,
                                            self._connection, "connection check-in people")
        self.strength_row = self._log_row("Strength", "A session the band can't see", "dumbbell", t.FITNESS,
                                          self._strength, "strength training session")
        self.sleep_row = self._log_row("Sleep check", "Seven questions about your nights", "sleep", t.SLEEP,
                                       self._sleep_check, "sleep check insomnia")
        self.column.addWidget(self.log_group)

        self.history_group = kit.Group("This week")
        self.history = self.history_group.rows
        self.column.addWidget(self.history_group)

        # 2. modes
        self.modes = QWidget()
        self.modes.setProperty("search", " ".join(["modes focus", *(m[0].lower() for m in MODES)]))
        lay = QVBoxLayout(self.modes)
        lay.setContentsMargins(0, 0, 0, 0)
        lay.setSpacing(7)
        head = label("MODES", "head")
        head.setContentsMargins(16, 0, 16, 0)
        lay.addWidget(head)
        self.mode = kit.Segmented([m[0] for m in MODES])
        self.mode.changed.connect(self._pick_mode)
        lay.addWidget(self.mode)
        lay.addSpacing(6)
        form = kit.Group()
        self.minutes = kit.Stepper(MODES[0][3], 5, 240, 5, "{} min")
        self.minutes_row = kit.Row("Length", trailing=self.minutes)
        form.add(self.minutes_row)
        self.title = QLineEdit()
        self.title.setProperty("class", "plain")
        self.title.setPlaceholderText("What is it for?")
        self.title.setAlignment(Qt.AlignmentFlag.AlignRight)
        self.title.setFixedWidth(200)
        form.add(kit.Row("Title", trailing=self.title))
        lay.addWidget(form)
        self.mode_note = label(MODES[0][4], "foot", wrap=True)
        self.mode_note.setContentsMargins(16, 0, 16, 0)
        lay.addWidget(self.mode_note)
        lay.addSpacing(6)
        self.start = kit.text_button("", "primary")
        self.start.clicked.connect(self._start)
        lay.addWidget(self.start)
        self.column.addWidget(self.modes)
        self._pick_mode(0)

        # 3. sessions
        self.sessions = kit.Group("Sessions")
        self.sessions.body = self.sessions.rows
        self.column.addWidget(self.sessions)
        self.column.addStretch(1)

    def _log_row(self, title: str, sub: str, icon: str, accent: str, slot, search: str) -> kit.Row:
        row = kit.Row(title, sub, icon=icon, accent=accent, chevron=title != "+1 glass", clickable=True)
        if title == "+1 glass":
            row.trailing = kit.Glyph("plus", 20, "accent", stroke=2.2)
            row.layout().addWidget(row.trailing)
        row.setProperty("search", f"{title} {search} logging".lower())
        row.clicked.connect(slot)
        self.log_group.add(row)
        return row

    # -- loading ---------------------------------------------------------------

    def render(self, names: set[str]) -> None:
        if names & {"checkins", "logs", "me"}:
            self._show_logging()
        if names & {"active", "sessions"}:
            self._show_sessions()
        self._filter(self.query.text())

    def _show_logging(self) -> None:
        store = self.ctx.store
        today = actions.now_local(self.ctx).date()
        logs = store.get("logs", []) or []
        water = [x for x in logs if x.get("type", "water") == "water"]
        ml = sum(int((x.get("data") or {}).get("ml") or 0) for x in water if str(x.get("date")) == today.isoformat())
        me = store.data.get("me") if isinstance(store.data.get("me"), dict) else self.ctx.me
        goal = int(((me or {}).get("healthGoals") or {}).get("waterGoalMl") or 2000)
        glass = actions.WATER_GLASS_ML
        glasses, target = ml // glass, -(-goal // glass)
        left = max(0, target - glasses)
        note = f"{glasses} of {target} glasses" + (f" · {left} to go" if left else " · goal reached")
        self.water_card.set_values(str(ml), f"of {goal} ml", note)
        self.water_card.ring.set_fraction(ml / max(goal, 1))
        days = [today - timedelta(days=n) for n in range(6, -1, -1)]
        per_day = {d.isoformat(): 0 for d in days}
        for x in water:
            key = str(x.get("date"))
            if key in per_day:
                per_day[key] += int((x.get("data") or {}).get("ml") or 0)
        self.water_card.bars.set_data(list(per_day.values()), [d.strftime("%a")[0] for d in days], goal=goal)
        set_shown(self.water_card.bars, "logs" in store.data)
        self.water_row.set_value(f"{glasses} of {target}")

        checkins = store.get("checkins", []) or []
        for kind, row in (("mood", self.mood_row), ("connection", self.connection_row)):
            last = [c for c in checkins if c.get("kind") == kind and c.get(kind) is not None]
            last.sort(key=lambda c: str(c.get("takenAt")))
            row.set_value(f"{last[-1][kind]}/5" if last else "")
        strength = sum(int((x.get("data") or {}).get("minutes") or 0) for x in logs if x.get("type") == "strength")
        self.strength_row.set_value(f"{strength} min" if strength else "")
        sleep = [c for c in checkins if c.get("kind") == "insomnia"]
        self.sleep_row.set_value(checkin_text(sleep[-1]).removeprefix("Sleep check ") if sleep else "")

        self.history_group.clear()
        rows: list[tuple[str, str]] = []
        for c in checkins:
            rows.append((str(c.get("takenAt")), checkin_text(c)))
        for x in logs:
            rows.append((str(x.get("takenAt")), log_text(x)))
        rows.sort(reverse=True)
        if not rows:
            loaded = "checkins" in store.data or "logs" in store.data
            text = "Nothing logged this week." if loaded else store.status("checkins", "Logs")
            empty = label(store.errors.get("checkins") or text, "rowsub", wrap=True)
            empty.setContentsMargins(16, 12, 16, 12)
            self.history_group.add(empty)
        for when, what in rows[:8]:
            row = HistoryRow(what, when_text(when, today))
            row.setProperty("search", what.lower())
            self.history_group.add(row)

    def _show_sessions(self) -> None:
        store = self.ctx.store
        self.sessions.clear()
        today = actions.now_local(self.ctx).date()
        active = store.data.get("active")
        if active:
            running = RunningRow(active)
            for b in running.buttons:
                b.clicked.connect(self._session_action)
            self.sessions.add(running)
        sessions = store.data.get("sessions")
        if sessions is None:
            self.sessions.add(self._note(store.status("sessions", "Sessions")))
            return
        if not sessions and not active:
            self.sessions.add(self._note("No sessions this week. Pick a mode above and start one."))
        sessions = sorted(sessions, key=lambda s: str(s.get("startedAt") or ""), reverse=True)
        for s in sessions[:8]:
            when = when_text(str(s.get("startedAt") or ""), today)
            state = s.get("outcome") or s.get("state")
            title = str(s.get("title") or s.get("kind") or "Session")
            icon, accent = KIND_TILES.get(str(s.get("kind")), ("clock", t.BLUE))
            trailing = None
            if s.get("kind") == "recovery" and state == "completed" and not str(s.get("id")).startswith("pending-"):
                trailing = kit.text_button("Stress change", "link", tip="Your stress before and after")
                trailing.setProperty("session_id", s.get("id"))
                trailing.clicked.connect(self._stress_change)
            row = kit.Row(title, " · ".join(str(x) for x in (when, state) if x), icon=icon,
                          accent=accent, trailing=trailing)
            row.setProperty("search", f"{when} {title} {s.get('kind')} {s.get('mode')} {state}".lower())
            self.sessions.add(row)

    @staticmethod
    def _note(text: str) -> QLabel:
        note = label(text, "rowsub", wrap=True)
        note.setContentsMargins(16, 12, 16, 12)
        return note

    def _filter(self, text: str) -> None:
        needle = text.strip().lower()

        def match(w: QWidget) -> bool:
            return not needle or needle in str(w.property("search"))

        found = False
        for group in (self.log_group, self.history_group, self.sessions):
            hits = 0
            for i in range(group.count()):
                w = group.rows.itemAt(i).widget()
                if w is None:
                    continue
                if w.property("search") is None:
                    set_shown(w, not needle)
                    continue
                set_shown(w, match(w))
                hits += match(w)
            set_shown(group, not needle or hits > 0)
            group.list.update()
            found = found or hits > 0
        for w in (self.water_card, self.modes):
            set_shown(w, match(w))
            found = found or (bool(needle) and match(w))
        self.nothing.setText(f"No results for “{text.strip()}”")
        set_shown(self.nothing, bool(needle) and not found)

    # -- actions ---------------------------------------------------------------

    def _water(self) -> None:
        actions.log_water(self.ctx)

    def _strength(self) -> None:
        actions.log_strength(self.ctx, self)

    def _sleep_check(self) -> None:
        actions.sleep_check(self.ctx, self)

    def _stress_change(self) -> None:
        session_id = str(self.sender().property("session_id"))
        self.ctx.call(lambda: self.ctx.api.session_trace(session_id), self._show_stress_change)

    def _show_stress_change(self, trace: dict) -> None:
        layers.Alert.tell(self, "Stress before and after", actions.stress_change_text(trace))

    def _mood(self) -> None:
        actions.ask_checkin(self.ctx, self, "mood")

    def _connection(self) -> None:
        actions.ask_checkin(self.ctx, self, "connection")

    def _pick_mode(self, index: int) -> None:
        name, _mode, _kind, minutes, note = MODES[index]
        set_shown(self.minutes, minutes > 0)
        self.minutes_row.set_value("" if minutes else "Open-ended")
        if minutes:
            self.minutes.setValue(minutes)
        self.mode_note.setText(note)
        self.start.setText(f"Start {name.lower()}")

    def _mode(self) -> tuple[str, str, str, int, str]:
        return MODES[max(0, self.mode.index())]

    def _start(self) -> None:
        name, mode, kind, default, _note = self._mode()
        minutes = self.minutes.value() if default else None
        title = self.title.text().strip() or name
        actions.start_session(self.ctx, kind, mode, minutes=minutes, title=title)

    def _session_action(self) -> None:
        verb = str(self.sender().property("verb"))
        session_id = str(self.sender().property("session_id"))
        actions.session_action(self.ctx, session_id, verb)


def checkin_text(c: dict) -> str:
    kind = str(c.get("kind"))
    if kind == "insomnia":
        score = c.get("insomniaScore")
        if score is None and isinstance(c.get("insomnia"), list):
            score = sum(int(v) for v in c["insomnia"])
        return f"Sleep check {score}/28" if score is not None else "Sleep check"
    value = c.get(kind)
    return f"{kind.capitalize()} {value}/5" if value is not None else kind.capitalize()


def log_text(x: dict) -> str:
    data = x.get("data") or {}
    kind = str(x.get("type") or "water")
    if kind == "water":
        return f"Water {data.get('ml')} ml"
    if kind == "strength":
        return f"Strength {data.get('minutes')} min"
    return kind.capitalize()
