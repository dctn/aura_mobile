"""Search → Sessions: the one running now (round pause/play and stop buttons, Done), then this week's.

A history row is the title, its length and the day. A finished recovery session has "Stress change", its stress
before and after (DX-07).
"""

from __future__ import annotations

from datetime import datetime

from PyQt6.QtWidgets import QFrame, QHBoxLayout, QLabel, QVBoxLayout

from aura_mobile.ui import actions, kit, layers, runview
from aura_mobile.ui import tokens as t
from aura_mobile.ui.context import AppContext
from aura_mobile.ui.pages.base import Page
from aura_mobile.ui.pages.logbook import when_text
from aura_mobile.ui.widgets import label

#: the tile on a session row, by session kind
KIND_TILES: dict[str, tuple[str, str]] = {
    "focus": ("timer", t.BLUE),
    "walk": ("walk", t.FITNESS),
    "meeting": ("people", t.TEAL),
    "recovery": ("recover", t.RECOVERY),
}
#: history rows shown
HISTORY_ROWS = 10


def length_text(s: dict) -> str:
    """"30 min": the planned length, else the time between start and end; empty when neither is known."""
    planned = s.get("plannedMinutes")
    if isinstance(planned, int | float) and planned > 0:
        return f"{round(planned)} min"
    try:
        start = datetime.fromisoformat(str(s.get("startedAt")))
        end = datetime.fromisoformat(str(s.get("endedAt")))
    except ValueError:
        return ""
    return f"{max(1, round((end - start).total_seconds() / 60))} min"


class RunningCard(QFrame):
    """The session running now: its disc, title and state, then pause/play, a red stop and Done."""

    def __init__(self, active: dict) -> None:
        super().__init__()
        self.setProperty("class", "bigcard")
        lay = QVBoxLayout(self)
        lay.setContentsMargins(18, 16, 18, 16)
        lay.setSpacing(14)
        state = str(active.get("state") or "running")
        title = str(active.get("title") or active.get("kind") or "Session")
        icon, accent = KIND_TILES.get(str(active.get("kind")), ("timer", t.BLUE))
        head = QHBoxLayout()
        head.setSpacing(12)
        head.addWidget(kit.Disc(icon, runview.accent_name(accent), 40))
        words = QVBoxLayout()
        words.setSpacing(0)
        self.title = label(title, "tilename")
        self.state = label(runview.session_status(active), "tilesub")
        words.addWidget(self.title)
        words.addWidget(self.state)
        head.addLayout(words, 1)
        lay.addLayout(head)
        row = QHBoxLayout()
        row.setSpacing(16)
        paused = state == "paused"
        self.buttons: list = []
        for verb, kind in (("resume" if paused else "pause", "play" if paused else "pause"), ("stop", "stop")):
            b = kit.RoundButton(kind, 52, tip=verb.capitalize())
            b.setText(verb.capitalize())
            self.buttons.append(b)
            row.addWidget(b)
        row.addStretch(1)
        done = kit.text_button("Done", "go", tip="Count it as complete")
        done.setProperty("verb", "complete")
        self.buttons.append(done)
        row.addWidget(done)
        for b, verb in zip(self.buttons, ("resume" if paused else "pause", "stop", "complete"), strict=True):
            b.setProperty("verb", verb)
            b.setProperty("session_id", active.get("id"))
        lay.addLayout(row)


class SessionsPage(Page):
    parts = ("active", "sessions")
    heading = "Sessions"

    def __init__(self, ctx: AppContext) -> None:
        super().__init__(ctx)
        self.running: RunningCard | None = None
        self.running_box = QVBoxLayout()
        self.running_box.setContentsMargins(0, 0, 0, 0)
        self.column.addLayout(self.running_box)
        self.history = kit.Group("This week")
        self.body = self.history.rows
        self.column.addWidget(self.history)
        self.column.addStretch(1)

    def render(self, names: set[str]) -> None:
        store = self.ctx.store
        today = actions.now_local(self.ctx).date()
        if self.running is not None:
            self.running.hide()
            self.running.deleteLater()
            self.running = None
        active = store.data.get("active")
        if isinstance(active, dict):
            self.running = RunningCard(active)
            for b in self.running.buttons:
                b.clicked.connect(self._session_action)
            self.running_box.addWidget(self.running)

        self.history.clear()
        sessions = store.data.get("sessions")
        if sessions is None or not sessions:
            self.history.add(self._note(store.status("sessions", "Sessions") if sessions is None else "None yet"))
            return
        for s in sorted(sessions, key=lambda s: str(s.get("startedAt") or ""), reverse=True)[:HISTORY_ROWS]:
            title = str(s.get("title") or s.get("kind") or "Session")
            state = s.get("outcome") or s.get("state")
            day = when_text(str(s.get("startedAt") or ""), today).split(" ")[0]
            sub = " · ".join(x for x in (length_text(s), "Stopped" if state == "stopped" else "") if x)
            icon, accent = KIND_TILES.get(str(s.get("kind")), ("clock", t.BLUE))
            trailing = None
            if s.get("kind") == "recovery" and state == "completed" and not str(s.get("id")).startswith("pending-"):
                trailing = kit.text_button("Stress change", "link", tip="Your stress before and after")
                trailing.setProperty("session_id", s.get("id"))
                trailing.clicked.connect(self._stress_change)
            row = kit.Row(title, sub, day, icon=icon, accent=accent, trailing=trailing)
            row.setProperty("search", f"{day} {title} {s.get('kind')} {s.get('mode')} {state}".lower())
            self.history.add(row)

    @staticmethod
    def _note(text: str) -> QLabel:
        note = label(text, "rowsub", wrap=True)
        note.setContentsMargins(16, 12, 16, 12)
        return note

    def _session_action(self) -> None:
        verb = str(self.sender().property("verb"))
        session_id = str(self.sender().property("session_id"))
        actions.session_action(self.ctx, session_id, verb)

    def _stress_change(self) -> None:
        session_id = str(self.sender().property("session_id"))
        self.ctx.call(lambda: self.ctx.api.session_trace(session_id), self._show_stress_change)

    def _show_stress_change(self, trace: dict) -> None:
        layers.Alert.tell(self, "Stress before and after", actions.stress_change_text(trace))
