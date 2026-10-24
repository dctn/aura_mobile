"""Copy the phone's fixed copy from the demo package into ``src/data/catalog.json``, so the site never hand-copies it.

What it takes: the 18 features (``core/features.py``), the recovery presets (``ui/pages/recovery.py``), the four
modes (``ui/pages/modes.py``) and the line icons (``ui/icons.py``). Run it again whenever those change:

    uv run python export_catalog.py
"""

from __future__ import annotations

import json
from pathlib import Path

from aura_mobile.core.features import FEATURES
from aura_mobile.ui.icons import PATHS
from aura_mobile.ui.pages.modes import MODES
from aura_mobile.ui.pages.recovery import PRESETS

OUT = Path(__file__).resolve().parents[1] / "src" / "data" / "catalog.json"


def catalog() -> dict:
    features = [
        {"id": f.id, "name": f.name, "group": f.group, "accent": f.accent, "shows": f.shows, "does": f.does,
         "action": f.action.value, "actionLabel": f.action_label, "rule": f.rule, "hasAlert": f.has_alert,
         "reason": f.reason, "verb": f.verb, "metric": f.metric, "icon": f.icon}
        for f in FEATURES
    ]
    presets = [
        {"title": title, "word": word, "minutes": minutes, "kind": kind, "accent": accent, "note": note, "icon": icon}
        for title, word, minutes, kind, accent, note, icon in PRESETS
    ]
    modes = [m._asdict() for m in MODES]
    return {"features": features, "presets": presets, "modes": modes, "icons": PATHS}


def main() -> int:
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(catalog(), indent=1, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"Wrote {OUT.name}: {len(FEATURES)} features, {len(PRESETS)} presets, {len(MODES)} modes, {len(PATHS)} icons")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
