"""The Figma-look building blocks: the alert pill, the metric card, the day strip, the start pill, the countdown."""

from __future__ import annotations

import os
from datetime import date

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

import pytest
from PyQt6.QtWidgets import QApplication, QVBoxLayout, QWidget

from aura_mobile.core import metrics as m
from aura_mobile.core.features import FEATURES
from aura_mobile.ui import kit

#: no widget may flash up as its own window (see conftest.stray_windows)
pytestmark = pytest.mark.usefixtures("stray_windows")


@pytest.fixture
def host():
    app = QApplication.instance() or QApplication([])
    w = QWidget()
    w.setProperty("test_host", True)
    lay = QVBoxLayout(w)
    w.resize(390, 600)
    yield w, lay
    w.close()
    w.deleteLater()
    assert app is not None


def _render(w: QWidget) -> None:
    w.show()
    w.grab()  # runs every paintEvent once


def test_action_pill_emits_act_done_and_clicked(host):
    w, lay = host
    pill = kit.ActionPill("sleep", "Short-night nap", "Short night", "Nap", accent="sleep", with_done=True)
    lay.addWidget(pill)
    _render(w)
    got: list[str] = []
    pill.act.connect(lambda: got.append("act"))
    pill.done.connect(lambda: got.append("done"))
    pill.clicked.connect(lambda: got.append("info"))
    pill.verb.click()
    pill.check.click()
    assert got == ["act", "done"]
    assert pill.text() == "Short-night nap · Short night · Nap"
    pill.set_content("check", "All clear", "", "")
    assert pill.verb.isHidden() and pill.reason.isHidden()


def test_metric_card_draws_fourteen_tracks(host):
    w, lay = host
    card = kit.MetricCard("Sleep", "h", key="cyan")
    lay.addWidget(card)
    values = [6.0 + i / 10 for i in range(14)]
    card.set_series(values, "7.3")
    _render(w)
    assert card.bars.track and len(card.bars.values) == 14
    assert card.text() == "Sleep 7.3 h"
    assert card.when.text() == "14 days"


def test_day_strip_centres_today(host):
    w, lay = host
    strip = kit.DayStrip(date(2026, 10, 24))
    lay.addWidget(strip)
    _render(w)
    assert [d.day for d in strip.days()] == [22, 23, 24, 25, 26]


def test_countdown_counts_to_zero_and_finishes(host):
    w, lay = host
    ring = kit.CountdownRing(120)
    lay.addWidget(ring)
    done: list[bool] = []
    ring.finished.connect(lambda: done.append(True))
    ring.start(3)
    _render(w)
    ring.tick()
    ring.tick()
    assert ring.left == 1 and not done
    ring.tick()
    assert done == [True] and ring.fraction == 0


def test_start_pill_round_buttons_category_row_and_tile_paint(host):
    w, lay = host
    start = kit.StartPill("Start walking")
    row = kit.CategoryRow("Sleep", "sleep", "cyan")
    tile = kit.IconTile("Nap", "10 min", icon="sleep", accent="sleep")
    buttons = [kit.RoundButton(k) for k in ("flag", "pause", "play", "stop")]
    for x in (start, row, tile, *buttons, kit.section("Today")):
        lay.addWidget(x)
    _render(w)
    clicks: list[bool] = []
    row.clicked.connect(lambda: clicks.append(True))
    start.click()
    assert row.has_tile and tile.text() == "Nap · 10 min"


def test_every_feature_has_short_copy_and_a_card():
    for f in FEATURES:
        assert f.reason and len(f.reason.split()) <= 3, f.id
        assert f.verb and len(f.verb.split()) <= 2, f.id
        assert f.icon
        if f.metric:
            assert m.card_for(f.metric).label


def test_series_lines_up_days_oldest_first():
    today = date(2026, 10, 24)
    days = [{"date": "2026-10-24", "metrics": {"recoveryScore": 70}},
            {"date": "2026-10-20", "metrics": {"recoveryScore": 50}}]
    values = m.series(days, "recoveryScore", today)
    assert len(values) == 14 and values[-1] == 70 and values[-5] == 50 and values[0] is None
    assert m.newest(values) == 70 and m.average(values) == 60
    assert m.CARDS["sleep"].show(450) == "7.5"
