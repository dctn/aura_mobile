"""The calendar drag's maths: quarter-hour drops, stretching, what may move and what a drop writes."""

from __future__ import annotations

from datetime import date, datetime
from zoneinfo import ZoneInfo

from aura_mobile.core import drag as dg
from aura_mobile.core.agenda import Item

TZ = ZoneInfo("Asia/Kolkata")
TODAY = date(2026, 10, 24)  # a Saturday


def _event(hour: int, minutes: int = 60) -> Item:
    return Item("event", "e1", "Standup", datetime(2026, 10, 24, hour, 0, tzinfo=TZ), minutes, look="meeting")


def test_drop_start_snaps_to_quarter_hours_inside_the_day():
    assert dg.drop_start(9 * 60 + 7, 0, 60) == 9 * 60
    assert dg.drop_start(9 * 60 + 8, 0, 60) == 9 * 60 + 15
    assert dg.drop_start(9 * 60 + 40, 30, 60) == 9 * 60 + 15  # less where it was grabbed
    assert dg.drop_start(-50, 0, 60) == 0
    assert dg.drop_start(24 * 60 + 90, 0, 60) == 23 * 60


def test_stretch_keeps_at_least_a_quarter_hour_and_ends_at_midnight():
    assert dg.stretch_to(10 * 60 + 37, 9 * 60) == 90
    assert dg.stretch_to(9 * 60 + 2, 9 * 60) == dg.MIN_LENGTH
    assert dg.stretch_to(26 * 60, 23 * 60) == 60


def test_done_and_block_placed_tasks_do_not_move():
    task = Item("task", "t1", "Report", None, 30)
    assert dg.can_drag(task) and dg.can_drag(_event(9))
    assert not dg.can_drag(Item("task", "t2", "Done", None, 30, done=True))
    assert not dg.can_drag(Item("task", "t3", "Block", None, 30, raw={"blockId": "b1"}))
    assert not dg.can_drag(Item("task", "t4", "Block", None, 30, raw={"scheduledAt": "2026-10-24T04:30:00Z"}))


def test_same_spot_is_no_change():
    ev = _event(9)
    assert dg.same(ev, TODAY, dg.Target(TODAY, 9 * 60, 60))
    assert not dg.same(ev, TODAY, dg.Target(TODAY, 9 * 60, 75))
    assert not dg.same(ev, TODAY, dg.Target(date(2026, 10, 25), 9 * 60, 60))
    todo = Item("task", "t1", "Report", None, 30)
    assert dg.same(todo, TODAY, dg.Target(TODAY, None, 45))  # a to-do has no length to change


def test_task_changes_write_day_time_length_and_list():
    timed = dg.task_changes(dg.Target(date(2026, 10, 27), 10 * 60 + 15, 45), TZ, TODAY)
    assert timed == {"dueAt": "2026-10-27T10:15:00+05:30", "hasTime": True, "plannedFor": None, "horizon": "week",
                     "estimateMinutes": 45}
    untimed = dg.task_changes(dg.Target(date(2026, 11, 20), None, 30), TZ, TODAY)
    assert untimed["hasTime"] is False and untimed["horizon"] == "month" and "estimateMinutes" not in untimed
    assert dg.event_start(dg.Target(TODAY, 14 * 60 + 45, 60), TZ).isoformat() == "2026-10-24T14:45:00+05:30"


def test_target_text():
    assert dg.target_text(dg.Target(date(2026, 10, 25), 10 * 60 + 15, 45)) == "Sun 25 Oct · 10:15–11:00"
    assert dg.target_text(dg.Target(date(2026, 10, 25), 23 * 60 + 30, 60)) == "Sun 25 Oct · 23:30–24:00"
    assert dg.target_text(dg.Target(date(2026, 10, 25), None, 30)) == "Sun 25 Oct"
