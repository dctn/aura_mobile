"""Live end-to-end check against dev: every route the app uses, with the account signed in on this PC.

Sign in once in the app first (the session is kept in Credential Manager), then:

    uv run python scripts/live_check.py            # everything except the lab
    uv run python scripts/live_check.py --lab      # also run the DX-04 lab scenario and wait for its alert

It writes ordinary test data to that dev account: a check-in, a glass of water, a task (completed), a short timer
session (stopped), one calendar event, an export, and with --lab one simulated band day. It never deletes the account.
"""

from __future__ import annotations

import argparse
import sys
import time
import traceback
from collections.abc import Callable
from datetime import datetime, timedelta
from typing import Any
from zoneinfo import ZoneInfo

from aura_mobile.services.api import AuraApi
from aura_mobile.services.auth import AuthSession, CognitoClient, TokenStore
from aura_mobile.services.config import load_config
from aura_mobile.services.http import ServiceClient
from aura_mobile.services.sim.band import live_packets
from aura_mobile.services.sim.runner import LabRunner, LabState
from aura_mobile.services.sim.scenarios import BY_FEATURE

results: list[tuple[str, bool, str]] = []


def check(name: str, fn: Callable[[], Any], show: Callable[[Any], str] = lambda r: "") -> Any:
    try:
        value = fn()
    except Exception as exc:  # report and carry on
        results.append((name, False, f"{exc.__class__.__name__}: {exc}"))
        print(f"FAIL  {name}: {exc!r}", flush=True)
        if "--trace" in sys.argv:
            traceback.print_exc()
        return None
    detail = show(value)
    results.append((name, True, detail))
    print(f"ok    {name}  {detail}".rstrip(), flush=True)
    return value


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--lab", action="store_true", help="run the DX-04 scenario and wait for its alert")
    parser.add_argument("--trace", action="store_true")
    args = parser.parse_args()

    config = load_config()
    cognito = CognitoClient(config)
    session = AuthSession(cognito, TokenStore(config.stage))
    api = AuraApi(ServiceClient(config, session))
    check("identity  GET /v1/config (public)", api.public_config, lambda c: f"clients={sorted(c.get('clients', {}))}")
    if session.restore() is None:
        print("\nNo saved session. Sign in once in the app (uv run aura-mobile), then run this again.")
        return 2
    tz = ZoneInfo(api.time_zone)
    now = datetime.now(tz)
    today = now.date()
    week_ago = (today - timedelta(days=7)).isoformat()

    me = check("identity  GET /v1/me", api.me, lambda m: f"{m.get('email')} v{m.get('version')}") or {}
    check("identity  GET /v1/me/consent", api.consent, lambda c: f"healthData={c.get('healthData', c)}")
    check("identity  health data on (as the app does at sign-in)",
          lambda: api.ensure_consent(config.consent_policy_version),
          lambda turned_on: "turned it on" if turned_on else "already on")
    water_goal = int((me.get("healthGoals") or {}).get("waterGoalMl") or 2000)
    check("identity  PUT /v1/me/profile (same water goal)",
          lambda: api.update_profile_fresh({"healthGoals": {"waterGoalMl": water_goal}}),
          lambda m: f"v{m.get('version')}")

    off = check("health    GET /v1/alerts/settings", api.alert_settings, lambda o: f"off={o}")
    if off is not None:
        check("health    PUT /v1/alerts/settings (unchanged)", lambda: api.set_alert_settings(off), lambda o: f"off={o}")
    check("health    GET /v1/alerts", lambda: api.alerts((today - timedelta(days=40)).isoformat(), today.isoformat()),
          lambda a: f"{len(a)} alerts, {sum(1 for x in a if not x.get('done'))} open")
    check("health    GET /v1/metrics/daily (10 days)",
          lambda: api.metrics_range((today - timedelta(days=10)).isoformat(), today.isoformat()),
          lambda d: f"{len(d)} days {[x.get('status') for x in d][:5]}")
    check("health    GET /v1/metrics/daily/{yesterday}", lambda: api.metrics_day((today - timedelta(days=1)).isoformat()),
          lambda d: "no row" if d is None else f"status={d.get('status')}")
    check("health    POST /v1/checkins (connection 4)", lambda: api.post_checkin("connection", taken_at=now, connection=4))
    check("health    GET /v1/checkins", lambda: api.checkins(week_ago, today.isoformat()), lambda c: f"{len(c)} this week")
    check("health    POST /v1/logs (water 250)", lambda: api.log_water(250, taken_at=now))
    check("health    GET /v1/logs?type=water", lambda: api.logs(week_ago, today.isoformat(), "water"),
          lambda x: f"{len(x)} this week")
    check("health    POST /v1/samples (Reconnect, 30 min)",
          lambda: [api.post_samples(p) for p in (live_packets(api.time_zone)[:15], live_packets(api.time_zone)[15:])],
          lambda r: "accepted")

    inbox = check("notify    GET /v1/notifications", lambda: api.notifications(limit=20), lambda n: f"{len(n)} items") or []
    unread = [n for n in inbox if not n.get("readAt")]
    if unread:
        check("notify    POST /v1/notifications/{id}/read", lambda: api.notification_read(str(unread[0]["id"])))

    start = datetime.combine(today + timedelta(days=1), datetime.min.time(), tz).replace(hour=7)
    check("calendar  POST /v1/calendar/events", lambda: api.create_event("Aura demo check", start, start + timedelta(minutes=15)))
    check("calendar  GET /v1/calendar/events (7 days)",
          lambda: api.events(today.isoformat(), (today + timedelta(days=7)).isoformat()), lambda e: f"{len(e)} events")

    task = check("task      POST /v1/tasks", lambda: api.create_task("Aura demo check"), lambda t: "created")
    task_id = ((task or {}).get("task") or task or {}).get("id")
    if task_id:
        check("task      POST /v1/tasks/{id}/complete", lambda: api.complete_task(str(task_id)))
    check("task      GET /v1/tasks", api.tasks, lambda t: f"{len(t)} tasks")

    check("session   GET /v1/sessions/active", api.active_session, lambda s: "none" if s is None else s.get("state"))
    started = check("session   POST /v1/sessions (timer 1 min)",
                    lambda: api.start_session("recovery", "timer", minutes=1, title="Aura demo check"),
                    lambda b: f"joined={b.get('joined')}")
    sid = ((started or {}).get("session") or {}).get("id")
    if sid and not (started or {}).get("joined"):
        for verb in ("pause", "resume", "stop"):
            check(f"session   POST /v1/sessions/{{id}}/actions {verb}", lambda v=verb: api.session_action(str(sid), v),
                  lambda b: str((b.get("session") or b).get("state", "")))
    check("session   GET /v1/sessions (week)", lambda: api.sessions(week_ago, today.isoformat()), lambda s: f"{len(s)}")

    export = check("identity  POST /v1/me/exports", api.request_export,
                   lambda b: str((b.get("export") or {}).get("status")))
    export_id = ((export or {}).get("export") or {}).get("exportId")
    if export_id:
        check("identity  GET /v1/me/exports/{id}", lambda: api.export_status(str(export_id)),
              lambda b: str((b.get("export") or {}).get("status")))

    if args.lab:
        lab_check(api)

    failed = [r for r in results if not r[1]]
    print(f"\n{len(results) - len(failed)} passed, {len(failed)} failed")
    for name, _ok, detail in failed:
        print(f"  FAIL {name}: {detail}")
    return 1 if failed else 0


def lab_check(api: AuraApi) -> None:
    tokens = api.client.session.tokens
    runner = LabRunner(api, LabState.load(tokens.sub if tokens else "anon"))
    run = check("lab       DX-04 send band day", lambda: runner.run(BY_FEATURE["DX-04"]),
                lambda r: f"{r.posted_packets} minutes for {r.target}")
    if run is None:
        return
    print("          waiting for the day's own compute and the alert (up to 8 min)...", flush=True)
    alert = None
    deadline = time.monotonic() + 8 * 60
    while time.monotonic() < deadline and alert is None:
        time.sleep(30)
        alert = runner.find_alert(run)
    if alert is None:
        status = runner.day_status(run)
        results.append(("lab       DX-04 alert raised", False,
                        f"no alert after 8 min; day status={None if status is None else status.get('status')}"))
        print("FAIL  lab       DX-04 alert raised", flush=True)
        return
    results.append(("lab       DX-04 alert raised", True, str(alert.get("id"))))
    print(f"ok    lab       DX-04 alert raised  {alert.get('id')}", flush=True)
    check("health    POST /v1/alerts/{id}/done", lambda: api.alert_done(str(alert["id"])))


if __name__ == "__main__":
    sys.exit(main())
