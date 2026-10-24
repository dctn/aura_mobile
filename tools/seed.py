"""Fill the demo website's account with a believable month, through the normal app APIs (dev us-west-1 only).

Signs in as the demo account (``create_account.py`` saved its sign-in in Credential Manager) and writes what a
person using the phone would: never another account, never a table.

    uv run python seed.py              # profile + health-data consent + the Test lab's all-at-once month
    uv run python seed.py --wait       # ...then watch the alerts arrive (about 15 min; NI-04 comes ~2 h later)
    uv run python seed.py --extras     # after NI-04 is in: calendar for the next 14 days + today's water
    uv run python seed.py --calendar   # top the calendar up again later (skips what is already there)

The month is ``ALL_AT_ONCE`` (``demo/src/aura_mobile/services/sim/scenarios.py``): 27 past days of band data that
raise 16 of the 18 alerts. It needs the birth date first (PB-04) and only works once per account. Today's water is
held back until NI-04 has fired, because logging water would stop its hydration alert.
"""

from __future__ import annotations

import argparse
import sys
import time
from datetime import date, datetime, timedelta
from zoneinfo import ZoneInfo

from aura_mobile.services.api import AuraApi
from aura_mobile.services.auth import AuthSession, CognitoClient, MemoryTokenStore
from aura_mobile.services.config import load_config
from aura_mobile.services.http import ServiceClient
from aura_mobile.services.secrets import get_secret
from aura_mobile.services.sim.runner import LabRunner, LabState
from aura_mobile.services.sim.scenarios import ALL_AT_ONCE

from create_account import EMAIL_KEY, PASSWORD_KEY

#: the demo-site Lambda counts days here too (DEMO_TIME_ZONE in aws_docs services/demo-site/infra)
ZONE = "Asia/Kolkata"
PROFILE = {"displayName": "Asha Rao", "birthDate": "1994-06-12", "heightCm": 165, "weightKg": 61.0}
GOALS = {"waterGoalMl": 2000, "bedtime": "23:00", "wakeTime": "07:00", "sleepNeedMinutes": 480, "stepsPerDay": 8000}

#: weekday (Mon=0) -> events: (title, "HH:MM", minutes)
WEEK_EVENTS: dict[int, list[tuple[str, str, int]]] = {
    0: [("Team stand-up", "09:30", 15), ("Deep work: quarterly report", "10:00", 120), ("1:1 with Meera", "16:00", 30)],
    1: [("Team stand-up", "09:30", 15), ("Gym: strength", "18:30", 60)],
    2: [("Team stand-up", "09:30", 15), ("Design review", "15:00", 60), ("Deep work: roadmap draft", "10:30", 90)],
    3: [("Team stand-up", "09:30", 15), ("Lunch with Priya", "13:00", 60), ("Gym: strength", "18:30", 60)],
    4: [("Team stand-up", "09:30", 15), ("Sprint demo", "14:00", 45), ("Weekly planning", "16:30", 30)],
    5: [("Long walk in the park", "08:00", 60), ("Family dinner", "19:30", 90)],
    6: [("Yoga class", "09:00", 60), ("Meal prep", "17:00", 60)],
}
#: (title, days from today, "HH:MM" or None for a day-only task, estimate minutes)
TASKS: list[tuple[str, int, str | None, int | None]] = [
    ("Reply to Ravi's email", 0, None, 10),
    ("Review pull request #42", 0, "11:30", 30),
    ("Buy groceries", 1, None, None),
    ("Book dentist appointment", 2, None, 5),
    ("Call the bank about the card", 3, "12:30", 15),
    ("Read 20 pages of Atomic Habits", 4, None, 30),
    ("Plan the weekend trip", 5, None, 45),
    ("Pay the electricity bill", 7, None, 5),
    ("Prepare slides for the offsite", 9, "15:00", 90),
    ("Renew gym membership", 12, None, 10),
]


def signed_in() -> tuple[AuraApi, str]:
    """The API as the demo account, and its Cognito sub (the Test lab keeps its used dates under it)."""
    email, password = get_secret(EMAIL_KEY), get_secret(PASSWORD_KEY)
    if not email or not password:
        sys.exit("No demo account in Credential Manager. Run create_account.py first.")
    config = load_config()
    cognito = CognitoClient(config)
    session = AuthSession(cognito, MemoryTokenStore())
    tokens = cognito.sign_in_password(email, password)
    session.begin(tokens)
    return AuraApi(ServiceClient(config, session, time_zone=ZONE)), tokens.sub


def set_up_profile(api: AuraApi) -> dict:
    me = api.me()  # Identity makes the profile on the first read
    api.ensure_consent(load_config().consent_policy_version)
    if me.get("birthDate") != PROFILE["birthDate"] or me.get("displayName") != PROFILE["displayName"]:
        me = api.update_profile_fresh(PROFILE, me.get("version"))
        me = api.update_profile_fresh({"healthGoals": GOALS}, me.get("version"))
    print(f"Profile: {me.get('displayName')}, born {me.get('birthDate')}; health data on")
    return me


def run_month(api: AuraApi, sub: str) -> None:
    state = LabState.load(sub)
    if state.latest_run(ALL_AT_ONCE.feature_id) is not None:
        print("The month was already sent for this account; not sending it again.")
        return
    runner = LabRunner(api, state)
    last = [0.0]

    def progress(message: str, fraction: float) -> None:
        if fraction - last[0] >= 0.1 or fraction >= 1.0:
            last[0] = fraction
            print(f"  {round(fraction * 100):3d}%  {message}", flush=True)

    run = runner.run(ALL_AT_ONCE, progress=progress)
    print(f"Month sent: {min(run.dates)} .. {run.target}. Days compute about 2-6 min after the last packet.")


def watch(api: AuraApi, minutes: int = 15) -> None:
    today = datetime.now(ZoneInfo(ZONE)).date()
    seen = 0
    for _ in range(minutes * 3):
        alerts = api.alerts((today - timedelta(days=40)).isoformat(), today.isoformat())
        ids = sorted({str(a.get("featureId")) for a in alerts})
        if len(ids) != seen:
            seen = len(ids)
            print(f"  {seen} features have alerts: {', '.join(ids)}", flush=True)
        if seen >= 16:
            return
        time.sleep(20)


def _at(day: date, hhmm: str) -> datetime:
    h, m = (int(x) for x in hhmm.split(":"))
    return datetime(day.year, day.month, day.day, h, m, tzinfo=ZoneInfo(ZONE))


def fill_calendar(api: AuraApi, days: int = 14) -> None:
    """Events and tasks for today and the next ``days`` days; anything with the same title on the same day is kept."""
    tz = ZoneInfo(ZONE)
    today = datetime.now(tz).date()
    end = today + timedelta(days=days)
    have = {(str(e.get("title")), str(e.get("start"))[:10]) for e in api.events(today.isoformat(), end.isoformat())}
    added = 0
    for i in range(days + 1):
        day = today + timedelta(days=i)
        for title, hhmm, minutes in WEEK_EVENTS[day.weekday()]:
            start = _at(day, hhmm)
            if (title, day.isoformat()) in have or start < datetime.now(tz):
                continue
            api.create_event(title, start, start + timedelta(minutes=minutes))
            added += 1
    tasks = {str(t.get("title")) for t in api.tasks() if str(t.get("status") or "") != "completed"}
    for title, offset, hhmm, estimate in TASKS:
        if title in tasks:
            continue
        day = today + timedelta(days=offset)
        due = _at(day, hhmm or "18:00")
        horizon = "today" if offset == 0 else "week" if offset < 7 else "month"
        api.create_task(title, horizon=horizon, dueAt=due.isoformat(), hasTime=hhmm is not None,
                        estimateMinutes=estimate)
        added += 1
    print(f"Calendar: {added} events and tasks added for {today} .. {end}")


def log_water(api: AuraApi) -> None:
    tz = ZoneInfo(ZONE)
    now = datetime.now(tz)
    today = now.date()
    alerts = api.alerts((today - timedelta(days=35)).isoformat(), today.isoformat())  # dated on the band days
    if not any(a.get("featureId") == "NI-04" for a in alerts):
        print("NI-04 hasn't fired yet; today's water is held back so it still can. Run --extras again later.")
        return
    if api.logs(today.isoformat(), today.isoformat(), "water"):
        print("Water already logged today.")
        return
    glasses = [t for t in ("08:30", "11:00", "14:30") if _at(today, t) < now]
    for t in glasses:
        api.log_water(250, taken_at=_at(today, t))
    print(f"Water: {len(glasses)} glasses logged today")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--wait", action="store_true", help="watch the alerts arrive after sending the month")
    parser.add_argument("--extras", action="store_true", help="calendar + today's water (after NI-04 fired)")
    parser.add_argument("--calendar", action="store_true", help="only top the calendar up")
    args = parser.parse_args()
    api, sub = signed_in()
    if args.calendar:
        fill_calendar(api)
        return 0
    if args.extras:
        fill_calendar(api)
        log_water(api)
        return 0
    set_up_profile(api)
    run_month(api, sub)
    if args.wait:
        watch(api)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
