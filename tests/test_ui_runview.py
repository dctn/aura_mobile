"""The run view (full-phone timer), its MiniBar, the phone's layers and the rebuilt sheets. Offscreen, no network:
a bare phone window and a fake context whose writes answer at once (or when the test lets them)."""

from __future__ import annotations

import os

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from collections.abc import Callable
from datetime import date

import pytest
from PyQt6.QtCore import QTimer
from PyQt6.QtWidgets import QApplication, QDialog, QFrame, QPushButton, QWidget

from aura_mobile.ui import actions, kit, layers, runview, sheets
from aura_mobile.ui import tokens as t
from aura_mobile.ui.pages.base import TABBAR_ROOM

#: no widget may flash up as its own window (see conftest.stray_windows)
pytestmark = pytest.mark.usefixtures("stray_windows")


class Toast:
    def __init__(self) -> None:
        self.said: list[str] = []

    def emit(self, text: str) -> None:
        self.said.append(text)


class Store:
    def __init__(self) -> None:
        self.data: dict = {}

    def patch(self, part: str, change: Callable) -> None:
        self.data[part] = change(self.data.get(part))


class Api:
    time_zone = "Asia/Kolkata"

    def __init__(self) -> None:
        self.verbs: list[tuple[str, str]] = []
        self.started: list[str] = []
        self.tasks: list[str] = []

    def start_session(self, kind, mode, *, minutes=None, title=""):
        self.started.append(title)
        return {"session": {"id": f"s{len(self.started)}", "title": title, "state": "running"}}

    def session_action(self, session_id, verb):
        self.verbs.append((session_id, verb))
        return {}

    def create_task(self, title, *, horizon="today"):
        self.tasks.append(title)
        return {}


class Ctx:
    """Just what ``actions`` needs. ``hold`` keeps writes back until :meth:`flush`, like a slow server."""

    def __init__(self) -> None:
        self.api = Api()
        self.store = Store()
        self.toast = Toast()
        self.hold = False
        self.waiting: list[Callable[[], None]] = []

    def write(self, fn, _area, on_done=None) -> None:
        def run() -> None:
            result = fn()
            if on_done is not None:
                on_done(result)

        if self.hold:
            self.waiting.append(run)
        else:
            run()

    def flush(self) -> None:
        while self.waiting:
            self.waiting.pop(0)()


class Page:
    def __init__(self) -> None:
        self.room = TABBAR_ROOM

    def set_bottom_room(self, room: int) -> None:
        self.room = room


@pytest.fixture
def window():
    app = QApplication.instance() or QApplication([])
    w = QWidget()
    w.setObjectName("phone")
    w.setFixedSize(t.PHONE_WIDTH, t.PHONE_HEIGHT)
    tabbar = QFrame(w)
    tabbar.setObjectName("tabbar")
    tabbar.setGeometry(16, t.PHONE_HEIGHT - 12 - 62, t.PHONE_WIDTH - 32, 62)
    w.tabbar = tabbar
    w.home, w.recovery, w.search = Page(), Page(), Page()
    runview.install(w)
    w.show()
    yield w
    run = runview.current(w)
    if run is not None:
        run.discard()
    for top in app.topLevelWidgets():
        if isinstance(top, QDialog) and top.isVisible():
            top.reject()
    w.close()
    w.deleteLater()
    app.processEvents()


@pytest.fixture
def ctx():
    return Ctx()


def later(fn: Callable[[], None], ms: int = 0) -> None:
    QTimer.singleShot(ms, fn)


def open_layer(kind: type) -> QDialog:
    return next(w for w in QApplication.topLevelWidgets() if isinstance(w, kind) and w.isVisible())


# -- run view -------------------------------------------------------------------------------------------------


def test_run_finish_completes_then_runs_then_and_after(window, ctx):
    calls: list[str] = []
    ran = actions.run_timed_session(ctx, window, "Nap", 10, accent=t.SLEEP, then=lambda: calls.append("then"),
                                    after=lambda sid: calls.append(f"after {sid}"))
    assert ran is None  # non-blocking: the run view is open, nothing settled yet
    view = runview.current(window)
    assert view is not None and view.night and view.isVisible()
    assert ctx.api.verbs == [] and calls == []
    view.time_up()
    assert view.done_sheet is not None and view.done_sheet.isVisible()
    assert ctx.api.verbs == []  # settles when the "Done" sheet closes
    view.done_sheet.accept()
    assert ctx.api.verbs == [("s1", "complete")]
    assert calls == ["then", "after s1"] or calls == ["after s1", "then"]
    assert runview.current(window) is None
    assert ctx.store.data["active"] is None and ctx.store.data["sessions"][0]["outcome"] == "completed"


def test_run_finished_before_the_start_answers_completes_when_it_does(window, ctx):
    ctx.hold = True
    actions.run_timed_session(ctx, window, "Move break", 3, kind="walk")
    runview.current(window).complete()
    assert ctx.api.verbs == []
    ctx.flush()
    assert ctx.api.verbs == [("s1", "complete")]


def test_end_session_stops_and_skips_then(window, ctx, monkeypatch):
    calls: list[str] = []
    monkeypatch.setattr(layers.ActionSheet, "pick", staticmethod(lambda *a, **k: 1))
    actions.run_timed_session(ctx, window, "Recovery break", 10, then=lambda: calls.append("then"))
    view = runview.current(window)
    assert not view.night
    view.ask_end()
    assert ctx.api.verbs == [("s1", "stop")]
    assert calls == []
    assert runview.current(window) is None


def test_end_sheet_can_mark_it_done_or_be_cancelled(window, ctx, monkeypatch):
    picks = iter([None, 0])
    monkeypatch.setattr(layers.ActionSheet, "pick", staticmethod(lambda *a, **k: next(picks)))
    actions.run_timed_session(ctx, window, "Recovery break", 10)
    view = runview.current(window)
    view.ask_end()  # Cancel: keeps running
    assert runview.current(window) is view and ctx.api.verbs == []
    view.ask_end()  # Mark as done
    assert ctx.api.verbs == [("s1", "complete")]


def test_end_sheet_says_how_long_is_left(window, monkeypatch):
    seen: list[str] = []

    def pick(_parent, title, actions_, cancel="Cancel"):
        seen.append(title)
        assert ("End session", "danger") in actions_
        return None

    monkeypatch.setattr(layers.ActionSheet, "pick", staticmethod(pick))
    view = runview.start(window, "Nap", 10)
    view.ask_end()
    assert seen and seen[0].startswith("End now?") and "minutes left" in seen[0]


def test_hide_shows_the_minibar_and_a_tap_reopens(window, ctx):
    actions.run_timed_session(ctx, window, "Recovery break", 10)
    view = runview.current(window)
    assert window.minibar.isHidden()
    view.collapse()
    assert view.collapsed and window.minibar.isVisible()
    assert window.minibar.title.text() == "Recovery break" and "left" in window.minibar.sub.text()
    assert window.minibar.geometry().bottom() < window.tabbar.geometry().top()
    assert window.home.room == TABBAR_ROOM + runview.MINIBAR_ROOM  # the bar covers nothing
    window.tabbar.hide()
    runview.sync(window)
    assert window.minibar.isHidden()
    assert window.search.room == TABBAR_ROOM
    window.tabbar.show()
    runview.sync(window)
    assert window.minibar.isVisible()
    window.minibar.open()
    assert not view.collapsed and window.minibar.isHidden()
    assert ctx.api.verbs == []  # hiding and reopening never ends the session


def test_pause_stops_the_clock(window):
    view = runview.start(window, "Recovery break", 10)
    view.toggle_pause()
    left = view.left_seconds
    view._tick()
    assert view.paused and view.left_seconds == left and view.pause_button.text() == "Resume"
    view.toggle_pause()
    assert view.pause_button.text() == "Pause"


def test_one_run_at_a_time(window, ctx):
    actions.run_timed_session(ctx, window, "Nap", 10)
    first = runview.current(window)
    first.collapse()
    actions.run_timed_session(ctx, window, "Move break", 3)
    assert runview.current(window) is first and not first.collapsed
    assert ctx.api.started == ["Nap"]
    assert "still running" in ctx.toast.said[-1]


def test_breathing_pacer_grows_in_and_shrinks_out(window):
    view = runview.start(window, "Slow breathing", 5, accent=t.STRESS, breathing=True)
    circle = view.circle
    circle.set_phase(0)
    assert circle.inhaling and circle.scale() == pytest.approx(0.62)
    circle.set_phase(5)
    assert not circle.inhaling and circle.phase_text() == "Breathe out" and circle.scale() == pytest.approx(1)
    circle.set_phase(10)
    assert circle.phase_text() == "Breathe in"


def test_wind_down_saves_the_list_when_done(window, ctx):
    actions.wind_down(ctx, window, t.SLEEP, then=lambda: None)
    view = runview.current(window)
    assert isinstance(view.panel, sheets.WindDownDialog)
    view.panel.todo.setPlainText("Reply to Ana\n\nBook the dentist")
    view.complete()
    assert ctx.api.tasks == ["Reply to Ana", "Book the dentist"]


def test_wind_down_ended_early_saves_nothing(window, ctx):
    actions.wind_down(ctx, window, t.SLEEP)
    view = runview.current(window)
    view.panel.todo.setPlainText("Reply to Ana")
    view.cancel()
    assert ctx.api.tasks == [] and ctx.api.verbs == [("s1", "stop")]


def test_minibar_shows_a_server_session(window, ctx):
    window.ctx = ctx
    ctx.store.data["active"] = {"id": "s9", "title": "Focus", "state": "running", "plannedMinutes": 25,
                                "startedAt": "2020-01-01T00:00:00+00:00"}
    runview.sync(window)
    assert window.minibar.isVisible() and window.minibar.title.text() == "Focus"
    assert window.minibar.sub.text() == "Time is up"
    ctx.store.data["active"] = None
    runview.sync(window)
    assert window.minibar.isHidden()


def test_session_status_words():
    assert runview.session_status({"state": "paused"}) == "Paused"
    assert runview.session_status({"state": "running", "startedAt": "bad"}) == "Running"


# -- layers ---------------------------------------------------------------------------------------------------


def test_alert_result_codes(window):
    a = layers.Alert(window, "Delete?", "Gone for good.", (("Cancel", "cancel"), ("Delete", "danger")))
    later(lambda: a.buttons[1].click())
    assert a.exec() == QDialog.DialogCode.Accepted and a.choice == 1
    b = layers.Alert(window, "Delete?", "", (("Cancel", "cancel"), ("Delete", "danger")))
    later(lambda: b.buttons[0].click())
    assert b.exec() == QDialog.DialogCode.Rejected and b.choice == 0


def test_alert_ask_and_confirm(window):
    later(lambda: open_layer(layers.Alert).buttons[1].click(), 50)
    assert layers.Alert.ask(window, "Sure?", ok="Yes")
    later(lambda: open_layer(layers.Alert).buttons[0].click(), 50)
    assert not sheets.confirm(window, "Sleep check", "Take it?", "Take the sleep check")


def test_action_sheet_pick(window):
    def press_end() -> None:
        sheet = open_layer(layers.ActionSheet)
        next(b for b in sheet.findChildren(QPushButton) if b.text() == "End session").click()

    later(press_end, 50)
    assert layers.ActionSheet.pick(window, "End now?", ["Mark as done", ("End session", "danger")]) == 1
    later(lambda: open_layer(layers.ActionSheet).cancel_button.click(), 50)
    assert layers.ActionSheet.pick(window, "End now?", ["Mark as done"]) is None


def test_sheet_result_codes(window):
    s = layers.Sheet(window, "Title")
    later(lambda: s.done_button.click())
    assert s.exec() == QDialog.DialogCode.Accepted
    s = layers.Sheet(window, "Title")
    later(lambda: s.cancel_button.click())
    assert s.exec() == QDialog.DialogCode.Rejected


def test_switch_toggles(window):
    s = kit.Switch()
    seen: list[bool] = []
    s.toggled.connect(seen.append)
    s.click()
    assert s.isChecked() and seen == [True]
    s.setChecked(False)
    assert not s.isChecked() and seen == [True, False]


# -- sheets -----------------------------------------------------------------------------------------------------


def test_sleep_check_steps_through_seven_questions(window):
    d = sheets.SleepCheckDialog(window)
    assert d.title.text() == "1 of 7" and not d.done_button.isEnabled()
    for i in range(7):
        d.choose(i % 5)
        if i < 6:
            d.accept()
    assert d.title.text() == "7 of 7" and d.done_button.text() == "See result"
    d.reject()  # Back
    assert d.title.text() == "6 of 7"
    assert d.values() == [0, 1, 2, 3, 4, 0, 1]


def test_rating_picks_a_value(window):
    d = sheets.RatingDialog(window, "mood", "How is your mood?")
    later(lambda: d.rows[1].clicked.emit())
    assert d.exec() == QDialog.DialogCode.Accepted and d.value == 2
    assert sheets.RatingDialog.WORDS["connection"][0] == "Alone"


def test_easy_day_and_connection_keep_their_answers(window):
    events = [{"id": "e1", "title": "Gym", "start": "2026-10-22T18:00:00+05:30"}]
    d = sheets.EasyDayDialog(window, events, ill=True, bedtime="22:30")
    assert d.chosen() == ["e1"] and d.wants_early_night()
    d.boxes[0].setChecked(False)
    assert d.chosen() == []
    c = sheets.ConnectionDialog(window, date(2026, 10, 22))  # a Thursday
    assert c.who() == "a friend" and c.call.isChecked() and not c.meal.isChecked()
    assert c.meal_start(None).date() == date(2026, 10, 24)
    h = sheets.HelpDialog(window, week_low=True)
    assert h.reach_out.isChecked()


def test_ask_minutes_uses_a_stepper(window):
    def done() -> None:
        sheet = open_layer(layers.Sheet)
        sheet.findChild(kit.Stepper).setValue(45)
        sheet.done_button.click()

    later(done, 50)
    assert sheets.ask_minutes(window, "Log a strength session", 30) == 45


def test_old_names_still_import(window):
    from aura_mobile.ui.widgets import BreathingDialog, Card, RatingDialog, TimerDialog, Toast

    assert Toast is layers.Hud and RatingDialog is sheets.RatingDialog
    assert issubclass(BreathingDialog, TimerDialog)
    d = BreathingDialog(window, 1)
    later(lambda: d.skip.click())
    assert d.exec() == QDialog.DialogCode.Accepted and d.elapsed_seconds == 0
    assert Card("Water", accent=t.TEAL).body is not None


# -- round buttons, open-ended runs, the 3-2-1 -----------------------------------------------------------------


def test_run_has_round_pause_and_stop(window):
    view = runview.start(window, "Focus", 25)
    assert isinstance(view.pause_button, kit.RoundButton) and view.pause_button.kind == "pause"
    assert isinstance(view.end_button, kit.RoundButton) and view.end_button.kind == "stop"
    view.toggle_pause()
    assert view.pause_button.kind == "play" and view.pause_button.text() == "Resume"
    view.toggle_pause()
    assert view.pause_button.kind == "pause"


def test_open_ended_run_counts_up(window, monkeypatch):
    clock = [100.0]
    monkeypatch.setattr(runview.time, "monotonic", lambda: clock[0])
    view = runview.start(window, "Flowtime", 0)
    assert view.open_ended and view.clock_seconds == 0
    clock[0] += 65
    view._tick()
    assert view.clock_seconds == 65 and view.elapsed_seconds == 65 and not view.ended
    assert view.clock.text() == runview.clock_text(65) and view.bar.isHidden()


def test_countdown_runs_on_done_once_and_cancel_starts_nothing(window):
    done: list[int] = []
    view = runview.countdown(window, lambda: done.append(1), seconds=3)
    assert window.countdown_view is view and view.ring.left == 3
    for _ in range(3):
        view.ring.tick()
    assert done == [1] and window.countdown_view is None
    view.ring.finished.emit()
    assert done == [1]
    other = runview.countdown(window, lambda: done.append(2))
    other.cancel_button.click()
    assert done == [1] and window.countdown_view is None


def test_minibar_tap_on_a_server_session_opens_sessions(window, ctx):
    window.ctx = ctx
    opened: list[int] = []
    window.open_sessions = lambda: opened.append(1)
    ctx.store.data["active"] = {"id": "s9", "title": "Focus", "state": "running", "plannedMinutes": 25,
                                "startedAt": "2020-01-01T00:00:00+00:00"}
    runview.sync(window)
    window.minibar.open()
    assert opened == [1]
