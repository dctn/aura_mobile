"""The rules behind the richer alert actions: no Qt windows, no network."""

from __future__ import annotations

from datetime import date, datetime, timedelta
from zoneinfo import ZoneInfo

from aura_mobile.core import profile
from aura_mobile.ui import actions, alarms, sheets

TZ = ZoneInfo("Asia/Kolkata")


def at(day: date, hh: int, mm: int = 0) -> datetime:
    return datetime(day.year, day.month, day.day, hh, mm, tzinfo=TZ)


def test_light_plan_is_three_mornings_and_three_evenings():
    now = at(date(2026, 10, 21), 6, 0)
    plan = actions.light_times(now, "07:30")
    assert [p[0] for p in plan].count("Outdoor light · 15 min") == 3
    assert [p[0] for p in plan].count("Dim screens") == 3
    assert plan[0][1] == at(now.date(), 8, 0)  # 30 min after waking
    assert all(p[1].hour == 21 for p in plan if p[0] == "Dim screens")


def test_light_plan_stays_between_half_seven_and_half_nine():
    now = at(date(2026, 10, 21), 12, 0)  # this morning's light is past: it starts tomorrow
    early = actions.light_times(now, "05:00")
    late = actions.light_times(now, "10:00")
    assert early[0][0] == "Dim screens" and early[1][1] == at(date(2026, 10, 22), 7, 30)
    assert late[1][1] == at(date(2026, 10, 22), 9, 30)


def test_backfill_covers_the_gap_within_what_a_band_holds():
    now = at(date(2026, 10, 21), 12)
    assert actions.backfill_minutes(None, now) == 30
    assert actions.backfill_minutes(now - timedelta(minutes=5), now) == 30
    assert actions.backfill_minutes(now - timedelta(hours=3), now) == 180
    assert actions.backfill_minutes(now - timedelta(days=2), now) == 720


def test_sleep_check_offered_after_three_weeks():
    def a(day: str) -> dict:
        return {"featureId": "DX-03", "date": day}

    two = [a("2026-10-05"), a("2026-10-07"), a("2026-10-13")]
    assert actions.alert_weeks(two, "DX-03") == 2
    assert actions.alert_weeks([*two, a("2026-10-20"), {"featureId": "DX-04", "date": "2026-09-01"}], "DX-03") == 3


def test_mood_low_most_of_the_week():
    now = at(date(2026, 10, 21), 20)

    def m(day: int, value: int) -> dict:
        return {"kind": "mood", "mood": value, "takenAt": at(date(2026, 10, day), 9).isoformat()}

    assert actions.mood_low_this_week([m(19, 2), m(20, 1), m(21, 3)], now)
    assert not actions.mood_low_this_week([m(20, 1), m(21, 1)], now)  # only two
    assert not actions.mood_low_this_week([m(19, 4), m(20, 1), m(21, 2)], now)
    assert not actions.mood_low_this_week([m(1, 1), m(2, 1), m(3, 1)], now)  # weeks ago


def test_stress_change_text():
    assert "fell 12 points" in actions.stress_change_text({"status": "ready", "stressDrop": 12.4})
    assert "didn't drop" in actions.stress_change_text({"status": "ready", "stressDrop": -3})
    assert "computed" in actions.stress_change_text({"status": "pending", "stressDrop": None})
    assert "No band data" in actions.stress_change_text({"status": "noData"})


def test_hard_training_is_today_and_tomorrow_by_title():
    today = date(2026, 10, 21)
    events = [
        {"id": "a", "title": "Gym", "start": "2026-10-21T18:00:00+05:30"},
        {"id": "b", "title": "Long RUN", "date": "2026-10-22", "start": "2026-10-22T07:00:00+05:30"},
        {"id": "c", "title": "Gym", "start": "2026-10-23T18:00:00+05:30"},  # the day after tomorrow
        {"id": "d", "title": "Team sync", "start": "2026-10-21T10:00:00+05:30"},
        {"id": "pending-x", "title": "Workout", "start": "2026-10-21T19:00:00+05:30"},  # not saved yet
    ]
    assert [e["id"] for e in sheets.hard_training(events, today)] == ["a", "b"]


def test_insomnia_bands():
    assert "No sign" in sheets.SleepCheckDialog.meaning(7)
    assert "Some trouble" in sheets.SleepCheckDialog.meaning(8)
    assert "Moderate" in sheets.SleepCheckDialog.meaning(15)
    assert "Severe" in sheets.SleepCheckDialog.meaning(22)


def test_profile_gaps():
    assert profile.missing({"displayName": "A", "sex": "notSet", "heightCm": 170}) == ["birth date", "weight"]
    assert profile.missing(None) == []
    assert profile.paired_band({"settings": {"pairedBandId": "aura-band-1"}}) == "aura-band-1"
    assert profile.paired_band({"settings": {}}) is None


def test_alarms_fire_once_a_day_at_their_minute():
    goals = {"bedtime": "23:00", "wakeTime": "07:00"}
    fired: set = set()
    day = date(2026, 10, 21)
    assert alarms.due(at(day, 22, 29), goals, fired) == []
    assert [k for k, _ in alarms.due(at(day, 22, 30), goals, fired)] == ["bedtime"]
    assert alarms.due(at(day, 22, 31), goals, fired) == []  # already rung today
    assert [k for k, _ in alarms.due(at(day + timedelta(days=1), 7, 0), goals, fired)] == ["wake"]
    assert alarms.due(at(day, 12), {"bedtime": "bad"}, set()) == []
