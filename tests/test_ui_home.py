
"""The shell (floating tab bar, pushed pages, theme) and Home (alert pills and cards, Burnout, quick sheet,
the calendar with its task and event editor, pins, the band line)."""

from __future__ import annotations

from datetime import timedelta

import pytest
from PyQt6.QtCore import QEvent, QPoint, QPointF, Qt
from PyQt6.QtGui import QKeyEvent, QMouseEvent
from PyQt6.QtWidgets import QApplication, QDialog, QPushButton

from aura_mobile.ui import layers
from aura_mobile.ui import tokens as t
from tests import test_ui as base
from tests.test_ui import TODAY, settle

#: no widget may flash up as its own window (see conftest.stray_windows)
pytestmark = pytest.mark.usefixtures("stray_windows")

#: the signed-in phone fixture from the shared smoke tests
phone = base.phone


def test_tab_bar_floats_over_the_stack(phone):
    window, _ctx, _api = phone
    bar = window.tabbar
    assert bar.parentWidget() is window
    assert bar.geometry().getRect() == (t.TABBAR_INSET, t.PHONE_HEIGHT - t.TABBAR_BOTTOM - t.TABBAR_H,
                                        t.PHONE_WIDTH - 2 * t.TABBAR_INSET, t.TABBAR_H)
    assert [b.text() for b in window.tab_buttons] == ["Home", "Recovery", "Search"]
    window.tab_buttons[2].click()
    assert window.stack.currentIndex() == 2
    assert [b.isChecked() for b in window.tab_buttons] == [False, False, True]


def test_home_has_a_large_title_with_the_date(phone):
    window, _ctx, _api = phone
    assert window.home.large.text() == "Home"
    assert window.home.subtitle.text().endswith(TODAY.strftime("%B"))
    assert str(TODAY.day) in window.home.subtitle.text()
    assert window.recovery.large.text() and window.search.large.text()


def test_profile_and_lab_push_with_a_back_button(phone):
    window, _ctx, _api = phone
    window.show_tab(1)
    window._open_lab()
    assert window.stack.currentWidget() is window.lab
    assert window.tabbar.isHidden()
    assert window.lab.nav.back.text() == "Recovery"
    window.keyPressEvent(QKeyEvent(QEvent.Type.KeyPress, Qt.Key.Key_Escape, Qt.KeyboardModifier.NoModifier))
    assert window.stack.currentIndex() == 1
    assert not window.tabbar.isHidden()
    window.show_tab(0)
    window.avatar.click()
    assert window.stack.currentWidget() is window.profile
    assert window.profile.nav.back.text() == "Home"
    window.profile.nav.back.click()
    assert window.stack.currentIndex() == 0


def test_theme_switch_sets_the_palette(phone):
    from aura_mobile.ui import shell

    shell.apply_theme(True)
    assert t.current().dark
    shell.apply_theme(False)
    assert not t.current().dark


def test_toast_is_the_hud(phone):
    window, ctx, _api = phone
    assert isinstance(window.toast, layers.Hud)
    ctx.toast.emit("Saved")
    assert window.toast.text() == "Saved" and window.toast.isVisible()


def test_not_now_hides_the_alert_until_a_new_one(phone):
    window, ctx, api = phone
    home = window.home
    assert "Short night" in home.notice.text()
    home._not_now()  # "Not now" in the pill's ⓘ sheet
    assert "Short night" not in home.notice.text()
    assert "All clear" in home.notice.text() and home.notice.verb.isHidden()
    api.new_alerts.append({"id": "alert-new-DX-07", "featureId": "DX-07", "date": TODAY.isoformat(),
                           "done": False, "title": "Time for a stress reset", "priority": "high"})
    from aura_mobile.ui import context as area

    ctx.notify(area.ALERTS)
    settle()
    assert home.notice.text() == "Stress reset · High stress · Breathe"


def test_alert_pill_has_its_metric_card_and_verb(phone, monkeypatch):
    window, _ctx, _api = phone
    from aura_mobile.ui import actions

    home = window.home
    assert home.notice.text() == "Short-night nap · Short night · Nap"
    card = home.alert_cards[0]
    assert not card.isHidden() and card.name.text() == "Sleep" and card.big.text() == "5.6"
    assert len(card.bars.values) == 14
    assert home.pills[1].isHidden() or home.pills[1].parentWidget().isHidden()  # one open alert, one pill
    performed: list[str] = []
    monkeypatch.setattr(actions, "perform", lambda ctx, parent, alert: performed.append(alert["featureId"]))
    home.notice.verb.click()
    assert performed == ["DX-04"]


def test_pill_tap_opens_a_short_sheet_and_not_now_snoozes(phone, monkeypatch):
    window, _ctx, _api = phone
    from aura_mobile.ui.pages.home import AlertSheet

    home = window.home
    seen: list[str] = []

    def later(sheet):
        seen.extend(w.text() for w in sheet.findChildren(type(sheet.title)))
        assert sheet.footer_button.text() == "Nap"
        sheet._later()
        return QDialog.DialogCode.Rejected

    monkeypatch.setattr(AlertSheet, "exec", later)
    home.notice.clicked.emit()
    assert any("Short-night nap" in x for x in seen) and any("10-min nap timer" in x for x in seen)
    assert not any("DX-04" in x for x in seen)
    assert "All clear" in home.notice.text()


def test_burnout_pill_and_cards_open_their_metric(phone):
    window, _ctx, _api = phone
    home = window.home
    assert home.burnout.text() == "Burnout risk · Moderate · See more"  # score 55
    cards = home.burnout_cards
    assert [c.name.text() for c in cards.values()] == ["Avg sleep", "Late nights", "Recovery"]
    assert cards["avg_sleep"].big.text() == "6.1" and cards["late_nights"].big.text() == "3"
    opened: list[str] = []
    home.on_metric = opened.append
    home.burnout.verb.click()
    cards["late_nights"].clicked.emit()
    home.movement.clicked.emit()
    assert opened == ["burnout", "late_nights", "movement"]


def test_band_line_is_one_short_line(phone):
    window, _ctx, _api = phone
    assert window.home.band_row.text() == "Not synced"  # the fake never synced from this phone
    assert window.home.band_row.reconnect.text() == "Reconnect"


def test_quick_sheet_logs_water_and_runs_steps_after_closing(phone, monkeypatch):
    window, _ctx, api = phone
    from aura_mobile.ui import actions
    from aura_mobile.ui.pages.home import QuickSheet

    home = window.home
    sheet = QuickSheet(home.ctx, home, home._top_alert)
    chip = next(b for b in sheet.findChildren(QPushButton) if b.text() == "+500 ml")
    chip.click()
    settle()
    assert api.water == [500]
    ran: list[str] = []
    monkeypatch.setattr(actions, "ask_checkin", lambda ctx, parent, kind, then=None: ran.append(kind))

    def pick_mood(self):
        self._run(lambda: actions.ask_checkin(self.ctx, home, "mood"))
        return QDialog.DialogCode.Accepted

    monkeypatch.setattr(QuickSheet, "exec", pick_mood)
    home._open_sheet()
    assert ran == ["mood"]


# -- the calendar ----------------------------------------------------------------------------------------------


def _sheet(monkeypatch, fill):
    """Run ``fill(sheet)`` instead of showing the add / edit sheet; it returns the dialog code."""
    from aura_mobile.ui.calview import ItemSheet

    monkeypatch.setattr(ItemSheet, "exec", fill)


def _todo(home) -> list[str]:
    return [r.text() for r in home.cal.task_rows()]


def test_calendar_shows_today_events_on_the_timeline_and_tasks_below(phone):
    window, _ctx, _api = phone
    home = window.home
    assert home.cal.day_title.text() == "Today"
    assert sorted(i.title for i in home.cal.timeline.items) == ["Gym · legs", "Lunch with Ana"]
    assert _todo(home) == ["Finish report"]
    assert home.cal.task_rows()[0].sub.text() == "Anytime · Today"
    assert home.cal.grid.marks[TODAY] == {"event", "task"}
    assert home.cal.day_count.text() == "3 items"
    assert not home.cal.grid.month_mode
    assert home.cal.grid.height() == home.cal.grid.HEAD + home.cal.grid.CELL


def test_add_button_adds_a_task_at_a_time(phone, monkeypatch):
    window, ctx, api = phone

    def fill(self):
        assert not self.done_button.isEnabled()
        self.title_edit.setText("Call the bank")
        assert self.done_button.isEnabled()
        assert self.has_day.isChecked() and self.has_time.isChecked()  # an hour was tapped
        return QDialog.DialogCode.Accepted

    _sheet(monkeypatch, fill)
    window.home.cal.timeline.slot_clicked.emit(9 * 60 + 30)
    task = next(x for x in ctx.store.data["tasks"] if x["title"] == "Call the bank")
    assert task["hasTime"] is True and "T09:30:00" in task["dueAt"] and task["horizon"] == "today"
    assert "Call the bank" in [i.title for i in window.home.cal.timeline.items]  # on screen before the server
    settle()
    assert [w[0] for w in api.cal_writes] == ["create_task"]
    assert api.cal_writes[0][1] == task["id"]  # the id chosen here is the one the server keeps
    assert "Call the bank" in [i.title for i in window.home.cal.timeline.items]


def test_add_sheet_can_add_an_event_instead(phone, monkeypatch):
    window, ctx, api = phone

    def fill(self):
        self.kinds.buttons[1].click()
        assert self.kind == "event"
        assert self.date_row.isVisibleTo(self) and self.time_row.isVisibleTo(self)
        assert not self.day_switch_row.isVisibleTo(self)
        self.title_edit.setText("Dentist")
        return QDialog.DialogCode.Accepted

    _sheet(monkeypatch, fill)
    window.home._add()
    assert any(e.get("title") == "Dentist" for e in ctx.store.data["events"])
    settle()
    assert [w[0] for w in api.cal_writes] == ["create_event"]
    assert "Dentist" in [i.title for i in window.home.cal.timeline.items]


def test_quick_add_puts_the_task_on_the_chosen_day(phone):
    window, ctx, api = phone
    home = window.home
    home.task_input.setText("Water plants")
    home._quick_add()
    assert "Water plants" in _todo(home)
    soon = TODAY + timedelta(days=2)
    home._pick_day(soon)
    assert home.task_input.placeholderText().startswith("Add a task for")
    home.task_input.setText("Buy gift")
    home._quick_add()
    assert _todo(home) == ["Buy gift"]
    gift = next(x for x in ctx.store.data["tasks"] if x["title"] == "Buy gift")
    assert gift["dueAt"].startswith(soon.isoformat()) and gift["hasTime"] is False and gift["horizon"] == "week"
    settle()
    assert [w[0] for w in api.cal_writes] == ["create_task", "create_task"]


def test_ticking_a_task_completes_it_and_it_stays_as_done(phone):
    window, ctx, api = phone
    row = window.home.cal.task_rows()[0]
    row.check.setChecked(True)
    assert all(x["status"] == "completed" for x in ctx.store.data["tasks"] if x["id"] == "t1")
    settle()
    assert api.cal_writes == [("complete_task", "t1", {})]
    assert window.home.cal.task_rows() == []  # a done task folds away
    more = window.home.cal.more
    assert more.isVisibleTo(window.home) and more.text() == "Show all 1"
    more.click()
    done = window.home.cal.task_rows()[0]
    assert done.check.isChecked() and done.sub.text() == "Done" and more.text() == "Show less"
    assert "task" not in window.home.cal.grid.marks.get(TODAY, set())


def test_calendar_takes_about_60_percent_and_hours_open_at_the_first_item(phone):
    window, ctx, _api = phone
    from aura_mobile.ui.pages.home import CALENDAR_SHARE

    home, cal = window.home, window.home.cal
    settle()
    assert abs(cal.height() - home.height() * CALENDAR_SHARE) < 20
    assert cal.hours.height() < cal.timeline.height()  # the hours scroll inside the card
    bar = cal.hours.verticalScrollBar()
    first = min(i.start for i in cal.timeline.items)
    top_hour = cal.timeline.minutes_at(bar.value() + cal.timeline.HOUR) // 60
    assert top_hour <= first.hour + 1 and bar.value() > 0  # not stuck at midnight
    assert not cal.hours.empty.isVisibleTo(cal.hours)

    bar.setValue(0)
    ctx.notify("tasks")  # the minute poll: the user's place stays
    settle()
    assert bar.value() == 0

    home._pick_day(TODAY + timedelta(days=3))  # nothing that day
    settle()
    hint = cal.hours.empty
    assert hint.isVisibleTo(cal.hours) and hint.text().startswith("Nothing planned")
    box = cal.hours.viewport().geometry()
    assert abs(hint.geometry().center().y() - box.center().y()) <= 2
    assert hint.geometry().left() > cal.timeline.GUTTER


def test_editing_a_task_moves_it_to_another_day(phone, monkeypatch):
    window, _ctx, api = phone
    later = TODAY + timedelta(days=10)

    def fill(self):
        assert self.title_edit.text() == "Finish report" and not self.kinds.isVisibleTo(self)
        assert not self.has_day.isChecked()
        self.has_day.setChecked(True)
        self.date.set_day(later)
        self.title_edit.setText("Finish the report")
        return QDialog.DialogCode.Accepted

    _sheet(monkeypatch, fill)
    window.home._open_item("task:t1")
    assert _todo(window.home) == []
    window.home._pick_day(later)
    assert _todo(window.home) == ["Finish the report"]
    settle()
    verb, task_id, changes = api.cal_writes[0]
    assert (verb, task_id) == ("update_task", "t1")
    assert changes["title"] == "Finish the report" and changes["horizon"] == "month"
    assert changes["dueAt"].startswith(later.isoformat()) and changes["hasTime"] is False


def test_task_sheet_marks_done_and_deletes(phone, monkeypatch):
    window, ctx, api = phone
    monkeypatch.setattr(layers.Alert, "ask", staticmethod(lambda *a, **k: True))
    _sheet(monkeypatch, lambda self: self._toggle_done() or QDialog.DialogCode.Accepted)
    window.home._open_item("task:t1")
    settle()
    assert api.cal_writes[-1][0] == "complete_task"
    _sheet(monkeypatch, lambda self: self._delete() or QDialog.DialogCode.Accepted)
    window.home._open_item("task:t1")
    assert not any(x["id"] == "t1" for x in ctx.store.data["tasks"])
    settle()
    assert api.cal_writes[-1] == ("delete_task", "t1", {})
    assert _todo(window.home) == []


def test_editing_an_event_changes_its_time_and_delete_asks_first(phone, monkeypatch):
    window, ctx, api = phone
    from PyQt6.QtCore import QTime

    def fill(self):
        assert self.kind == "event" and self.title_edit.text() == "Lunch with Ana"
        assert self.time.time() == QTime(13, 0) and self.length.value() == 60
        self.time.setTime(QTime(14, 0))
        return QDialog.DialogCode.Accepted

    _sheet(monkeypatch, fill)
    window.home._open_item("event:e-lunch")
    lunch = next(e for e in ctx.store.data["events"] if e["id"] == "e-lunch")
    assert "T14:00:00" in lunch["start"] and "T15:00:00" in lunch["end"]
    settle()
    assert api.cal_writes[0][:2] == ("update_event", "e-lunch")

    asked: list[str] = []
    monkeypatch.setattr(layers.Alert, "ask", staticmethod(lambda _p, title, *a, **k: asked.append(title) or False))
    _sheet(monkeypatch, lambda self: self._delete() or QDialog.DialogCode.Rejected)
    window.home._open_item("event:e-lunch")
    assert asked == ["Delete this event?"]
    assert any(e["id"] == "e-lunch" for e in ctx.store.data["events"])  # said no: kept


def test_week_month_and_today(phone):
    window, ctx, _api = phone
    from aura_mobile.core import agenda

    home = window.home
    home._set_mode(1)
    rows = len(agenda.month_grid(TODAY)) // 7
    assert home.cal.grid.month_mode and home.cal.grid.height() == home.cal.grid.HEAD + rows * home.cal.grid.CELL
    home._step(1)
    nxt = agenda.add_months(TODAY, 1)
    assert home.cal_day == nxt.replace(day=1) and home.cal.month.text() == f"{nxt:%b %Y}"
    assert home.cal.today.isVisibleTo(home)
    first, last = ctx.store.span
    assert first <= home.cal.grid.days()[0] and home.cal.grid.days()[-1] <= last
    home._go_today()
    assert home.cal_day == TODAY and home.cal.day_title.text() == "Today"
    assert not home.cal.today.isVisibleTo(home)
    home._set_mode(0)
    home._step(-1)
    assert home.cal_day == TODAY - timedelta(days=7)
    settle()


def test_grid_and_timeline_map_taps(phone):
    window, _ctx, _api = phone
    grid, line = window.home.cal.grid, window.home.cal.timeline
    week = grid.days()
    assert grid.date_at(grid.width() / 7 * 2.5, grid.HEAD + 5) == week[2]
    assert grid.date_at(10, 5) is None  # the weekday letters
    y = line.PAD + (9 - line.first) * line.HOUR + line.HOUR * 0.6
    assert line.minutes_at(y) == 9 * 60 + 30
    assert {item.key for item, _rect in line.blocks()} == {"event:e-gym", "event:e-lunch"}


def test_pins_sheet_keeps_at_most_six(phone):
    window, _ctx, _api = phone
    from aura_mobile.core import metrics as m
    from aura_mobile.ui.pages.home import PinsDialog

    dialog = PinsDialog(window.home, list(m.KNOWN), ["totalSleepMin"])
    for field in list(m.KNOWN)[1:9]:
        dialog._toggle(field)
    assert len(dialog.chosen()) == 6
    dialog._toggle("totalSleepMin")
    assert "totalSleepMin" not in dialog.chosen()


# -- dragging on the calendar ----------------------------------------------------------------------------------


def _mouse(widget, kind, x: float, y: float) -> None:
    """Send a left-button mouse event at (x, y) in ``widget`` (the card's drag sees it first)."""
    local = QPointF(x, y)
    glob = QPointF(widget.mapToGlobal(local.toPoint()))
    left = Qt.MouseButton.LeftButton
    button = Qt.MouseButton.NoButton if kind == QEvent.Type.MouseMove else left
    buttons = Qt.MouseButton.NoButton if kind == QEvent.Type.MouseButtonRelease else left
    QApplication.sendEvent(widget, QMouseEvent(kind, local, glob, button, buttons, Qt.KeyboardModifier.NoModifier))


def _card_of(widget):
    from aura_mobile.ui.calview import CalendarCard

    while widget is not None and not isinstance(widget, CalendarCard):
        widget = widget.parentWidget()
    return widget


def _global(widget, x: float, y: float) -> QPointF:
    return QPointF(widget.mapToGlobal(QPoint(int(x), int(y))))


def _at(line, hour: float) -> float:
    """The timeline y of ``hour`` (13.5 = 13:30), with the hours scrolled so it shows."""
    y = line._y_of(int(hour * 60))
    hours = _card_of(line).hours
    bar = hours.verticalScrollBar()
    if not bar.value() + 30 < y < bar.value() + hours.viewport().height() - 30:
        bar.setValue(int(y - hours.viewport().height() / 2))
    return y


def _toasts(ctx) -> list[str]:
    seen: list[str] = []
    ctx.toast.connect(seen.append)
    return seen


def _pick_up(widget, x: float, y: float) -> None:
    _mouse(widget, QEvent.Type.MouseButtonPress, x, y)
    _mouse(widget, QEvent.Type.MouseMove, x, y + 8)  # past the slop: the drag starts


def test_dragging_a_block_moves_the_event_in_quarter_hours(phone):
    window, ctx, api = phone
    line = window.home.cal.timeline
    toasts = _toasts(ctx)
    clicks: list[str] = []
    line.item_clicked.connect(clicks.append)
    line.slot_clicked.connect(lambda m: clicks.append(str(m)))
    x = line.GUTTER + 60
    # lunch is 13:00-14:00; picked up at 13:30, let go at 15:10 -> starts 14:40, snapped to 14:45
    _pick_up(line, x, _at(line, 13.5))
    to = _at(line, 15 + 10 / 60)
    _mouse(line, QEvent.Type.MouseMove, x, to)
    assert line.preview is not None and line.preview[1].start == 14 * 60 + 45
    assert line.lifted == "event:e-lunch"
    _mouse(line, QEvent.Type.MouseButtonRelease, x, to)
    lunch = next(e for e in ctx.store.data["events"] if e["id"] == "e-lunch")
    assert "T14:45:00" in lunch["start"] and "T15:45:00" in lunch["end"]
    assert toasts[-1].startswith("Lunch with Ana · ") and toasts[-1].endswith("14:45–15:45")
    assert clicks == []  # the release ended the drag; it opened nothing and added nothing
    assert line.lifted is None and line.preview is None
    settle()
    assert [w[:2] for w in api.cal_writes] == [("update_event", "e-lunch")]


def test_the_bottom_edge_stretches_a_block(phone):
    window, ctx, _api = phone
    line = window.home.cal.timeline
    _at(line, 19)
    gym = next(r for i, r in line.blocks() if i.id == "e-gym")
    _pick_up(line, gym.center().x(), gym.bottom() - 3)
    assert window.home.cal.drag.live.mode == "stretch"
    to = _at(line, 19.5)
    _mouse(line, QEvent.Type.MouseMove, gym.center().x(), to)
    _mouse(line, QEvent.Type.MouseButtonRelease, gym.center().x(), to)
    event = next(e for e in ctx.store.data["events"] if e["id"] == "e-gym")
    assert "T18:00:00" in event["start"] and "T19:30:00" in event["end"]


def test_a_todo_row_drops_into_the_hours_and_gets_a_time(phone):
    window, ctx, api = phone
    home = window.home
    line, drag = home.cal.timeline, home.cal.drag
    row = home.cal.task_rows()[0]
    _pick_up(row, row.width() / 2, 10)
    assert drag.live is not None and drag.live.item.title == "Finish report"
    # grabbed 15 minutes into its 30: at 10:15 it starts at 10:00
    drag.aim(_global(line, line.GUTTER + 60, _at(line, 10.25)))
    assert line.preview is not None and line.preview[1].start == 10 * 60
    assert "10:00–10:30" in drag.chip.text()
    drag.end(True)
    task = next(x for x in ctx.store.data["tasks"] if x["id"] == "t1")
    assert task["hasTime"] is True and "T10:00:00" in task["dueAt"] and task["estimateMinutes"] == 30
    assert task["horizon"] == "today"
    assert "Finish report" in [i.title for i in line.items]
    settle()
    assert [w[:2] for w in api.cal_writes] == [("update_task", "t1")]


def test_a_block_dropped_on_a_day_keeps_its_time_and_the_day_shows(phone):
    window, ctx, _api = phone
    home = window.home
    grid, line, drag = home.cal.grid, home.cal.timeline, home.cal.drag
    week = grid.days()
    other = next(d for d in week if d != TODAY)
    _pick_up(line, line.GUTTER + 60, _at(line, 18.5))
    drag.aim(_global(grid, grid.width() / 7 * (week.index(other) + 0.5), grid.HEAD + 10))
    assert grid.over == other and line.preview is None
    drag.end(True)
    gym = next(e for e in ctx.store.data["events"] if e["id"] == "e-gym")
    assert gym["start"].startswith(f"{other}T18:00:00")
    assert home.cal_day == other and grid.over is None
    assert "Gym · legs" in [i.title for i in line.items]


def test_esc_puts_it_back_and_the_release_clicks_nothing(phone):
    window, ctx, api = phone
    line, drag = window.home.cal.timeline, window.home.cal.drag
    clicks: list[int] = []
    line.slot_clicked.connect(clicks.append)
    x, y = line.GUTTER + 60, _at(line, 13.5)
    _pick_up(line, x, y)
    _mouse(line, QEvent.Type.MouseMove, x, y + 40)
    assert drag.live is not None
    drag.chip.keyPressEvent(QKeyEvent(QEvent.Type.KeyPress, Qt.Key.Key_Escape, Qt.KeyboardModifier.NoModifier))
    assert drag.live is None and line.preview is None and drag.chip.isHidden()
    _mouse(line, QEvent.Type.MouseButtonRelease, x, y + 40)
    assert clicks == []
    settle()
    assert api.cal_writes == []
    assert next(e for e in ctx.store.data["events"] if e["id"] == "e-lunch")["start"].endswith("T13:00:00+05:30")


def test_done_tasks_do_not_pick_up(phone):
    window, _ctx, _api = phone
    home = window.home
    home._tick(home._items["task:t1"], True)
    home._toggle_todo()  # show the done row too
    row = next(r for r in home.cal.task_rows() if r.item.id == "t1")
    _pick_up(row, row.width() / 2, 10)
    assert home.cal.drag.live is None


def test_holding_at_an_edge_or_on_the_arrows_flips_the_day_or_week(phone):
    window, _ctx, _api = phone
    home = window.home
    line, drag, port = home.cal.timeline, home.cal.drag, home.cal.hours.viewport()
    _pick_up(line, line.GUTTER + 60, _at(line, 13.5))
    drag.aim(_global(port, port.width() - 5, port.height() / 2))
    assert drag.live.side == 1 and drag._hold.isActive()
    drag._held()
    assert home.cal_day == TODAY + timedelta(days=1)
    assert drag.live.target is not None and drag.live.target.day == TODAY + timedelta(days=1)
    nxt = home.cal.next
    drag.aim(_global(nxt, nxt.width() / 2, nxt.height() / 2))
    assert drag._hold_key == "flip:1"
    drag._held()
    assert home.cal_day == TODAY + timedelta(days=8)
    drag.end(False)
    assert not drag._hold.isActive()
