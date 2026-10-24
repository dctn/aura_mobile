"""The calendar's data: which day each event and task falls on, and what a day shows.

Events come from Calendar, tasks from Task, both for the signed-in person, so a task added on the PC (on the same
backend) shows here after the next load. A task's day is the first of: its calendar block (``scheduledAt``), its due
date (``dueAt``; ``hasTime`` says whether the time counts), its planned day (``plannedFor``). An open task with none
of them is "Anytime" and shows on today. A done task shows on the day it was done.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date, datetime, timedelta, tzinfo
from typing import Any

#: a timed task with no estimate takes this long on the timeline
TASK_MINUTES = 30
#: an empty day's hours open here
DAY_FROM = 8


@dataclass
class Item:
    """One thing on a day: an event or a task."""

    kind: str  # "event" | "task"
    id: str
    title: str
    #: the start, when it has a time; None for a task with only a day (or none)
    start: datetime | None
    minutes: int
    done: bool = False
    #: the event's kind (meeting / recovery / focus); "task" for tasks
    look: str = "task"
    raw: dict[str, Any] = field(default_factory=dict)

    @property
    def key(self) -> str:
        return f"{self.kind}:{self.id}"

    @property
    def end(self) -> datetime | None:
        return self.start + timedelta(minutes=self.minutes) if self.start is not None else None


def parse(value: Any, tz: tzinfo) -> datetime | None:
    if not value:
        return None
    try:
        moment = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    except ValueError:
        return None
    if moment.tzinfo is None:
        moment = moment.replace(tzinfo=tz)
    return moment.astimezone(tz)


def is_done(task: dict) -> bool:
    return str(task.get("status") or "") == "completed"


def task_when(task: dict, tz: tzinfo) -> tuple[date | None, datetime | None]:
    """The task's day and, when it has one, its time."""
    if is_done(task):
        done_at = parse(task.get("completedAt"), tz)
        if done_at is not None:
            return done_at.date(), None
    block = parse(task.get("scheduledAt"), tz)
    if block is not None:
        return block.date(), block
    due = parse(task.get("dueAt"), tz)
    if due is not None:
        return due.date(), due if task.get("hasTime") else None
    planned = task.get("plannedFor")
    if planned:
        try:
            return date.fromisoformat(str(planned)[:10]), None
        except ValueError:
            pass
    return None, None


def task_item(task: dict, tz: tzinfo) -> Item:
    _day, at = task_when(task, tz)
    return Item("task", str(task.get("id") or ""), str(task.get("title") or "Task"), at,
                int(task.get("estimateMinutes") or TASK_MINUTES), is_done(task), "task", task)


def event_item(event: dict, tz: tzinfo) -> Item | None:
    start, end = parse(event.get("start"), tz), parse(event.get("end"), tz)
    if start is None:
        return None
    minutes = int((end - start).total_seconds() // 60) if end is not None and end > start else TASK_MINUTES
    return Item("event", str(event.get("id") or ""), str(event.get("title") or "Event"), start, minutes,
                look=str(event.get("kind") or "focus"), raw=event)


def task_day(task: dict, tz: tzinfo, today: date) -> date | None:
    """The day the calendar shows the task on: its own day, or today for an open task with none."""
    day, _at = task_when(task, tz)
    if day is None and not is_done(task):
        return today
    return day


@dataclass
class Day:
    """What one day shows: the timeline (events and timed tasks) and the to-do list (tasks without a time)."""

    timed: list[Item]
    todo: list[Item]

    @property
    def count(self) -> int:
        return len(self.timed) + len(self.todo)


def day_items(day: date, events: list[dict], tasks: list[dict], tz: tzinfo, today: date) -> Day:
    timed: list[Item] = []
    todo: list[Item] = []
    for e in events or []:
        item = event_item(e, tz)
        if item is not None and item.start is not None and item.start.date() == day:
            timed.append(item)
    for task in tasks or []:
        if task_day(task, tz, today) != day:
            continue
        item = task_item(task, tz)
        (timed if item.start is not None else todo).append(item)
    timed.sort(key=lambda i: (i.start, i.title))
    todo.sort(key=lambda i: (i.done, i.title.lower()))
    return Day(timed, todo)


def marks(events: list[dict], tasks: list[dict], tz: tzinfo, today: date) -> dict[date, set[str]]:
    """Each day's dots: "event" and/or "task" (open tasks only)."""
    out: dict[date, set[str]] = {}
    for e in events or []:
        start = parse(e.get("start"), tz)
        if start is not None:
            out.setdefault(start.date(), set()).add("event")
    for task in tasks or []:
        if is_done(task):
            continue
        day = task_day(task, tz, today)
        if day is not None:
            out.setdefault(day, set()).add("task")
    return out


def week_start(day: date) -> date:
    return day - timedelta(days=day.weekday())


def week_of(day: date) -> list[date]:
    first = week_start(day)
    return [first + timedelta(days=i) for i in range(7)]


def month_grid(day: date) -> list[date]:
    """The month of ``day`` as whole weeks, Monday first (5 or 6 rows)."""
    first = day.replace(day=1)
    nxt = (first + timedelta(days=32)).replace(day=1)
    start = week_start(first)
    weeks = ((nxt - start).days + 6) // 7
    return [start + timedelta(days=i) for i in range(weeks * 7)]


def add_months(day: date, n: int) -> date:
    month = day.month - 1 + n
    year, month = day.year + month // 12, month % 12 + 1
    last = ((date(year, month, 1) + timedelta(days=32)).replace(day=1) - timedelta(days=1)).day
    return date(year, month, min(day.day, last))


def lanes(items: list[Item]) -> dict[str, tuple[int, int]]:
    """Side-by-side columns for overlapping items: key -> (lane, lanes in its group)."""
    out: dict[str, tuple[int, int]] = {}
    group: list[tuple[Item, int]] = []
    ends: list[datetime] = []
    group_end: datetime | None = None

    def close() -> None:
        for it, lane in group:
            out[it.key] = (lane, len(ends))

    for it in sorted((i for i in items if i.start is not None), key=lambda i: (i.start, -i.minutes)):
        assert it.start is not None and it.end is not None
        if group_end is not None and it.start >= group_end:
            close()
            group, ends, group_end = [], [], None
        lane = next((n for n, e in enumerate(ends) if e <= it.start), None)
        if lane is None:
            lane = len(ends)
            ends.append(it.end)
        else:
            ends[lane] = it.end
        group.append((it, lane))
        group_end = it.end if group_end is None else max(group_end, it.end)
    close()
    return out


def horizon_for(day: date | None, today: date) -> str:
    """The Task list a dated task sits on, as the PC's Today / Week / Month lists read it."""
    if day is None or day <= today:
        return "today"
    return "week" if (day - today).days < 7 else "month"
