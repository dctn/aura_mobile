"""This phone's own preferences (pinned metrics, appearance), kept next to the lab state. Not synced."""

from __future__ import annotations

import json
from typing import Any

from aura_mobile.services.sim.runner import state_dir

DEFAULT_PINS = ["totalSleepMin", "recoveryScore", "restingHrBpm", "stressDurationMin"]


def _path():
    return state_dir() / "prefs.json"


def load() -> dict[str, Any]:
    try:
        data = json.loads(_path().read_text(encoding="utf-8"))
        return data if isinstance(data, dict) else {}
    except (OSError, ValueError):
        return {}


def save(changes: dict[str, Any]) -> None:
    data = load()
    data.update(changes)
    path = _path()
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp = path.with_suffix(".tmp")
        tmp.write_text(json.dumps(data, indent=1), encoding="utf-8")
        tmp.replace(path)
    except OSError:
        pass


def pins() -> list[str]:
    value = load().get("pins")
    return [str(v) for v in value] if isinstance(value, list) else list(DEFAULT_PINS)
