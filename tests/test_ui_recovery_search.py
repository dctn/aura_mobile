
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
    assert card.pill.title.text() == "Short-night nap" and card.pill.reason.text() == "Short night"
    assert card.act.text() == "Nap"
    assert card.done.toolTip() == "Done"
    assert card.card.name.text() == "Sleep" and card.card.big.text() == "5.6"  # last night, in hours
    assert len(card.card.bars.values) == 14
    assert "1 thing to do" in page.subtitle.text()


def test_alert_pill_verb_and_check_do_the_action_and_done(phone, monkeypatch):
    window, _ctx, api = phone
    from aura_mobile.ui import actions

    page = _recovery(window)
    performed: list[str] = []
    monkeypatch.setattr(actions, "perform", lambda ctx, parent, alert: performed.append(alert["id"]))
    card = page.todo_list.itemAt(0).widget()
    alert_id = card.property("alert")["id"]
    card.act.click()
    assert performed == [alert_id]
    card.done.click()
    assert "Nothing to do" in page.todo_list.itemAt(0).widget().text()  # gone at once; the fake brings it back
    settle()
    assert api.done == [alert_id]


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
    page.todo_list.itemAt(0).widget().info.clicked.emit()  # a tap on the pill
    assert any("Short-night nap" in x for x in seen)
    assert not any("DX-04" in x or "totalSleepMin" in x for x in seen)  # no feature codes or rules
    assert any("10-min nap timer" in x for x in seen)  # the feature's "what they can do"
    assert performed == [page.todo_list.itemAt(0).widget().property("alert")["id"]]


def test_no_alerts_shows_a_calm_row(phone):
    window, ctx, _api = phone
    page = _recovery(window)
    ctx.store.patch("alerts", lambda old: [{**a, "done": True} for a in old])
    assert page.todo_list.count() == 1
    assert "Nothing to do" in page.todo_list.itemAt(0).widget().text()
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
    assert page.preset_tiles[1].text() == "Breathe · 5 min"


def _place(window, name):
    """Search → one of its second list (Logging, Modes, Sessions), the way a tap opens it."""
    _search(window).places[name].clicked.emit()
    settle()
    return window.stack.currentWidget()


def test_water_card_shows_the_day_and_week(phone):
    window, _ctx, _api = phone
    page = _place(window, "Logging")
    assert page is window.logging
    assert page.water_card.big.text() == "500"
    assert page.water_card.unit.text() == "of 2000 ml"
    assert len(page.water_card.bars.values) == 7 and page.water_card.bars.values[-1] == 500
    assert (page.water_card.glasses.filled, page.water_card.glasses.total) == (2, 8)


def test_modes_pick_length_and_start(phone, monkeypatch):
    window, _ctx, _api = phone
    from aura_mobile.ui import actions, runview

    modes = _place(window, "Modes")
    assert modes is window.modes and modes.tiles[0].text().startswith("Timer")
    started: list[tuple] = []

    def start_session(ctx, kind, mode, then=None, **k):
        started.append((kind, mode, k))

    monkeypatch.setattr(actions, "start_session", start_session)
    monkeypatch.setattr(runview, "countdown", lambda parent, on_done, seconds=3: on_done())  # skip the 3-2-1
    modes.tiles[1].clicked.emit()
    page = window.stack.currentWidget()
    assert page is window.mode_setup and page.nav.back.text().endswith("Modes")
    assert page.minutes.isHidden() and page.start.text() == "Start flowtime"
    page._start()
    runview.current(window).discard()
    _place(window, "Modes")
    modes.tiles[2].clicked.emit()
    page.minutes.plus.click()
    page.title.setText("Park loop")
    page._start()
    assert started == [("focus", "flowtime", {"minutes": None, "title": "Flowtime"}),
                       ("walk", "walk", {"minutes": 25, "title": "Park loop"})]


def test_running_session_buttons_and_stress_change(phone, monkeypatch):
    window, ctx, api = phone
    from aura_mobile.ui import actions, layers

    page = _place(window, "Sessions")
    assert page is window.sessions
    verbs: list[str] = []
    told: list[str] = []
    monkeypatch.setattr(actions, "session_action", lambda ctx, sid, verb: verbs.append(f"{sid}:{verb}"))
    monkeypatch.setattr(layers.Alert, "tell", lambda parent, title, text="": told.append(title))
    ctx.store.patch("active", lambda _old: {"id": "s9", "kind": "focus", "mode": "timer", "state": "paused"})
    ctx.store.patch("sessions", lambda old: [*old, {"id": "s8", "kind": "recovery", "mode": "timer",
                                                    "outcome": "completed", "startedAt": "2026-01-01T08:00:00"}])
    running = page.running
    assert [b.text() for b in running.buttons] == ["Resume", "Stop", "Done"]
    running.buttons[0].click()
    assert verbs == ["s9:resume"]
    api.session_trace = lambda sid: {"stressDrop": 12}
    rows = [page.body.itemAt(i).widget() for i in range(page.body.count())]
    link = next(r.trailing for r in rows if getattr(r, "trailing", None) is not None)
    link.click()
    settle()
    assert told == ["Stress before and after"]


def test_filter_with_no_match_says_so(phone):
    window, _ctx, _api = phone
    page = _search(window)
    page.query.setText("zzz")
    assert not page.nothing.isHidden() and "zzz" in page.nothing.text()
    assert page.health.isHidden() and page.more.isHidden()
    page.query.setText("mood")
    assert page.nothing.isHidden() and not page.places["Logging"].isHidden()
    assert page.places["Modes"].isHidden() and page.categories["sleep"].isHidden()
