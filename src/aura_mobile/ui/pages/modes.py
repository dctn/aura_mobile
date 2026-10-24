"""Search → Modes: the Session service's four modes as tiles, then one mode's setup (the Figma "Walking" page).

The setup page is the black "Start walking" pill over a Length row and a Title row. Start shows 3-2-1 over the phone
(``runview.countdown``), then opens the run view with a Session in that mode (``actions.run_timed_session``):
Timer, Walk and Meeting count down their length; Flowtime has none and counts up.
"""

from __future__ import annotations

from collections.abc import Callable
from typing import NamedTuple

from PyQt6.QtCore import Qt
from PyQt6.QtWidgets import QLineEdit

from aura_mobile.ui import actions, kit, runview
from aura_mobile.ui import tokens as t
from aura_mobile.ui.context import AppContext
from aura_mobile.ui.pages.base import Page
from aura_mobile.ui.widgets import set_shown


class Mode(NamedTuple):
    name: str
    #: the Session service's mode and kind
    mode: str
    kind: str
    #: default minutes; 0 is open-ended
    minutes: int
    #: the start pill's words
    start: str
    icon: str
    accent: str


MODES: tuple[Mode, ...] = (
    Mode("Timer", "timer", "focus", 25, "Start timer", "timer", "blue"),
    Mode("Flowtime", "flowtime", "focus", 0, "Start flowtime", "flow", "purple"),
    Mode("Walk", "walk", "walk", 20, "Start walking", "walk", "fitness"),
    Mode("Meeting", "meeting", "meeting", 30, "Start meeting", "people", "teal"),
)


class ModesPage(Page):
    heading = "Modes"

    #: set by the shell: open the setup page for ``MODES[index]``
    on_pick: Callable[[int], None] | None = None

    def __init__(self, ctx: AppContext) -> None:
        super().__init__(ctx)
        self.tiles: list[kit.IconTile] = []
        for i, mode in enumerate(MODES):
            tile = kit.IconTile(mode.name, f"{mode.minutes} min" if mode.minutes else "Open", icon=mode.icon,
                                accent=mode.accent)
            tile.clicked.connect(lambda i=i: self._pick(i))
            self.tiles.append(tile)
        self.column.addWidget(kit.tile_grid(self.tiles))
        self.column.addStretch(1)

    def _pick(self, index: int) -> None:
        if self.on_pick is not None:
            self.on_pick(index)


class ModeSetupPage(Page):
    heading = "Timer"

    #: set by the shell: the countdown ended and the run is opening (the shell goes back to the Search tab)
    on_started: Callable[[], None] | None = None

    def __init__(self, ctx: AppContext) -> None:
        super().__init__(ctx)
        self.index = 0
        self.start = kit.StartPill("Start")
        self.start.clicked.connect(self._start)
        self.column.addWidget(self.start)
        form = kit.Group()
        self.minutes = kit.Stepper(MODES[0].minutes, 5, 240, 5, "{} min")
        self.minutes_row = kit.Row("Length", trailing=self.minutes)
        form.add(self.minutes_row)
        self.title = QLineEdit()
        self.title.setProperty("class", "plain")
        self.title.setPlaceholderText("Optional")
        self.title.setAlignment(Qt.AlignmentFlag.AlignRight)
        self.title.setFixedWidth(180)
        form.add(kit.Row("Title", trailing=self.title))
        self.column.addWidget(form)
        self.column.addStretch(1)
        self.set_mode(0)

    def set_mode(self, index: int) -> None:
        self.index = max(0, min(index, len(MODES) - 1))
        mode = MODES[self.index]
        self.set_heading(mode.name)
        self.start.setText(mode.start)
        set_shown(self.minutes, mode.minutes > 0)
        self.minutes_row.set_value("" if mode.minutes else "Open")
        if mode.minutes:
            self.minutes.setValue(mode.minutes)
        self.title.clear()

    def spec(self) -> tuple[str, int]:
        """The run's title and minutes (0 for open-ended)."""
        mode = MODES[self.index]
        return self.title.text().strip() or mode.name, self.minutes.value() if mode.minutes else 0

    def _start(self) -> None:
        window = runview.phone_of(self)
        if runview.current(window) is not None:
            self._run()  # one run at a time: this brings the running one back
            return
        runview.countdown(self, self._run)

    def _run(self) -> None:
        mode = MODES[self.index]
        title, minutes = self.spec()
        window = runview.phone_of(self)
        if self.on_started is not None:
            self.on_started()
        actions.run_timed_session(self.ctx, window, title, minutes, kind=mode.kind, mode=mode.mode,
                                  accent=t.ACCENTS.get(mode.accent, t.BLUE), night=False)
