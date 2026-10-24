"""The calendar's data: which day a task or event falls on, the month grid, hours and overlap lanes."""

from __future__ import annotations

from datetime import UTC, date, datetime, timedelta
from zoneinfo import ZoneInfo

from aura_mobile.core import agenda

TZ = ZoneInfo("Asia/Kolkata")
TODAY = date(2026, 10, 24)  # a Saturday


def test_task_day_follows_block_then_due_then_planned():
    assert agenda.task_when({"scheduledAt": "2026-10-25T04:30:00Z", "dueAt": "2026-10-30T00:00:00Z"}, TZ) == (
        date(2026, 10, 25), datetime(2026, 10, 25, 10, 0, tzinfo=TZ))
    day, at = agenda.task_when({"dueAt": "2026-10-26T09:00:00+05:30", "hasTime": False}, TZ)
    assert day == date(2026, 10, 26) and at is None
    day, at = agenda.task_when({"dueAt": "2026-10-26T09:00:00+05:30", "hasTime": True}, TZ)
    assert at is not None and at.hour == 9
    assert agenda.task_when({"plannedFor": "2026-10-27"}, TZ) == (date(2026, 10, 27), None)
    assert agenda.task_when({}, TZ) == (None, None)


def test_done_task_sits_on_the_day_it_was_done_and_open_undated_on_today():
    done = {"status": "completed", "completedAt": "2026-10-23T20:00:00Z", "dueAt": "2026-10-20T00:00:00Z"}
    assert agenda.task_day(done, TZ, TODAY) == date(2026, 10, 24)  # 01:30 IST the next day
    assert agenda.task_day({"status": "pending"}, TZ, TODAY) == TODAY
    assert agenda.task_day({"status": "completed"}, TZ, TODAY) is None


def test_day_items_splits_timeline_and_todo():
    events = [{"id": "e1", "title": "Standup", "kind": "meeting", "start": "2026-10-24T09:00:00+05:30",
               "end": "2026-10-24T09:15:00+05:30"},
              {"id": "e2", "title": "Tomorrow", "start": "2026-10-25T09:00:00+05:30"}]
    tasks = [{"id": "t1", "title": "b anytime", "status": "pending"},
             {"id": "t2", "title": "Timed", "status": "pending", "dueAt": "2026-10-24T11:00:00+05:30",
              "hasTime": True, "estimateMinutes": 45},
             {"id": "t3", "title": "A done", "status": "completed", "completedAt": "2026-10-24T06:00:00Z"}]
    day = agenda.day_items(TODAY, events, tasks, TZ, TODAY)
    assert [i.key for i in day.timed] == ["event:e1", "task:t2"]
    assert day.timed[0].minutes == 15 and day.timed[0].look == "meeting" and day.timed[1].minutes == 45
    assert [i.title for i in day.todo] == ["b anytime", "A done"]  # open first
    assert day.count == 4
    marks = agenda.marks(events, tasks, TZ, TODAY)
    assert marks[TODAY] == {"event", "task"} and marks[date(2026, 10, 25)] == {"event"}


def test_month_grid_is_whole_weeks_monday_first():
    grid = agenda.month_grid(TODAY)
    assert len(grid) % 7 == 0 and grid[0].weekday() == 0
    assert grid[0] <= date(2026, 10, 1) and date(2026, 10, 31) <= grid[-1]
    assert agenda.week_of(TODAY)[0] == date(2026, 10, 19)
    assert agenda.add_months(date(2026, 1, 31), 1) == date(2026, 2, 28)
    assert agenda.add_months(date(2026, 12, 5), 1) == date(2027, 1, 5)
    assert agenda.add_months(date(2026, 1, 5), -1) == date(2025, 12, 5)


def test_overlapping_items_share_lanes():
    at = datetime(2026, 10, 24, 9, 0, tzinfo=TZ)
    a = agenda.Item("event", "a", "A", at, 60)
    b = agenda.Item("task", "b", "B", at + timedelta(minutes=30), 30)
    c = agenda.Item("event", "c", "C", at + timedelta(minutes=60), 30)  # starts as A ends: a new group
    lanes = agenda.lanes([a, b, c])
    assert lanes["event:a"] == (0, 2) and lanes["task:b"] == (1, 2) and lanes["event:c"] == (0, 1)


def test_horizon_follows_the_pc_lists():
    assert agenda.horizon_for(None, TODAY) == "today"
    assert agenda.horizon_for(TODAY - timedelta(days=3), TODAY) == "today"
    assert agenda.horizon_for(TODAY + timedelta(days=6), TODAY) == "week"
    assert agenda.horizon_for(TODAY + timedelta(days=7), TODAY) == "month"


def test_parse_reads_z_and_naive_times():
    assert agenda.parse("2026-10-24T00:00:00Z", TZ) == datetime(2026, 10, 24, 0, 0, tzinfo=UTC)
    assert agenda.parse("2026-10-24T08:00:00", TZ).hour == 8
    assert agenda.parse("not a time", TZ) is None and agenda.parse(None, TZ) is None
