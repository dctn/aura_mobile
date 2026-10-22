"""The full-phone timer (RunView) and the MiniBar it hides into.

A run covers the phone: Hide ⌄ and ⓘ at the top; a kicker, the title, a big clock, a line of text and a thin
progress line in the middle; End and Pause at the bottom. Variants: night (black, for the nap), a breathing pacer
(DX-07) and the wind-down's list for tomorrow (DX-03).

The view never blocks: :func:`start` returns at once and calls ``on_finish`` (the time ran out and the person closed
the "Done" sheet, or chose "Mark as done") or ``on_cancel`` (End session). Hide slides it down into the MiniBar,
8 px above the floating tab bar; a tap opens it again. With no run open, the MiniBar shows a session the server says
is running (store part ``active``).

The old dialog versions (:class:`TimerDialog`, :class:`BreathingDialog`) stay for anything that still imports them.
"""

from __future__ import annotations

import math
import time
from collections.abc import Callable
from datetime import UTC, datetime

from PyQt6.QtCore import QEasingCurve, QEvent, QObject, QPoint, QPropertyAnimation, QRectF, Qt, QTimer, pyqtSignal
from PyQt6.QtGui import QColor, QPainter, QPen
from PyQt6.QtWidgets import (
    QApplication,
    QDialog,
    QFrame,
    QHBoxLayout,
    QLabel,
    QPushButton,
    QVBoxLayout,
    QWidget,
)

from aura_mobile.ui import icons, kit, layers
from aura_mobile.ui import tokens as t
from aura_mobile.ui.pages.base import TABBAR_ROOM
from aura_mobile.ui.widgets import label, set_shown

MINIBAR_H = 52
MINIBAR_GAP = 8
#: extra room the tab pages get at the bottom while the MiniBar shows
MINIBAR_ROOM = 60
#: how often the clock is checked (it reads a monotonic deadline, so it never drifts)
TICK_MS = 200
#: breathing pacer frame time
FRAME_MS = 33

KIND_WORDS = {"recovery": "Recovery", "walk": "Move", "nap": "Nap", "focus": "Focus"}


def accent_name(colour: str) -> str:
    """The ``t.ACCENTS`` key for a colour (``"blue"`` when it is not a feature accent)."""
    for name, value in t.ACCENTS.items():
        if value.lower() == str(colour).lower():
            return name
    return "blue"


def phone_of(widget: QWidget | None) -> QWidget | None:
    """The phone window a widget sits in, past any sheet or dialog it was opened from."""
    w = widget.window() if widget is not None else QApplication.activeWindow()
    while isinstance(w, QDialog) and w.parentWidget() is not None:
        w = w.parentWidget().window()
    return w


def clock_text(seconds: float) -> str:
    s = max(0, math.ceil(seconds))
    return f"{s // 60:02d}:{s % 60:02d}"


def minutes_left_text(seconds: float) -> str:
    m = math.ceil(max(0.0, seconds) / 60)
    if seconds < 60:
        return "less than a minute left"
    return f"{m} minute{'s' if m != 1 else ''} left"


# -- painted pieces -----------------------------------------------------------------------------------------


class BreathingCircle(QWidget):
    """The pacer: a circle that grows from 0.62 to full size while you breathe in (5 s), and back out (5 s)."""

    IN_S = 5.0
    OUT_S = 5.0
    SMALL = 0.62

    def __init__(self, colour: str, size: int = 230, *, words: bool = False) -> None:
        super().__init__()
        self.setFixedHeight(size)
        self.setMinimumWidth(size)
        self._colour = colour
        self._phase = 0.0
        self.words = words

    def set_phase(self, seconds: float) -> None:
        self._phase = seconds % (self.IN_S + self.OUT_S)
        self.update()

    @property
    def inhaling(self) -> bool:
        return self._phase < self.IN_S

    def phase_text(self) -> str:
        return "Breathe in" if self.inhaling else "Breathe out"

    def scale(self) -> float:
        x = self._phase / self.IN_S if self.inhaling else 1 - (self._phase - self.IN_S) / self.OUT_S
        eased = 0.5 - 0.5 * math.cos(math.pi * x)
        return self.SMALL + (1 - self.SMALL) * eased

    def paintEvent(self, _event) -> None:
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        c = self.rect().center()
        full = min(self.width(), self.height()) / 2 - 4
        r = full * self.scale()
        accent = kit.colour(self._colour)
        track = QColor(accent)
        track.setAlphaF(0.10)
        p.setPen(Qt.PenStyle.NoPen)
        p.setBrush(track)
        p.drawEllipse(QRectF(c.x() - full, c.y() - full, 2 * full, 2 * full))
        fill = QColor(accent)
        fill.setAlphaF(0.16 if t.current().dark else 0.20)
        p.setBrush(fill)
        p.setPen(QPen(accent, 2.5))
        p.drawEllipse(QRectF(c.x() - r, c.y() - r, 2 * r, 2 * r))
        core = QColor(accent)
        core.setAlphaF(0.55)
        p.setPen(Qt.PenStyle.NoPen)
        p.setBrush(core)
        p.drawEllipse(QRectF(c.x() - 7, c.y() - 7, 14, 14))
        if self.words:
            p.setPen(QPen(accent))
            p.drawText(self.rect().adjusted(0, 60, 0, 0), Qt.AlignmentFlag.AlignCenter, self.phase_text())


class _HideButton(QPushButton):
    """"Hide ⌄": the text, then a chevron painted in the same colour."""

    def __init__(self, key: str) -> None:
        super().__init__("Hide")
        self.key = key
        self.setProperty("class", "runhide")
        self.setCursor(Qt.CursorShape.PointingHandCursor)
        self.setToolTip("Keep it running in the bar above the tabs")

    def paintEvent(self, event) -> None:
        super().paintEvent(event)
        p = QPainter(self)
        text_w = self.fontMetrics().horizontalAdvance(self.text())
        x = 6 + text_w + 4
        p.drawPixmap(QPoint(x, (self.height() - 16) // 2), icons.pixmap("down", kit.colour(self.key), 16, stroke=2.4))


# -- the run view ---------------------------------------------------------------------------------------------


class RunView(QFrame):
    """One timed session over the whole phone. ``on_finish``/``on_cancel`` are plain callables, called once."""

    def __init__(self, window: QWidget, title: str, minutes: float, *, note: str = "", accent: str = t.RECOVERY,
                 kind: str = "recovery", breathing: bool = False, night: bool = False, panel: QWidget | None = None,
                 on_finish: Callable[[], None] | None = None, on_cancel: Callable[[], None] | None = None) -> None:
        super().__init__(window)
        self.setObjectName("runview")
        self.setProperty("night", night)
        self.title_text, self.minutes, self.accent, self.kind = title, minutes, accent, kind
        self.breathing, self.night, self.panel = breathing, night, panel
        self.on_finish, self.on_cancel = on_finish, on_cancel
        self.tint = accent_name(accent)
        self.collapsed = False
        self.paused = False
        #: the time ran out or the run was ended; ``settled`` once its callback has run
        self.ended = False
        self.settled = False
        self._closing = False
        self.done_sheet: layers.Sheet | None = None
        self._total = max(1.0, float(minutes) * 60)
        self._left = self._total
        self._ends = time.monotonic() + self._total
        self._breath = 0.0
        self._last_frame = time.monotonic()

        lay = QVBoxLayout(self)
        lay.setContentsMargins(t.GUTTER, 14, t.GUTTER, 30)
        lay.setSpacing(0)
        soft = t.NIGHT_2 if night else "accent"
        top = QHBoxLayout()
        top.setContentsMargins(0, 0, 0, 0)
        self.hide_button = _HideButton(soft)
        self.hide_button.clicked.connect(self.collapse)
        top.addWidget(self.hide_button)
        top.addStretch(1)
        self.info_button = kit.IconButton("info", "iconbtn", key=soft, size=22, tip="About this timer")
        self.info_button.clicked.connect(self.show_info)
        top.addWidget(self.info_button)
        lay.addLayout(top)
        lay.addStretch(2 if panel is None else 1)

        word = "Breathe" if breathing else KIND_WORDS.get(kind, kind.title() or "Session")
        whole = int(minutes) if float(minutes).is_integer() else minutes
        self.kicker = label(f"{word} · {whole} min".upper(), "kicker")
        self.kicker.setProperty("tint", self.tint)
        self.title = label(title, "runtitle", wrap=True)
        for w in (self.kicker, self.title):
            w.setAlignment(Qt.AlignmentFlag.AlignCenter)
            lay.addWidget(w)
        lay.addSpacing(6)

        self.circle: BreathingCircle | None = None
        self.phase: QLabel | None = None
        if breathing:
            lay.addSpacing(14)
            self.circle = BreathingCircle(accent if self.tint != "blue" else "accent")
            lay.addWidget(self.circle)
            self.phase = label("Breathe in", "runphase")
            self.phase.setAlignment(Qt.AlignmentFlag.AlignCenter)
            lay.addWidget(self.phase)
            note = "In through the nose, out slowly: five seconds each."

        self.clock = label("", "clock")
        self.clock.setAlignment(Qt.AlignmentFlag.AlignCenter)
        lay.addWidget(self.clock)
        self.note = label(note, "runnote", wrap=True)
        self.note.setAlignment(Qt.AlignmentFlag.AlignCenter)
        set_shown(self.note, bool(note))
        self.note.setContentsMargins(8, 0, 8, 0)
        lay.addWidget(self.note)
        lay.addSpacing(22)
        bar_row = QHBoxLayout()
        bar_row.setContentsMargins(44, 0, 44, 0)
        self.bar = kit.Bar(accent if self.tint != "blue" else "accent", 4)
        bar_row.addWidget(self.bar)
        lay.addLayout(bar_row)
        if panel is not None:
            lay.addSpacing(26)
            lay.addWidget(panel)
        lay.addStretch(3 if panel is None else 1)

        buttons = QHBoxLayout()
        buttons.setSpacing(12)
        self.end_button = kit.text_button("End", "grey")
        self.end_button.clicked.connect(self.ask_end)
        self.pause_button = kit.text_button("Pause", "primary")
        self.pause_button.setProperty("tint", self.tint)
        self.pause_button.clicked.connect(self.toggle_pause)
        for b in (self.end_button, self.pause_button):
            b.setMinimumHeight(50)
            buttons.addWidget(b, 1)
        lay.addLayout(buttons)

        self._tick_timer = QTimer(self)
        self._tick_timer.timeout.connect(self._tick)
        self._frame_timer = QTimer(self)
        self._frame_timer.timeout.connect(self._frame)
        self._slide = QPropertyAnimation(self, b"pos", self)
        self._slide.setDuration(320)
        self._slide.setEasingCurve(QEasingCurve.Type.OutCubic)
        self._slide.finished.connect(self._slid)
        self._render()
        self.setGeometry(0, 0, window.width(), window.height())
        self.hide()
        window.installEventFilter(self)

    # -- time ----------------------------------------------------------------

    @property
    def left_seconds(self) -> float:
        return self._left

    @property
    def elapsed_seconds(self) -> int:
        return int(self._total - self._left)

    def start(self) -> None:
        self._ends = time.monotonic() + self._left
        self._tick_timer.start(TICK_MS)
        if self.circle is not None:
            self._last_frame = time.monotonic()
            self._frame_timer.start(FRAME_MS)
        self.expand()

    def _tick(self) -> None:
        if self.paused or self.ended:
            return
        self._left = max(0.0, self._ends - time.monotonic())
        self._render()
        if self._left <= 0:
            self.time_up()

    def _frame(self) -> None:
        now = time.monotonic()
        if not self.paused and not self.collapsed:
            self._breath += now - self._last_frame
            self.circle.set_phase(self._breath)
            text = self.circle.phase_text()
            if self.phase.text() != text:
                self.phase.setText(text)
        self._last_frame = now

    def _render(self) -> None:
        self.clock.setText(clock_text(self._left))
        self.bar.set_fraction(1 - self._left / self._total)
        bar = getattr(self.window(), "minibar", None)
        if isinstance(bar, MiniBar) and bar.isVisible():
            bar.refresh()

    def toggle_pause(self) -> None:
        if self.ended:
            return
        self.paused = not self.paused
        if self.paused:
            self._left = max(0.0, self._ends - time.monotonic())
        else:
            self._ends = time.monotonic() + self._left
        self.pause_button.setText("Resume" if self.paused else "Pause")
        self._render()

    def time_up(self) -> None:
        """The time ran out: a beep, then the "Done" sheet; closing it finishes the run."""
        if self.ended:
            return
        self.ended = True
        self._left = 0.0
        self._stop_timers()
        self._render()
        QApplication.beep()
        if self.collapsed:
            self.expand()
        self.done_sheet = DoneSheet(self)
        self.done_sheet.finished.connect(self._done_closed)
        self.done_sheet.open()

    def _done_closed(self, _code: int) -> None:
        self.complete()

    # -- ending --------------------------------------------------------------

    def ask_end(self) -> None:
        """End early: an action sheet; "Mark as done" completes the run, "End session" stops it."""
        if self.ended:
            return
        if not self.paused:
            self._left = max(0.0, self._ends - time.monotonic())
        choice = layers.ActionSheet.pick(self, f"End now? {minutes_left_text(self._left).capitalize()}.",
                                         [("Mark as done", ""), ("End session", "danger")])
        if choice == 0:
            self.complete()
        elif choice == 1:
            self.cancel()

    def complete(self) -> None:
        self._finish(self.on_finish)

    def cancel(self) -> None:
        self._finish(self.on_cancel)

    def _finish(self, callback: Callable[[], None] | None) -> None:
        if self.settled:
            return
        self.settled = self.ended = True
        self._stop_timers()
        self.on_finish = self.on_cancel = None
        self._close()
        if callback is not None:
            callback()

    def discard(self) -> None:
        """Drop the run without telling anyone (signed out under it)."""
        self.settled = self.ended = True
        self.on_finish = self.on_cancel = None
        self._stop_timers()
        self._close(animate=False)

    def _stop_timers(self) -> None:
        self._tick_timer.stop()
        self._frame_timer.stop()

    def _close(self, *, animate: bool = True) -> None:
        window = self.window()
        if getattr(window, "run_view", None) is self:
            window.run_view = None
        window.removeEventFilter(self)
        self._closing = True
        if animate and self.isVisible() and not self.collapsed:
            self._slide_to(self.height())
        else:
            self.hide()
            self.deleteLater()
        sync(window)

    # -- showing -------------------------------------------------------------

    def expand(self) -> None:
        """Show the view (slides up); the MiniBar hides."""
        self.collapsed = False
        window = self.window()
        self.setGeometry(0, window.height(), window.width(), window.height())
        self.show()
        self.raise_()
        self._slide_to(0)
        sync(window)

    def collapse(self) -> None:
        """Hide ⌄: the run keeps going in the MiniBar."""
        if self.collapsed:
            return
        self.collapsed = True
        self._slide_to(self.height())
        sync(self.window())

    def _slide_to(self, y: int) -> None:
        self._slide.stop()
        self._slide.setStartValue(self.pos())
        self._slide.setEndValue(QPoint(0, y))
        self._slide.start()

    def _slid(self) -> None:
        if getattr(self, "_closing", False):
            self.hide()
            self.deleteLater()
        elif self.collapsed:
            self.hide()

    def eventFilter(self, obj: QObject, event: QEvent) -> bool:
        if event.type() == QEvent.Type.Resize and obj is self.window() and not self.collapsed:
            self.setGeometry(0, 0, obj.width(), obj.height())
        return False

    def show_info(self) -> None:
        whole = int(self.minutes) if float(self.minutes).is_integer() else self.minutes
        parts = [f"{whole} minutes, timed on this phone and saved as a session."]
        if self.note.text():
            parts.insert(0, self.note.text())
        parts.append("Hide keeps it running above the tabs. End lets you stop early or count it as done.")
        layers.Alert.tell(self, self.title_text, "\n\n".join(parts))

    def text(self) -> str:
        return f"{self.title_text} · {clock_text(self._left)}"


class _CheckBadge(QWidget):
    """A round accent badge with a white check, for the "Done" sheet."""

    def __init__(self, key: str, size: int = 64) -> None:
        super().__init__()
        self.key = key
        self.setFixedSize(size, size)

    def paintEvent(self, _event) -> None:
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        halo = kit.colour(self.key)
        halo.setAlphaF(0.18)
        p.setPen(Qt.PenStyle.NoPen)
        p.setBrush(halo)
        p.drawEllipse(QRectF(self.rect()))
        inner = QRectF(self.rect()).adjusted(7, 7, -7, -7)
        p.setBrush(kit.colour(self.key))
        p.drawEllipse(inner)
        side = round(inner.width() * 0.56)
        off = (self.width() - side) / 2
        p.drawPixmap(QPoint(round(off), round(off)), icons.pixmap("check", QColor("white"), side, stroke=2.8))


class DoneSheet(layers.Sheet):
    """"Done": a check, what was finished, and a Done button."""

    def __init__(self, run: RunView) -> None:
        super().__init__(run, "", done=None, cancel=None, footer="Done")
        col = QVBoxLayout()
        col.setSpacing(6)
        col.setContentsMargins(0, 12, 0, 4)
        tile_row = QHBoxLayout()
        tile_row.addStretch(1)
        tile_row.addWidget(_CheckBadge(run.accent if run.tint != "blue" else "accent"))
        tile_row.addStretch(1)
        col.addLayout(tile_row)
        col.addSpacing(8)
        whole = int(run.minutes) if float(run.minutes).is_integer() else run.minutes
        self.heading = label(f"{run.title_text} done", "runtitle", wrap=True)
        lines = [f"{whole} minute{'s' if whole != 1 else ''}. Saved to your sessions."]
        if run.breathing:
            lines.append("Your stress change shows in a moment.")
        self.text = label(" ".join(lines), "runnote", wrap=True)
        for w in (self.heading, self.text):
            w.setAlignment(Qt.AlignmentFlag.AlignCenter)
            col.addWidget(w)
        self.body.addLayout(col)


# -- the mini bar ---------------------------------------------------------------------------------------------


class MiniBar(QFrame):
    """52 high, 8 px above the floating tab bar: the hidden run (title, time left, pause) or the server's running
    session. A tap opens the run again (or the Search tab for a server session)."""

    def __init__(self, window: QWidget) -> None:
        super().__init__(window)
        self.setObjectName("minibar")
        self.setFixedHeight(MINIBAR_H)
        self.setCursor(Qt.CursorShape.PointingHandCursor)
        self.host = window
        self.session: dict | None = None
        lay = QHBoxLayout(self)
        lay.setContentsMargins(10, 0, 8, 0)
        lay.setSpacing(10)
        self.tile = kit.Tile("timer", t.RECOVERY, 32)
        lay.addWidget(self.tile)
        text = QVBoxLayout()
        text.setSpacing(0)
        text.setContentsMargins(0, 0, 0, 0)
        self.title = label("", "rowtitle-strong")
        self.sub = label("", "rowsub")
        text.addStretch(1)
        text.addWidget(self.title)
        text.addWidget(self.sub)
        text.addStretch(1)
        lay.addLayout(text, 1)
        self.pause_button = kit.IconButton("pause", "iconbtn", key="label", size=26, tip="Pause")
        self.pause_button.setFixedSize(40, 40)
        self.pause_button.clicked.connect(self._pause)
        lay.addWidget(self.pause_button)
        self.open_glyph = kit.Glyph("chevron", 14, "label3", stroke=2.4)
        lay.addWidget(self.open_glyph)
        self._timer = QTimer(self)
        self._timer.timeout.connect(self.refresh)
        self.hide()
        window.installEventFilter(self)

    def run(self) -> RunView | None:
        return current(self.host)

    def refresh(self) -> None:
        run = self.run()
        if run is not None:
            self.tile.name, self.tile.accent = ("wind" if run.breathing else "sleep" if run.night else "timer",
                                                run.accent)
            self.title.setText(run.title_text)
            self.sub.setText("Paused · " + clock_text(run.left_seconds) if run.paused
                             else f"{clock_text(run.left_seconds)} left")
            self.pause_button.name = "play" if run.paused else "pause"
            self.pause_button.setToolTip("Resume" if run.paused else "Pause")
            self.pause_button.show()
            self.open_glyph.hide()
        elif self.session is not None:
            self.tile.name, self.tile.accent = "timer", t.BLUE
            self.title.setText(str(self.session.get("title") or self.session.get("kind") or "Session").capitalize())
            self.sub.setText(session_status(self.session))
            self.pause_button.hide()
            self.open_glyph.show()
        self.tile.update()
        self.pause_button.update()

    def _pause(self) -> None:
        run = self.run()
        if run is not None:
            run.toggle_pause()
            self.refresh()

    def place(self) -> None:
        w = self.host
        tabbar = getattr(w, "tabbar", None)
        g = tabbar.geometry() if tabbar is not None else None
        if g is not None and g.width() > 0 and g.height() > 0:
            x, width, bottom = g.x(), g.width(), g.top()
            if width >= w.width():  # a tab bar that runs edge to edge: keep the bar inset anyway
                x, width = t.TABBAR_INSET, w.width() - 2 * t.TABBAR_INSET
        else:
            x, width = t.TABBAR_INSET, w.width() - 2 * t.TABBAR_INSET
            bottom = w.height() - t.TABBAR_BOTTOM - t.TABBAR_H
        self.setGeometry(x, bottom - MINIBAR_GAP - MINIBAR_H, width, MINIBAR_H)

    def showEvent(self, event) -> None:
        super().showEvent(event)
        self._timer.start(1000)

    def hideEvent(self, event) -> None:
        super().hideEvent(event)
        self._timer.stop()

    def mouseReleaseEvent(self, event) -> None:
        if event.button() == Qt.MouseButton.LeftButton and self.rect().contains(event.position().toPoint()):
            self.open()
        super().mouseReleaseEvent(event)

    def open(self) -> None:
        run = self.run()
        if run is not None:
            run.expand()
        elif self.session is not None and hasattr(self.host, "show_tab"):
            self.host.show_tab(2)  # Search lists the running session with its buttons

    def eventFilter(self, obj: QObject, event: QEvent) -> bool:
        if event.type() == QEvent.Type.Resize and obj is self.host:
            self.place()
        return False

    def _on_store(self, names: object) -> None:
        if "active" in names:
            sync(self.host)


def session_status(session: dict) -> str:
    """"12 min left", "Running · 4 min" or "Paused" for a session the server says is open."""
    state = str(session.get("state") or "")
    if state == "paused":
        return "Paused"
    if state == "starting":
        return "Starting…"
    try:
        started = datetime.fromisoformat(str(session.get("startedAt")))
        if started.tzinfo is None:
            started = started.replace(tzinfo=UTC)
        ran = max(0, int((datetime.now(UTC) - started).total_seconds() // 60))
    except ValueError:
        return "Running"
    planned = session.get("plannedMinutes")
    if isinstance(planned, int | float) and planned > 0:
        left = int(planned) - ran
        return f"{left} min left" if left > 0 else "Time is up"
    return f"Running · {ran} min"


def _server_session(window: QWidget) -> dict | None:
    ctx = getattr(window, "ctx", None)
    store = getattr(ctx, "store", None)
    active = store.data.get("active") if store is not None else None
    if isinstance(active, dict) and active.get("state") not in ("ended", "completed", "stopped"):
        return active
    return None


# -- module functions (the shell's contract) ------------------------------------------------------------------


def current(window: QWidget | None) -> RunView | None:
    run = getattr(window, "run_view", None) if window is not None else None
    return run if isinstance(run, RunView) and not run.settled else None


def install(window: QWidget) -> None:
    """Create ``window.minibar`` (hidden) on the phone window. Called once by the shell."""
    window.run_view = None
    window.minibar = MiniBar(window)
    ctx = getattr(window, "ctx", None)
    if ctx is not None and hasattr(ctx, "store"):
        ctx.store.updated.connect(window.minibar._on_store)
    sync(window)


def sync(window: QWidget) -> None:
    """Show or hide the MiniBar for the current page (hidden on pushed pages and when signed out)."""
    bar = getattr(window, "minibar", None)
    if not isinstance(bar, MiniBar):
        return
    run = current(window)
    ctx = getattr(window, "ctx", None)
    signed_in = bool(getattr(getattr(ctx, "session", None), "is_signed_in", True))
    if run is not None and not signed_in:
        run.discard()
        run = None
    tabbar = getattr(window, "tabbar", None)
    tabs_on = tabbar is not None and not tabbar.isHidden()
    if run is not None:
        bar.session = None
        wanted = run.collapsed
    else:
        bar.session = _server_session(window) if signed_in else None
        wanted = bar.session is not None
    shown = wanted and tabs_on
    if shown:
        bar.place()
        bar.refresh()
        bar.show()
        bar.raise_()
    else:
        bar.hide()
    _room_for_bar(window, shown)


def _room_for_bar(window: QWidget, on: bool) -> None:
    """While the MiniBar shows, the tab pages get its height in extra room at the bottom, so it hides nothing."""
    bar = window.minibar
    if getattr(bar, "room_on", False) == on:
        return
    bar.room_on = on
    room = TABBAR_ROOM + (MINIBAR_ROOM if on else 0)
    for name in ("home", "recovery", "search"):
        page = getattr(window, name, None)
        if hasattr(page, "set_bottom_room"):
            page.set_bottom_room(room)


def start(parent: QWidget | None, title: str, minutes: float, *, note: str = "", accent: str = t.RECOVERY,
          kind: str = "recovery", breathing: bool = False, night: bool = False, panel: QWidget | None = None,
          on_finish: Callable[[], None] | None = None, on_cancel: Callable[[], None] | None = None) -> RunView:
    """Open a run over the phone that holds ``parent`` and start its clock. Returns at once."""
    window = phone_of(parent)
    if window is None:
        raise RuntimeError("No phone window to show the timer in")
    if not hasattr(window, "minibar"):
        install(window)
    view = RunView(window, title, minutes, note=note, accent=accent, kind=kind, breathing=breathing, night=night,
                   panel=panel, on_finish=on_finish, on_cancel=on_cancel)
    window.run_view = view
    view.start()
    return view


# -- the old dialogs, kept for compatibility (nothing runs a session with them now) -------------------------------


class TimerDialog(layers.Sheet):
    """A countdown in a sheet; accepted when the time runs out or on "Finish now"."""

    finished_ok = pyqtSignal()

    def __init__(self, parent: QWidget | None, title: str, minutes: int, note: str = "", accent: str = t.BLUE):
        super().__init__(parent, title, done=None, cancel="Cancel", footer="Finish now")
        self._total = max(1, int(minutes * 60))
        self._left = self._total
        self.skip = self.footer_button
        self.skip.clicked.disconnect()
        self.skip.clicked.connect(self._done)
        if note:
            n = label(note, "runnote", wrap=True)
            n.setAlignment(Qt.AlignmentFlag.AlignCenter)
            self.body.addWidget(n)
        self.clock = label("", "clock")
        self.clock.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.body.addWidget(self.clock)
        self.bar = kit.Bar(accent, 4)
        self.body.addWidget(self.bar)
        self._timer = QTimer(self)
        self._timer.timeout.connect(self._tick)
        self._timer.start(1000)
        self._render()

    @property
    def elapsed_seconds(self) -> int:
        return self._total - self._left

    def _tick(self) -> None:
        self._left -= 1
        if self._left <= 0:
            self._left = 0
            QApplication.beep()
            self._done()
            return
        self._render()

    def _render(self) -> None:
        self.clock.setText(clock_text(self._left))
        self.bar.set_fraction(1 - self._left / self._total)

    def _done(self) -> None:
        self._timer.stop()
        self.finished_ok.emit()
        self.accept()


class BreathingDialog(TimerDialog):
    def __init__(self, parent: QWidget | None, minutes: int = 5, accent: str = t.STRESS):
        super().__init__(parent, "Slow breathing", minutes, "In through the nose, out slowly. Five seconds each.",
                         accent)
        self.circle = BreathingCircle(accent, 200, words=True)
        self.body.insertWidget(0, self.circle)
        self._t = 0.0
        self._anim = QTimer(self)
        self._anim.timeout.connect(self._breathe)
        self._anim.start(50)

    def _breathe(self) -> None:
        self._t += 0.05
        self.circle.set_phase(self._t)
