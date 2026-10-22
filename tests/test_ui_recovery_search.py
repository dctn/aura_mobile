
"""Recovery and Search in the prototype look: alert cards, the ⓘ sheet, presets, the water card, modes, sessions."""

from __future__ import annotations

import pytest
from PyQt6.QtWidgets import QDialog

from tests import test_ui
from tests.test_ui import settle

#: no widget may flash up as its own window (see conftest.stray_windows)
pytestmark = pytest.mark.usefixtures("stray_windows")

phone = test_ui.phone


def _recovery(window):
    window.show_tab(1)
    settle()
    return window.recovery


def _search(window):
    window.show_tab(2)
    settle()
    return window.search


def test_alert_card_has_tinted_action_done_and_info(phone):
    window, _ctx, _api = phone
    page = _recovery(window)
    card = page.todo_list.itemAt(0).widget()
    assert card.property("alert")["featureId"] == "DX-04"
    assert card.act.text() == "Start a 10-min nap"
    assert card.act.property("tint") == "sleep"
    assert card.done.text() == "Done"
    assert "1 thing to do" in page.subtitle.text()


def test_info_opens_the_feature_description(phone, monkeypatch):
    window, _ctx, _api = phone
    from aura_mobile.ui import actions
    from aura_mobile.ui.pages import recovery

    page = _recovery(window)
    seen: list[str] = []
    performed: list[str] = []

    def fake_exec(sheet):
        seen.extend(w.text() for w in sheet.findChildren(type(sheet.title)))
        sheet.act = True
        return QDialog.DialogCode.Accepted

    monkeypatch.setattr(recovery.FeatureSheet, "exec", fake_exec)
    monkeypatch.setattr(actions, "perform", lambda ctx, parent, alert: performed.append(alert["id"]))
    page.todo_list.itemAt(0).widget().info.click()
    assert any("Short-night nap" in x for x in seen)
    assert any("10-min nap timer" in x for x in seen)  # the feature's "what they can do"
    assert performed == [page.todo_list.itemAt(0).widget().property("alert")["id"]]


def test_no_alerts_shows_a_calm_row(phone):
    window, ctx, _api = phone
    page = _recovery(window)
    ctx.store.patch("alerts", lambda old: [{**a, "done": True} for a in old])
    assert page.todo_list.count() == 1
    assert "Nothing to do" in page.todo_list.itemAt(0).widget().rows.itemAt(0).widget().text()
    assert page.subtitle.text() == "All clear"


def test_preset_tile_starts_a_timed_session(phone, monkeypatch):
    window, _ctx, _api = phone
    from aura_mobile.ui import actions

    page = _recovery(window)
    calls: list[tuple] = []
    monkeypatch.setattr(actions, "run_timed_session", lambda *a, **k: calls.append((a[2], a[3], k)))
    page.preset_tiles[1].clicked.emit()
    title, minutes, kw = calls[0]
    assert (title, minutes, kw["kind"], kw["breathing"]) == ("Slow breathing", 5, "recovery", True)
    assert page.preset_tiles[1].text() == "Slow breathing · 5 min"


def test_water_card_shows_the_day_and_week(phone):
    window, _ctx, _api = phone
    page = _search(window)
    assert page.water_card.big.text() == "500"
    assert page.water_card.unit.text() == "of 2000 ml"
    assert len(page.water_card.bars.values) == 7 and page.water_card.bars.values[-1] == 500
    assert page.water_row.value.text() == "2 of 8"


def test_modes_pick_length_and_start(phone, monkeypatch):
    window, _ctx, _api = phone
    from aura_mobile.ui import actions

    page = _search(window)
    started: list[tuple] = []
    monkeypatch.setattr(actions, "start_session", lambda ctx, kind, mode, **k: started.append((kind, mode, k)))
    page.mode.buttons[1].click()
    assert page.minutes.isHidden() and page.start.text() == "Start flowtime"
    page._start()
    page.mode.buttons[2].click()
    page.minutes.plus.click()
    page.title.setText("Park loop")
    page._start()
    assert started == [("focus", "flowtime", {"minutes": None, "title": "Flowtime"}),
                       ("walk", "walk", {"minutes": 25, "title": "Park loop"})]


def test_running_session_buttons_and_stress_change(phone, monkeypatch):
    window, ctx, api = phone
    from aura_mobile.ui import actions, layers

    page = _search(window)
    verbs: list[str] = []
    told: list[str] = []
    monkeypatch.setattr(actions, "session_action", lambda ctx, sid, verb: verbs.append(f"{sid}:{verb}"))
    monkeypatch.setattr(layers.Alert, "tell", lambda parent, title, text="": told.append(title))
    ctx.store.patch("active", lambda _old: {"id": "s9", "kind": "focus", "mode": "timer", "state": "paused"})
    ctx.store.patch("sessions", lambda old: [*old, {"id": "s8", "kind": "recovery", "mode": "timer",
                                                    "outcome": "completed", "startedAt": "2026-01-01T08:00:00"}])
    running = page.sessions.body.itemAt(0).widget()
    assert [b.text() for b in running.buttons] == ["Resume", "Stop", "Complete"]
    running.buttons[0].click()
    assert verbs == ["s9:resume"]
    api.session_trace = lambda sid: {"stressDrop": 12}
    rows = [page.sessions.body.itemAt(i).widget() for i in range(page.sessions.body.count())]
    link = next(r.trailing for r in rows if getattr(r, "trailing", None) is not None)
    link.click()
    settle()
    assert told == ["Stress before and after"]


def test_filter_with_no_match_says_so(phone):
    window, _ctx, _api = phone
    page = _search(window)
    page.query.setText("zzz")
    assert not page.nothing.isHidden() and "zzz" in page.nothing.text()
    assert page.sessions.isHidden() and page.modes.isHidden() and page.water_card.isHidden()
    page.query.setText("mood")
    assert page.nothing.isHidden() and not page.log_group.isHidden() and not page.mood_row.isHidden()
    assert page.water_row.isHidden()
