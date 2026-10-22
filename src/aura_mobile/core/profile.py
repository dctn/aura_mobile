"""BS-01: what a complete profile needs. Health Data uses the body details for its calculations (calories, VO2max,
heart-rate zones), so Home asks for the missing ones."""

from __future__ import annotations

from typing import Any

#: field on ``GET /v1/me`` → what Home calls it
NEEDED: dict[str, str] = {
    "displayName": "name",
    "birthDate": "birth date",
    "sex": "sex",
    "heightCm": "height",
    "weightKg": "weight",
}

#: the band paired to this account, kept in ``settings`` (PUT /v1/me/settings)
PAIRED_BAND = "pairedBandId"

SEX = ("notSet", "female", "male", "other")


def missing(me: Any) -> list[str]:
    """The profile fields still unset, in the order Home lists them. ``sex: notSet`` counts as set (a choice)."""
    if not isinstance(me, dict):
        return []
    return [name for field, name in NEEDED.items() if me.get(field) in (None, "")]


def paired_band(me: Any) -> str | None:
    settings = me.get("settings") if isinstance(me, dict) else None
    value = settings.get(PAIRED_BAND) if isinstance(settings, dict) else None
    return str(value) if value else None
