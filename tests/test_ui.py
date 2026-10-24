"""Offscreen smoke: the phone builds, signs in, and each page draws canned answers. No network, no keyring."""

from __future__ import annotations

import os
import threading
from datetime import date, timedelta

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

import pytest
from PyQt6.QtCore import QEventLoop, QThreadPool, QTimer
from PyQt6.QtWidgets import QApplication

from aura_mobile.core.features import ALERT_FEATURES
from aura_mobile.ui import context as area
from aura_mobile.ui.context import AppContext

#: no widget may flash up as its own window (see conftest.stray_windows)
pytestmark = pytest.mark.usefixtures("stray_windows")

TODAY = date.today()


def _day(n: int) -> str:
    return (TODAY - timedelta(days=n)).isoformat()


class Tokens:
    sub = "sub-ui"
    email = "tester@example.com"
    refresh_token = "r"


class Session:
    tokens = Tokens()
    is_signed_in = True
    on_ended = None

    def clear(self):
        return None


class Api:
    time_zone = "Asia/Kolkata"

    def __init__(self) -> None:
        self.off_writes: list[list[str]] = []
        self.done: list[str] = []
        self.water: list[int] = []
        self.moved: list[str] = []
        self.profile_writes: list[dict] = []
        self.logged: list[tuple[str, dict]] = []
        self.new_alerts: list[dict] = []
        self.consent_checks: list[str] = []
        #: what Task and Calendar hold; writes change them, so a reload shows the write (as on the server)
        self.task_rows: list[dict] = [{"id": "t1", "title": "Finish report", "status": "open", "horizon": "today",
                                       "version": 1}]
        self.event_rows: list[dict] = [
            {"id": "e-gym", "title": "Gym · legs", "date": TODAY.isoformat(), "kind": "focus",
             "start": f"{TODAY}T18:00:00+05:30", "end": f"{TODAY}T19:00:00+05:30", "version": 1},
            {"id": "e-lunch", "title": "Lunch with Ana", "date": TODAY.isoformat(), "kind": "meeting",
             "start": f"{TODAY}T13:00:00+05:30", "end": f"{TODAY}T14:00:00+05:30", "version": 1}]
        #: every task / event write, in order: (verb, id, body)
        self.cal_writes: list[tuple[str, str, dict]] = []
        #: writes wait for this, so a test can look at the screen before the server answers
        self.gate = threading.Event()
        self.gate.set()

    def alerts(self, start, end):
        return [
            *self.new_alerts,
            {"id": f"alert-{_day(1)}-DX-04", "featureId": "DX-04", "date": _day(1), "done": False,
             "title": "Short night", "body": "Take a nap", "priority": "high"},
            {"id": f"alert-{_day(2)}-PB-03", "featureId": "PB-03", "date": _day(2), "done": True,
             "title": "Sitting", "body": "Move", "priority": "normal"},
        ]

    def ensure_consent(self, policy_version):
        self.consent_checks.append(policy_version)
        return False

    def alert_done(self, alert_id):
        self.gate.wait(5)
        self.done.append(alert_id)
        return {}

    def log_water(self, ml, *, taken_at):
        self.gate.wait(5)
        self.water.append(ml)
        return {}

    def notifications(self, limit=30):
        return [{"id": "n1", "title": "Short night", "body": "Take a nap", "status": "sent"}]

    def events(self, start, end):
        return [dict(e) for e in self.event_rows]

    def create_event(self, title, start, end, **k):
        row = {"id": f"e-{len(self.event_rows) + 1}", "title": title, "start": start.isoformat(),
               "end": end.isoformat(), "version": 1}
        self.event_rows.append(row)
        self.cal_writes.append(("create_event", row["id"], dict(row)))
        return row

    def update_event(self, event_id, changes, version=None):
        self.cal_writes.append(("update_event", event_id, dict(changes)))
        for e in self.event_rows:
            if e["id"] == event_id:
                e.update(changes, version=e.get("version", 1) + 1)
        return {}

    def delete_event(self, event_id):
        self.cal_writes.append(("delete_event", event_id, {}))
        self.event_rows = [e for e in self.event_rows if e["id"] != event_id]
        return {}

    def move_event(self, event_id, *, to="tomorrow"):
        self.moved.append(event_id)
        return {}

    def update_profile_fresh(self, changes, version=None):
        self.profile_writes.append(dict(changes))
        self.saved = {**getattr(self, "saved", {}), **changes}  # the server keeps what earlier writes set
        return {**self.me(), "version": (version or 1) + 1}

    def post_log(self, log_type, data, *, taken_at):
        self.logged.append((log_type, dict(data)))
        return {}

    def tasks(self, *a, **k):
        return [dict(x) for x in self.task_rows]

    def create_task(self, title, *, horizon="today", task_id=None, **fields):
        row = {"id": task_id or f"t{len(self.task_rows) + 1}", "title": title, "status": "pending",
               "horizon": horizon, "version": 1, **fields}
        self.task_rows.append(row)
        self.cal_writes.append(("create_task", row["id"], dict(row)))
        return row

    def update_task(self, task_id, changes, version=None):
        self.cal_writes.append(("update_task", task_id, dict(changes)))
        for x in self.task_rows:
            if x["id"] == task_id:
                x.update(changes, version=x.get("version", 1) + 1)
        return {}

    def complete_task(self, task_id):
        self.cal_writes.append(("complete_task", task_id, {}))
        for x in self.task_rows:
            if x["id"] == task_id:
                x.update(status="completed", completedAt=f"{TODAY}T10:00:00+05:30")
        return {}

    def delete_task(self, task_id):
        self.cal_writes.append(("delete_task", task_id, {}))
        self.task_rows = [x for x in self.task_rows if x["id"] != task_id]
        return {}

    def metrics_range(self, *a, **k):
        """Two weeks of summary days (newest yesterday), so the cards' bars have something to draw."""
        return [{"date": _day(n), "status": "ok",
                 "metrics": {"totalSleepMin": 330 + 6 * n, "recoveryScore": 40 + n, "stressScore": 60 - n,
                             "restingHrBpm": 62, "activeMinutes": 20 + n, "burnoutIndexScore": 55,
                             "avgSleepBurnoutHrs": 6.1, "lateNightsBurnout": 3, "weeklyActiveMinutes": 62}}
                for n in range(14, 0, -1)]

    def metrics_day(self, day):
        return {"date": day, "status": "ok", "metrics": {"totalSleepMin": 330, "weeklyActiveMinutes": 62}}

    def me(self):
        return {"email": Tokens.email, "displayName": "Tester", "version": 1,
                "healthGoals": {"waterGoalMl": 2000, "bedtime": "23:00", "wakeTime": "07:00"},
                **getattr(self, "saved", {})}

    def alert_settings(self):
        return ["NI-03"]

    def set_alert_settings(self, off):
        self.off_writes.append(sorted(off))
        return sorted(off)

    def checkins(self, start, end):
        return [{"kind": "mood", "mood": 4, "takenAt": f"{TODAY}T09:00:00+05:30"}]

    def logs(self, start, end, log_type=None):
        return [{"date": TODAY.isoformat(), "takenAt": f"{TODAY}T10:00:00+05:30", "data": {"ml": 500}}]

    def active_session(self):
        return None

    def sessions(self, start, end):
        return [{"id": "s0", "kind": "walk", "mode": "walk", "outcome": "completed", "title": "Evening walk",
                 "startedAt": f"{_day(1)}T18:00:00+05:30"}]


def settle(ms: int = 50) -> None:
    """Let workers finish and their queued answers land."""
    for _ in range(40):
        QThreadPool.globalInstance().waitForDone(200)
        loop = QEventLoop()
        QTimer.singleShot(ms, loop.quit)
        loop.exec()
        from aura_mobile.ui.tasks import in_flight_count

        if in_flight_count() == 0:
            return


@pytest.fixture
def phone(tmp_path, monkeypatch, config):
    monkeypatch.setenv("APPDATA", str(tmp_path))
    app = QApplication.instance() or QApplication([])
    from aura_mobile.ui.shell import PhoneWindow

    api = Api()
    ctx = AppContext(config, session=Session(), api=api)
    window = PhoneWindow(ctx)
    window.show()
    ctx.after_sign_in()
    settle()
    yield window, ctx, api
    window.close()
    window.deleteLater()
    settle()
    assert app is not None


def test_starts_on_home_with_the_open_alert(phone):
    window, _ctx, _api = phone
    assert window.stack.currentWidget() is window.home
    assert "Short night" in window.home.notice.text()


def test_recovery_lists_only_open_alerts(phone):
    window, _ctx, _api = phone
    window.show_tab(1)
    settle()
    assert window.recovery.todo_list.count() == 1


def test_search_filters_rows(phone):
    window, _ctx, _api = phone
    window.show_tab(2)
    settle()
    window.search.query.setText("walk")
    rows = [*window.search.categories.values(), *window.search.places.values()]
    assert any(not w.isHidden() for w in rows)
    window.search.query.setText("zzz")
    assert all(w.isHidden() for w in rows)


def test_profile_switch_writes_the_off_list(phone):
    window, _ctx, api = phone
    window._open_profile()
    settle()
    assert not window.profile.switches["NI-03"].isChecked()
    window.profile.switches["DX-04"].setChecked(False)
    settle()
    assert api.off_writes[-1] == ["DX-04", "NI-03"]


def test_lab_has_a_row_per_alert_feature(phone):
    window, _ctx, _api = phone
    window._open_lab()
    settle()
    assert set(window.lab.rows) == {f.id for f in ALERT_FEATURES} | {"ALL"}
    assert all(row.run.isEnabled() for row in window.lab.rows.values())
    assert "Next:" in window.lab.rows["DX-04"].plan.text()


def test_sign_out_returns_to_sign_in(phone):
    window, ctx, _api = phone
    ctx.sign_out()
    assert window.stack.currentWidget() is window.signin
    assert window.tabbar.isHidden()
    ctx.notify(area.ALERTS)


def test_water_shows_before_the_server_answers(phone):
    window, _ctx, api = phone
    window.show_tab(2)
    window.search.on_open("Logging")
    settle()
    assert "500 of 2000" in window.logging.water_card.text()
    api.gate.clear()
    window.logging._water()
    assert "750 of 2000" in window.logging.water_card.text()  # drawn now, the write still in flight
    assert api.water == []
    api.gate.set()
    settle()
    assert api.water == [250]


def test_done_alert_leaves_the_list_at_once(phone):
    window, ctx, api = phone
    window.show_tab(1)
    settle()
    alert = ctx.store.data["alerts"][0]
    api.gate.clear()
    from aura_mobile.ui import actions

    actions.mark_done(ctx, alert)
    assert window.recovery._find(alert["id"]) is None  # gone now; the empty-state card shows instead
    api.gate.set()
    settle()
    assert api.done == [alert["id"]]


def test_easy_day_moves_hard_training_only(phone, monkeypatch):
    window, ctx, api = phone
    from PyQt6.QtWidgets import QDialog

    from aura_mobile.ui import actions, sheets

    monkeypatch.setattr(sheets.EasyDayDialog, "exec", lambda self: QDialog.DialogCode.Accepted)
    monkeypatch.setattr(actions, "run_timed_session", lambda *a, **k: False)
    assert actions.easy_day(ctx, window, "#000", ill=False)
    moved = next(e for e in ctx.store.data["events"] if e["id"] == "e-gym")
    assert moved["start"].startswith((TODAY + timedelta(days=1)).isoformat())  # on screen before the server answers
    settle()
    assert api.moved == ["e-gym"]  # lunch is not hard training


def test_profile_saves_body_and_goals_as_two_writes(phone):
    window, _ctx, api = phone
    window._open_profile()
    settle()
    assert "height" in window.profile.complete.text()
    window.profile.height.setValue(170)
    window.profile._save()
    settle()
    body, goals = api.profile_writes[-2:]
    assert body["heightCm"] == 170 and "healthGoals" not in body
    assert set(goals) == {"healthGoals"}


def test_strength_log_shows_in_history(phone, monkeypatch):
    window, _ctx, api = phone
    from aura_mobile.ui import sheets

    monkeypatch.setattr(sheets, "ask_minutes", lambda *a, **k: 99)  # not asked: the card has its own stepper
    window.show_tab(2)
    window.search.on_open("Logging")
    settle()
    window.logging.strength_minutes.setValue(40)
    window.logging.strength_log.click()
    page = window.logging
    texts = [page.history.itemAt(i).widget().text() for i in range(page.history.count())]
    assert any("Strength 40 min" in x for x in texts)
    assert "500 of 2000 ml" in page.water_card.text()  # strength doesn't count as water
    settle()
    assert api.logged == [("strength", {"minutes": 40})]


def test_home_shows_movement_and_finish_profile(phone):
    window, _ctx, _api = phone
    assert "62 of 150" in window.home.movement_label.text()
    assert not window.home.finish.isHidden()
    assert "4 missing" in window.home.finish.text()  # birth date, sex, height, weight
    assert "birth date" in window.home.finish.toolTip()


def test_a_new_alert_rings_once(phone, monkeypatch):
    window, ctx, api = phone
    rung: list[str] = []
    monkeypatch.setattr(window, "_ring", rung.append)
    ctx.notify(area.ALERTS)
    settle()
    assert rung == []  # nothing new
    api.new_alerts.append({"id": "alert-new-DX-07", "featureId": "DX-07", "date": TODAY.isoformat(),
                           "done": False, "title": "Time for a stress reset", "priority": "high"})
    ctx.notify(area.ALERTS)
    settle()
    ctx.notify(area.ALERTS)
    settle()
    assert rung == ["New alert: Time for a stress reset"]
