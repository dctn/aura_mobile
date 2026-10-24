"""How a computed day's metric reads on a pin: a label and a formatted value. Unknown fields still show, raw.

Absent is ``None`` and reads "—"; a measured zero stays a zero.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date, timedelta
from typing import Any

NO_VALUE = "—"

#: field → (label, unit kind)
KNOWN: dict[str, tuple[str, str]] = {
    "totalSleepMin": ("Sleep", "duration"),
    "sleepDebtMin14": ("Sleep debt (14 d)", "duration"),
    "sleepLatencyMin": ("Time to fall asleep", "minutes"),
    "recoveryScore": ("Recovery", "score"),
    "restingHrBpm": ("Resting HR", "bpm"),
    "restingHrBaselineBpm": ("Resting HR baseline", "bpm"),
    "hrvAboveBaseline": ("HRV above baseline", "bool"),
    "skinTempDeviationC": ("Skin temp vs normal", "celsius"),
    "stressDurationMin": ("High stress", "duration"),
    "weeklyActiveMinutes": ("Active this week", "minutes"),
    "longestSittingBoutMin": ("Longest sitting", "minutes"),
    "vo2max": ("VO2max", "number"),
    "wakeRegularityMin": ("Wake-time swing", "minutes"),
    "circadianAlignmentPct": ("Body-clock alignment", "percent"),
    "spo2DipEvents": ("SpO2 dips", "number"),
    "steps": ("Steps", "number"),
    "burnoutIndexScore": ("Burnout index", "score"),
}


def label_for(field: str) -> str:
    known = KNOWN.get(field)
    if known:
        return known[0]
    # camelCase → words
    out = "".join(" " + c.lower() if c.isupper() else c for c in field).strip()
    return out[:1].upper() + out[1:]


def format_value(field: str, value: Any) -> str:
    if value is None:
        return NO_VALUE
    kind = KNOWN.get(field, ("", "raw"))[1]
    if isinstance(value, bool) or kind == "bool":
        return "Yes" if value else "No"
    if not isinstance(value, int | float):
        return str(value)[:18]
    if kind == "duration":
        minutes = round(value)
        return f"{minutes // 60} h {minutes % 60:02d} m" if minutes >= 60 else f"{minutes} min"
    if kind == "minutes":
        return f"{round(value)} min"
    if kind == "bpm":
        return f"{round(value)} bpm"
    if kind == "celsius":
        return f"{value:+.1f} °C"
    if kind == "percent":
        return f"{round(value)} %"
    if kind == "score":
        return f"{round(value)}"
    return f"{value:g}" if isinstance(value, float) else str(value)


def numeric_fields(metrics: dict) -> list[str]:
    """The fields a person could pin: the scalars, known ones first."""
    keys = [k for k, v in metrics.items() if isinstance(v, int | float | bool) or v is None]
    known = [k for k in KNOWN if k in keys]
    return known + sorted(k for k in keys if k not in KNOWN)


# -- the simple screens' metric cards ---------------------------------------------------------------------------


@dataclass(frozen=True)
class Card:
    """How one metric reads on a card: the label, its column, the unit under the big number, and its look.

    ``accent`` is a colour name in ``ui/tokens.ACCENTS`` and ``icon`` a glyph in ``ui/icons.PATHS``. ``div`` turns
    the raw value into the shown one (minutes → hours)."""

    key: str
    label: str
    field: str
    unit: str
    accent: str
    icon: str
    div: float = 1.0
    digits: int = 0

    def number(self, raw: Any) -> float | None:
        if isinstance(raw, bool) or not isinstance(raw, int | float):
            return None
        return raw / self.div

    def show(self, raw: Any) -> str:
        n = self.number(raw)
        if n is None:
            return NO_VALUE
        return f"{n:.{self.digits}f}" if self.digits else str(round(n))


#: Search's categories, in order, then the extra cards Home shows under Burnout
CARDS: dict[str, Card] = {c.key: c for c in (
    Card("sleep", "Sleep", "totalSleepMin", "h", "cyan", "sleep", 60, 1),
    Card("heart", "Heart", "restingHrBpm", "bpm", "cardiac", "heart"),
    Card("stress", "Stress", "stressScore", "/100", "stress", "bolt"),
    Card("recovery", "Recovery", "recoveryScore", "/100", "recovery", "recover"),
    Card("movement", "Movement", "activeMinutes", "min", "fitness", "walk"),
    Card("breathing", "Breathing", "breathsPerMin", "/min", "mint", "wind", 1, 1),
    Card("burnout", "Burnout", "burnoutIndexScore", "/100", "workout", "flame"),
    Card("body", "Body", "skinTempDeviationC", "°C", "purple", "body", 1, 1),
    Card("avg_sleep", "Avg sleep", "avgSleepBurnoutHrs", "h", "cyan", "sleep", 1, 1),
    Card("late_nights", "Late nights", "lateNightsBurnout", "nights", "purple", "evening"),
    Card("sleep_score", "Sleep score", "sleepScore", "/100", "sleep", "sleep"),
)}
CATEGORIES: tuple[str, ...] = ("sleep", "heart", "stress", "recovery", "movement", "breathing", "burnout", "body")

#: units for a column with no card of its own, by its KNOWN kind
_UNITS = {"duration": "min", "minutes": "min", "bpm": "bpm", "celsius": "°C", "percent": "%", "score": "/100"}


def card_for(field: str) -> Card:
    """The card for a metric column: a known one, else one built from the column's label."""
    for c in CARDS.values():
        if c.field == field:
            return c
    kind = KNOWN.get(field, ("", "raw"))[1]
    return Card(field, label_for(field), field, _UNITS.get(kind, ""), "blue", "chart")


def series(days: list[dict], field: str, today: date, count: int = 14) -> list[float | None]:
    """``count`` values of one column, oldest first, ending today; ``None`` where a day has no value."""
    by_date = {str(d.get("date"))[:10]: (d.get("metrics") or {}).get(field) for d in days}
    out: list[float | None] = []
    for i in range(count - 1, -1, -1):
        v = by_date.get((today - timedelta(days=i)).isoformat())
        out.append(None if isinstance(v, bool) or not isinstance(v, int | float) else float(v))
    return out


def newest(values: list[float | None]) -> float | None:
    """The last value that isn't missing."""
    return next((v for v in reversed(values) if v is not None), None)


def average(values: list[float | None]) -> float | None:
    got = [v for v in values if v is not None]
    return sum(got) / len(got) if got else None
