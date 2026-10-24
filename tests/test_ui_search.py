"""Search and its drill pages: the category list, a metric's page, Logging, Modes → setup → 3-2-1, Sessions, and
the shell's pushed-page navigation. Offscreen with the canned answers of ``test_ui``."""

from __future__ import annotations

import pytest

from aura_mobile.core import metrics as m
from aura_mobile.ui import actions, prefs, runview
from aura_mobile.ui.pages import metric as metric_page
from tests import test_ui
from tests.test_ui import settle

#: no widget may flash up as its own window (see conftest.stray_windows)
pytestmark = pytest.mark.usefixtures("stray_windows")

phone = test_ui.phone


def _search(window):
    window.show_tab(2)
    settle()
    return window.search


# -- the Search root ------------------------------------------------------------------------------------------


def test_search_lists_categories_with_newest_values_then_places(phone):
    window, _ctx, _api = phone
    page = _search(window)
    assert page.large.text() == "Search"
    assert list(page.categories) == list(m.CATEGORIES)
    assert page.categories["sleep"].value.text() == "5.6 h"  # yesterday's 336 min
    assert page.categories["recovery"].value.text() == "41/100"
    assert list(page.places) == ["Logging", "Modes", "Sessions", "Settings"]


def test_search_rows_open_their_pages_and_back_names_search(phone):
    window, _ctx, _api = phone
    page = _search(window)
    page.categories["sleep"].clicked.emit()
    assert window.stack.currentWidget() is window.metric and window.tabbar.isHidden()
    assert window.metric.large.text() == "Sleep" and window.metric.nav.back.text().endswith("Search")
    window.metric.nav.back.click()
    assert window.stack.currentIndex() == 2 and not window.tabbar.isHidden()
    page.places["Settings"].clicked.emit()
    assert window.stack.currentWidget() is window.profile and window.profile.nav.back.text().endswith("Search")


def test_pushed_pages_stack_and_back_unwinds_them(phone):
    window, _ctx, _api = phone
    _search(window).places["Modes"].clicked.emit()
    window.modes.tiles[2].clicked.emit()
    assert window.stack.currentWidget() is window.mode_setup
    assert window.mode_setup.large.text() == "Walk" and window.mode_setup.nav.back.text().endswith("Modes")
    window._close_overlay()
    assert window.stack.currentWidget() is window.modes
    window._close_overlay()
    assert window.stack.currentIndex() == 2


def test_home_and_recovery_hooks_are_wired(phone):
    window, _ctx, _api = phone
    window.show_tab(0)
    window.home.on_metric("sleepDebtMin14")  # an alert column outside CARDS
    assert window.stack.currentWidget() is window.metric and window.metric.card.field == "sleepDebtMin14"
    assert window.metric.nav.back.text().endswith("Home")
    window.show_tab(0)
    window.home.on_open_recovery()
    assert window.stack.currentIndex() == 1
    window.recovery.on_metric("burnout")
    assert window.metric.large.text() == "Burnout" and window.metric.nav.back.text().endswith("Recovery")


# -- the metric page --------------------------------------------------------------------------------------------


def test_trend_compares_the_last_week_with_the_one_before():
    assert metric_page.trend([10.0] * 7 + [11.0] * 7) == pytest.approx(10.0)
    assert metric_page.trend([None] * 7 + [5.0] * 7) is None
    assert metric_page.trend([0.0] * 7 + [5.0] * 7) is None


def test_metric_page_shows_value_average_bars_and_trend(phone):
    window, _ctx, _api = phone
    window.open_metric("sleep")
    page = window.metric
    assert (page.big.text(), page.unit.text()) == ("5.6", "h")
    assert page.avg.text().endswith("h") and len(page.bars.values) == 14
    assert not page.trend_card.isHidden()
    assert page.trend_title.text() == "Trending down" and page.trend_pct.text() == "-10%"


def test_longer_range_is_fetched_once_on_a_worker(phone):
    window, _ctx, api = phone
    asked: list[tuple] = []
    real = api.metrics_range
    api.metrics_range = lambda *a, **k: asked.append(a) or real(*a, **k)
    window.open_metric("recovery")
    page = window.metric
    page.range.set_index(1)
    page.range.changed.emit(1)
    settle()
    assert len(asked) == 1 and 30 in page._ranges and len(page.bars.values) == 30
    page.range.changed.emit(1)
    settle()
    assert len(asked) == 1


def test_pin_to_home_saves_the_pin_and_tells_home(phone):
    window, ctx, _api = phone
    said: list[str] = []
    ctx.toast.connect(said.append)
    window.open_metric("breathing")
    page = window.metric
    field = page.card.field
    was = field in prefs.pins()
    page.pin.setChecked(not was)
    assert (field in prefs.pins()) is not was
    assert said and "Home" in said[-1]
    page.pin.setChecked(was)
    assert (field in prefs.pins()) is was


# -- Logging ----------------------------------------------------------------------------------------------------


def test_logging_faces_save_a_checkin(phone):
    window, _ctx, api = phone
    posted: list[dict] = []
    api.post_checkin = lambda kind, taken_at, **k: posted.append({"kind": kind, **k}) or {}
    window.show_tab(2)
    window.search.on_open("Logging")
    settle()
    page = window.logging
    assert page.mood.faces.value == 4 and page.mood.last.text()  # the canned 4/5 from this morning
    page.connection.faces.picked.emit(5)
    settle()
    assert posted == [{"kind": "connection", "connection": 5}]


def test_water_buttons_add_a_glass_or_a_bottle(phone):
    window, _ctx, api = phone
    window.show_tab(2)
    window.search.on_open("Logging")
    settle()
    window.logging.water_card.add_bottle.click()
    settle()
    window.logging.water_card.add_glass.click()
    settle()
    assert api.water == [500, 250]


# -- Modes → setup → 3-2-1 → run --------------------------------------------------------------------------------


def test_mode_start_counts_down_then_runs_in_that_mode(phone, monkeypatch):
    window, _ctx, _api = phone
    runs: list[tuple] = []
    monkeypatch.setattr(actions, "run_timed_session",
                        lambda ctx, win, title, minutes, **k: runs.append((title, minutes, k["kind"], k["mode"])))
    _search(window).places["Modes"].clicked.emit()
    window.modes.tiles[3].clicked.emit()
    setup = window.mode_setup
    assert setup.start.text() == "Start meeting" and setup.minutes.value() == 30
    setup.start.clicked.emit()
    view = window.countdown_view
    assert view is not None and view.isVisible() and runs == []
    for _ in range(3):
        view.ring.tick()
    assert runs == [("Meeting", 30, "meeting", "meeting")]
    assert window.stack.currentIndex() == 2  # back on Search, so Hide leaves the mini bar over it


def test_mode_countdown_cancel_starts_nothing(phone, monkeypatch):
    window, _ctx, _api = phone
    runs: list = []
    monkeypatch.setattr(actions, "run_timed_session", lambda *a, **k: runs.append(a))
    _search(window).places["Modes"].clicked.emit()
    window.modes.tiles[0].clicked.emit()
    window.mode_setup._start()
    window.countdown_view.cancel_button.click()
    assert runs == [] and window.countdown_view is None and runview.current(window) is None


# -- Sessions ---------------------------------------------------------------------------------------------------


def test_sessions_lists_this_weeks_with_length_and_day(phone):
    window, ctx, _api = phone
    _search(window).places["Sessions"].clicked.emit()
    settle()
    page = window.sessions
    assert page.running is None
    row = page.body.itemAt(0).widget()
    assert row.title.text() == "Evening walk" and row.value.text() == "Yesterday"
    ctx.store.patch("active", lambda _old: {"id": "s9", "kind": "walk", "title": "Walk", "state": "running"})
    assert page.running is not None and [b.text() for b in page.running.buttons] == ["Pause", "Stop", "Done"]
    assert _search(window).places["Sessions"].value.text() == "Running"  # drawn when Search shows again
