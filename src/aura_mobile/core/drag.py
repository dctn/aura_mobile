"""Drag and drop on the calendar: where a dragged block lands (15-minute steps), how long a stretched one gets, and
the task or event changes a drop writes. The mouse handling is in ``ui/caldrag.py``; this is the plain maths, the
same as the website's ``calendar/drag.ts``.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date, datetime, time, tzinfo
from typing import Any

from aura_mobile.core.agenda import Item, horizon_for

#: a drop lands on a quarter hour
SNAP = 15
#: a stretched block is never shorter than this
MIN_LENGTH = 15
DAY = 24 * 60


def snap(minute: float) -> int:
    return round(minute / SNAP) * SNAP


def _clamp(n: int, lo: int, hi: int) -> int:
    return max(lo, min(hi, n))


def drop_start(minute: float, grab: float, minutes: int) -> int:
    """Where a moved block starts: the minute under the pointer less where it was grabbed, snapped, inside the
    day."""
    return _clamp(snap(minute - grab), 0, max(0, DAY - minutes))


def stretch_to(minute: float, start: int) -> int:
    """A block stretched by its bottom edge to ``minute``: from its start to there, snapped, at least
    :data:`MIN_LENGTH`."""
    return _clamp(snap(minute - start), MIN_LENGTH, DAY - start)


@dataclass(frozen=True)
class Target:
    """Where a drag would put an item: a day, a start in minutes after midnight (None keeps a to-do without a
    time) and a length."""

    day: date
    start: int | None
    minutes: int


def start_minute(item: Item) -> int | None:
    return item.start.hour * 60 + item.start.minute if item.start is not None else None


def is_locked(task: dict[str, Any]) -> bool:
    """A task placed by a calendar block keeps its time (the block owns it)."""
    return bool(task.get("blockId") or task.get("scheduledAt"))


def can_drag(item: Item) -> bool:
    """Done tasks stay where they were ticked, and a block-placed task stays put."""
    return not item.done and not (item.kind == "task" and is_locked(item.raw))


def same(item: Item, day: date, target: Target) -> bool:
    """Whether the drop changes nothing (``day`` is the day the item shows on)."""
    start = start_minute(item)
    return day == target.day and start == target.start and (target.start is None or item.minutes == target.minutes)


def _at(minute: int) -> time:
    return time(minute // 60, minute % 60)


def event_start(target: Target, tz: tzinfo | None) -> datetime:
    return datetime.combine(target.day, _at(target.start if target.start is not None else 9 * 60), tzinfo=tz)


def task_changes(target: Target, tz: tzinfo | None, today: date) -> dict[str, Any]:
    """A task's new day, time, length and list, written the way the editor writes them."""
    at = _at(target.start) if target.start is not None else None
    changes: dict[str, Any] = {
        "dueAt": datetime.combine(target.day, at or time(0, 0), tzinfo=tz).isoformat(),
        "hasTime": at is not None,
        "plannedFor": None,
        "horizon": horizon_for(target.day, today),
    }
    if target.start is not None:
        changes["estimateMinutes"] = target.minutes
    return changes


def end_minute(target: Target) -> int:
    return min((target.start or 0) + target.minutes, DAY)


def target_text(target: Target) -> str:
    """"Sun 25 Oct · 10:15–11:00", or "Sun 25 Oct" for a to-do without a time."""
    day = f"{target.day:%a} {target.day.day} {target.day:%b}"
    if target.start is None:
        return day
    end = end_minute(target)
    return f"{day} · {target.start // 60:02d}:{target.start % 60:02d}–{end // 60:02d}:{end % 60:02d}"
