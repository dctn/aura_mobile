"""DX-01 and DX-02 on the phone: a bedtime reminder 30 minutes before bed and a wake alarm, from the health goals.

Both are local, like a phone's own alarm clock: no server call. Each fires once a day, within a minute of its time,
and only while the app is open (the demo has no background service).
"""

from __future__ import annotations

from collections.abc import Callable
from datetime import date, datetime

from PyQt6.QtCore import QObject, QTimer

CHECK_MS = 30_000
REMIND_BEFORE_BED_MIN = 30


def _minutes(value: object) -> int | None:
    try:
        hh, mm = (int(x) for x in str(value).split(":")[:2])
    except ValueError:
        return None
    return hh * 60 + mm if 0 <= hh < 24 and 0 <= mm < 60 else None


def due(now: datetime, goals: dict, fired: set[tuple[str, date]]) -> list[tuple[str, str]]:
    """The alarms due at ``now`` that have not fired today: (key, message). Adds them to ``fired``."""
    out: list[tuple[str, str]] = []
    at = now.hour * 60 + now.minute
    bed = _minutes(goals.get("bedtime"))
    wake = _minutes(goals.get("wakeTime"))
    plan = []
    if bed is not None:
        plan.append(("bedtime", (bed - REMIND_BEFORE_BED_MIN) % 1440,
                     f"Bed at {goals['bedtime']}: start winding down. Dim the lights, screens away."))
    if wake is not None:
        plan.append(("wake", wake,
                     f"Good morning. The same wake time every day ({goals['wakeTime']}) keeps your clock steady."))
    for key, minute, text in plan:
        if (key, now.date()) not in fired and 0 <= (at - minute) % 1440 < 2:
            fired.add((key, now.date()))
            out.append((key, text))
    return out


class Alarms(QObject):
    """Checks every 30 s; ``ring(message)`` shows it (the shell: toast, beep and a taskbar flash)."""

    def __init__(self, now: Callable[[], datetime], goals: Callable[[], dict], ring: Callable[[str], None],
                 parent: QObject | None = None) -> None:
        super().__init__(parent)
        self._now = now
        self._goals = goals
        self._ring = ring
        self._fired: set[tuple[str, date]] = set()
        self._timer = QTimer(self)
        self._timer.setInterval(CHECK_MS)
        self._timer.timeout.connect(self.check)

    def start(self) -> None:
        self._timer.start()

    def stop(self) -> None:
        self._timer.stop()

    def check(self) -> None:
        try:
            now, goals = self._now(), self._goals()
        except Exception:  # signed out mid-check, or no time zone yet
            return
        for _key, text in due(now, goals or {}, self._fired):
            self._ring(text)
