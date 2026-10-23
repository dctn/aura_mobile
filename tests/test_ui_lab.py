"""Test lab rows: every look at AWS says what it found, never just "Looking…"."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta

import pytest

from aura_mobile.services.sim.runner import LabCheck, LabRun
from tests import test_ui as base
from tests.test_ui import settle

#: no widget may flash up as its own window (see conftest.stray_windows)
pytestmark = pytest.mark.usefixtures("stray_windows")

#: the signed-in phone fixture from the shared smoke tests
phone = base.phone

RULE = "sleepLatencyMin > 30 on 4+ nights"


def _watched_row(window, fid="DX-03"):
    window._open_lab()
    settle()
    lab = window.lab
    run = LabRun(fid, "2026-10-21", ["2026-10-17", "2026-10-21"], datetime.now(UTC).isoformat(), 3325, accepted=True)
    lab._watching[fid] = (run, datetime.now(UTC) + timedelta(minutes=10))
    return lab, lab.rows[fid]


def test_a_check_while_watching_shows_what_aws_computed(phone):
    window, _ctx, _api = phone
    lab, row = _watched_row(window)
    lab._make_found("DX-03")(LabCheck("2026-10-21", computed=True, final=True,
                                      computed_at="2026-10-22T15:35:46Z", field="sleepLatencyMin", value=0))
    text = row.status.text()
    assert "AWS computed 21 Oct at " in text
    assert "sleepLatencyMin = 0 (rule: sleepLatencyMin > 30 on 4+ nights)" in text
    assert "watching until" in text


def test_a_day_not_computed_yet_says_so(phone):
    window, _ctx, _api = phone
    lab, row = _watched_row(window, "DX-04")
    lab._make_found("DX-04")(LabCheck("2026-10-21"))
    assert "AWS hasn't computed 21 Oct yet" in row.status.text()


def test_the_last_look_says_watching_stopped(phone):
    window, _ctx, _api = phone
    lab, row = _watched_row(window)
    del lab._watching["DX-03"]
    lab._make_found("DX-03")(LabCheck("2026-10-21", computed=True, field="sleepLatencyMin", value=12))
    assert "stopped watching" in row.status.text()


def test_an_alert_passes_the_row(phone):
    window, _ctx, _api = phone
    lab, row = _watched_row(window)
    run = lab._watching["DX-03"][0]
    run.found_alert = "alert-2026-10-21-DX-03"  # what LabRunner.find_alert records on the saved run
    window.ctx.lab.state.runs.append(run)
    lab._make_found("DX-03")(LabCheck("2026-10-21", alert={"id": run.found_alert,
                                                           "title": "Trouble falling asleep lately",
                                                           "date": "2026-10-21"}))
    settle()
    assert row.status.text().startswith("Passed")
    assert "DX-03" not in lab._watching


def test_a_night_counting_rule_lists_each_night(phone):
    window, _ctx, _api = phone
    lab, row = _watched_row(window)
    del lab._watching["DX-03"]
    nights = (("2026-10-11", 0), ("2026-10-12", 0), ("2026-10-13", 26), ("2026-10-14", 25), ("2026-10-15", 37))
    lab._make_found("DX-03")(LabCheck("2026-10-15", computed=True, field="sleepLatencyMin", value=37, nights=nights))
    text = row.status.text()
    assert "sleepLatencyMin by night 0, 0, 26, 25, 37. 1 of 5 nights over 30; the rule needs 4" in text


def test_the_month_row_lists_what_fired_and_what_is_still_coming(phone):
    window, _ctx, _api = phone
    window._open_lab()
    settle()
    lab = window.lab
    run = LabRun("ALL", "2026-10-19", ["2026-09-23", "2026-10-19"], datetime.now(UTC).isoformat(), 30000,
                 accepted=True)
    lab._watching["ALL"] = (run, datetime.now(UTC) + timedelta(minutes=160))
    fired = {"DX-04": {"id": "a1", "title": "Short night", "date": "2026-10-13"},
             "PB-05": {"id": "a2", "title": "Worth a health check", "date": "2026-10-17"}}
    lab._make_found("ALL")(LabCheck("2026-10-19", computed=True, fired=fired))
    text = lab.rows["ALL"].status.text()
    assert text.startswith("2 of 16 fired: DX-04, PB-05. Waiting for DX-01")
    assert "watching until" in text and "ALL" in lab._watching
    assert lab.rows["DX-04"].status.text().startswith("Passed in the all-at-once run")


def test_the_month_passes_when_every_alert_is_in(phone):
    window, _ctx, _api = phone
    window._open_lab()
    settle()
    lab = window.lab
    month = lab.rows["ALL"]
    run = LabRun("ALL", "2026-10-19", ["2026-10-19"], datetime.now(UTC).isoformat(), 1, accepted=True)
    lab._watching["ALL"] = (run, datetime.now(UTC) + timedelta(minutes=10))
    fired = {f: {"id": f, "date": "2026-10-19"} for f in month.scenario.feature_ids}
    lab._make_found("ALL")(LabCheck("2026-10-19", computed=True, fired=fired))
    assert month.status.text() == "Passed · all 16 alerts fired."
    assert "ALL" not in lab._watching
