"""How a computed day's metric reads on a pin: a label and a formatted value. Unknown fields still show, raw.

Absent is ``None`` and reads "—"; a measured zero stays a zero.
"""

from __future__ import annotations

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
