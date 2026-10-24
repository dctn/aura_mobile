"""Config, the HTTP client's rules (auth, refresh, throttling) and the routes the features call. No network."""

from __future__ import annotations

import json
from datetime import datetime
from zoneinfo import ZoneInfo

import pytest

from aura_mobile.services.config import load_config
from aura_mobile.services.http import ApiError
from tests.conftest import body_of, reply


def test_config_is_dev_us_west_1(config):
    assert config.stage == "dev"
    assert config.region == "us-west-1"
    assert config.base_url("health-data").startswith("https://")


@pytest.mark.parametrize("override", [{"stage": "prod"}, {"region": "ap-south-1"}])
def test_config_refuses_prod_and_ap_south_1(tmp_path, override):
    raw = json.loads(load_config.__globals__["DEFAULT_CONFIG"].read_text(encoding="utf-8"))
    raw.update(override)
    path = tmp_path / "c.json"
    path.write_text(json.dumps(raw), encoding="utf-8")
    with pytest.raises(ValueError):
        load_config(path)


def test_requests_carry_bearer_and_time_zone(make_api):
    api, seen = make_api(lambda r: reply(200, {"alerts": []}))
    assert api.alerts("2026-10-01", "2026-10-21") == []
    req = seen[0]
    assert req.headers["Authorization"] == "Bearer tok-1"
    assert req.headers["X-Time-Zone"] == "Asia/Kolkata"
    assert req.url.path == "/v1/alerts"
    assert req.url.params["from"] == "2026-10-01"


def test_401_refreshes_once_then_retries(make_api, session):
    answers = iter([reply(401), reply(200, {"off": ["DX-01"]})])
    api, seen = make_api(lambda r: next(answers))
    assert api.alert_settings() == ["DX-01"]
    assert session.refreshes == 1
    assert seen[1].headers["Authorization"] == "Bearer tok-2"


def test_second_401_is_rejected(make_api, session):
    api, _ = make_api(lambda r: reply(401))
    with pytest.raises(ApiError):
        api.me()
    assert session.refreshes == 1
    assert session.rejections == 1


def test_503_without_error_body_is_retried(make_api):
    answers = iter([reply(503), reply(503), reply(200, {"checkins": []})])
    api, seen = make_api(lambda r: next(answers))
    assert api.checkins("2026-10-14", "2026-10-21") == []
    assert len(seen) == 3


def test_error_body_becomes_api_error(make_api):
    api, _ = make_api(lambda r: reply(429, {"error": {"code": "compute_limit", "message": "5 an hour"}}))
    with pytest.raises(ApiError) as caught:
        api.compute_day("2026-10-20")
    assert caught.value.code == "compute_limit"
    assert caught.value.status == 429


def test_missing_day_and_no_active_session_are_none(make_api):
    api, _ = make_api(lambda r: reply(404, {"error": {"code": "not_found", "message": "none"}}))
    assert api.metrics_day("2026-10-20") is None
    assert api.active_session() is None


def test_alert_settings_are_sorted_and_unique(make_api):
    api, seen = make_api(lambda r: reply(200, body_of(r)))
    assert api.set_alert_settings(["PB-01", "DX-04", "PB-01"]) == ["DX-04", "PB-01"]
    assert seen[0].method == "PUT"


def test_checkin_and_water_bodies(make_api):
    api, seen = make_api(lambda r: reply(201, {}))
    at = datetime(2026, 10, 21, 9, 0, tzinfo=ZoneInfo("Asia/Kolkata"))
    api.post_checkin("mood", taken_at=at, mood=2)
    api.log_water(250, taken_at=at)
    mood, water = body_of(seen[0]), body_of(seen[1])
    assert mood["kind"] == "mood" and mood["mood"] == 2 and mood["takenAt"].startswith("2026-10-21T09:00")
    assert water["type"] == "water" and water["data"] == {"ml": 250} and water["date"] == "2026-10-21"


def test_flowtime_has_no_planned_minutes(make_api):
    api, seen = make_api(lambda r: reply(201, {"session": {"id": "s"}, "joined": False}))
    api.start_session("focus", "flowtime", minutes=25, title="Deep work")
    api.start_session("focus", "timer", minutes=25, title="Deep work")
    assert body_of(seen[0])["plannedMinutes"] is None
    assert body_of(seen[1])["plannedMinutes"] == 25


def test_session_action_and_snooze_routes(make_api):
    api, seen = make_api(lambda r: reply(200, {}))
    api.session_action("s/1", "pause")
    api.notification_snooze("n1", 15)
    assert seen[0].url.raw_path == b"/v1/sessions/s%2F1/actions"
    assert body_of(seen[0]) == {"action": "pause"}
    assert body_of(seen[1]) == {"minutes": 15}


def test_paged_lists_follow_the_cursor(make_api):
    pages = iter([reply(200, {"sessions": [{"id": "a"}], "cursor": "c1"}), reply(200, {"sessions": [{"id": "b"}]})])
    api, seen = make_api(lambda r: next(pages))
    assert [s["id"] for s in api.sessions("2026-10-14", "2026-10-21")] == ["a", "b"]
    assert seen[1].url.params["cursor"] == "c1"


def test_consent_turns_health_data_on(make_api, config):
    api, seen = make_api(lambda r: reply(200, {}))
    api.grant_consent(config.consent_policy_version)
    body = body_of(seen[0])
    assert body["healthData"] is True and body["policyVersion"] == "2026-10"


def test_at_most_six_calls_open_at_once(make_api):
    """Dev runs 10 Lambdas at once for the whole account; the app keeps to 6 so the backend's own work still runs."""
    import threading
    import time as _time
    from concurrent.futures import ThreadPoolExecutor

    import httpx

    from aura_mobile.services.http import MAX_IN_FLIGHT

    lock = threading.Lock()
    state = {"open": 0, "most": 0}

    def slow(_request):
        with lock:
            state["open"] += 1
            state["most"] = max(state["most"], state["open"])
        _time.sleep(0.05)
        with lock:
            state["open"] -= 1
        return httpx.Response(200, json={"tasks": []})

    api, seen = make_api(slow)
    with ThreadPoolExecutor(max_workers=20) as pool:
        list(pool.map(lambda _i: api.client.get("task", "/v1/tasks"), range(20)))
    assert len(seen) == 20
    assert state["most"] == MAX_IN_FLIGHT == 6


def test_task_edit_sends_if_match_and_rereads_a_stale_version(make_api):
    answers = iter([reply(412, {"error": {"code": "stale", "message": "changed"}}),
                    reply(200, {"task": {"id": "t1", "version": 5}}),
                    reply(200, {"task": {"id": "t1", "version": 6}})])
    api, seen = make_api(lambda r: next(answers))
    api.update_task("t1", {"title": "New", "dueAt": None}, version=3)
    assert [r.method for r in seen] == ["PATCH", "GET", "PATCH"]
    assert seen[0].headers["If-Match"] == '"v3"' and seen[2].headers["If-Match"] == '"v5"'
    assert body_of(seen[0]) == {"title": "New", "dueAt": None}  # None clears the field


def test_task_create_keeps_the_client_id_and_drops_empty_fields(make_api):
    api, seen = make_api(lambda r: reply(201, {"task": {}}))
    api.create_task("Call", horizon="week", task_id="task-1", dueAt="2026-10-27T00:00:00+05:30", hasTime=False,
                    estimateMinutes=None)
    assert body_of(seen[0]) == {"id": "task-1", "title": "Call", "horizon": "week",
                                "dueAt": "2026-10-27T00:00:00+05:30", "hasTime": False}
    api.delete_task("task-1")
    api.complete_task("task-1")
    assert [(r.method, r.url.path) for r in seen[1:]] == [("DELETE", "/v1/tasks/task-1"),
                                                         ("POST", "/v1/tasks/task-1/complete")]


def test_event_edit_without_a_version_reads_it_first(make_api):
    answers = iter([reply(200, {"event": {"id": "e1", "version": 2}}), reply(200, {"event": {}})])
    api, seen = make_api(lambda r: next(answers))
    api.update_event("e1", {"title": "Lunch"})
    assert [r.method for r in seen] == ["GET", "PATCH"] and seen[1].headers["If-Match"] == '"v2"'
