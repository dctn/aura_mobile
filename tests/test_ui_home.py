
"""The shell (floating tab bar, pushed pages, theme) and Home (coach card, quick sheet, calendar, tasks, pins)."""

from __future__ import annotations

import pytest
from PyQt6.QtCore import QEvent, Qt
from PyQt6.QtGui import QKeyEvent
from PyQt6.QtWidgets import QDialog, QPushButton

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
    home.notice.dismissed.emit()
    assert "Short night" not in home.notice.text()
    assert "Nothing needed" in home.notice.text()
    api.new_alerts.append({"id": "alert-new-DX-07", "featureId": "DX-07", "date": TODAY.isoformat(),
                           "done": False, "title": "Time for a stress reset", "priority": "high"})
    from aura_mobile.ui import context as area

    ctx.notify(area.ALERTS)
    settle()
    assert "Time for a stress reset" in home.notice.text()


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


def test_new_event_sheet_adds_to_the_calendar(phone, monkeypatch):
    window, ctx, _api = phone
    from aura_mobile.ui.pages.home import EventDialog

    ctx.api.create_event = lambda *a, **k: {}

    def fill(self):
        assert not self.done_button.isEnabled()
        self.event_title.setText("Dentist")
        assert self.done_button.isEnabled()
        return QDialog.DialogCode.Accepted

    monkeypatch.setattr(EventDialog, "exec", fill)
    window.home._add_event()
    assert any(e.get("title") == "Dentist" for e in ctx.store.data["events"])
    rows = [window.home.calendar.rows.itemAt(i).widget() for i in range(window.home.calendar.count())]
    assert any("Dentist" in r.text() for r in rows if r is not None)
    settle()


def test_checking_a_task_completes_it(phone):
    window, ctx, _api = phone
    from aura_mobile.ui.pages.home import CheckMark

    done: list[str] = []
    ctx.api.complete_task = lambda task_id: done.append(task_id) or {}
    checks = window.home.tasks.findChildren(CheckMark)
    assert len(checks) == 1
    checks[0].setChecked(True)
    assert all(x["status"] == "completed" for x in ctx.store.data["tasks"] if x["id"] == "t1")
    settle()
    assert done == ["t1"]


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
