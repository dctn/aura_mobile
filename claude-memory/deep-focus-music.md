---
name: deep-focus-music
description: "deep_focus_with_music — 16 Hz AM research, focusmusic generator 6.5.1, A/B test app, status and how to run"
metadata:
  node_type: memory
  type: project
  originSessionId: 2352f890-89f8-4a5a-874e-47a97a8fb600
  modified: 2026-10-21T15:00:14.333Z
---

`D:\intellj\aura\deep_focus_with_music` applies 16 Hz amplitude modulation to music, based on Woods et al. 2024 (the paper is in `docs/`). It is on branch `deep_focus_with_music` of the **aura_window_app** remote, an orphan-style branch. Last commit 26 Sep 2026. There is one uncommitted change: `generate_focus_music.py` DURATION changed from 10 to 1200. There is no CLAUDE.md.

- `src/AM method/`: the original post-process.
- `src/song_generation_method/focusmusic/` (v6.5.1): 9 genres, per-stem modulation, movements, depth wave/contour, mastering to −16 LUFS. Run `uv run python -m focusmusic.cli -g lofi_piano -d 1200 -s 1203 -o <dir> --no-stems`.
- `src/nature_am/`: nature scenes and the SART experiment.
- `focus_test_app/`: PyQt6 A/B tester v7, built with `build.ps1` into `dist/AuraClarusABTester*.exe`.
- **Status (WORK_LOG, 19 Sep):** 169 generator tests pass. Open items: choose wave or contour by listening, rebuild the exe, run the SART study.
- `FOCUS_MUSIC_STREAMING_GUIDE.md`: 20-minute tracks, delivered as `<genre>-<seed>.wav` plus JSON.

**How to apply:** old memory rules for this folder still apply: ask before acting, no builds without permission, the bells layer is off limits. See [[aura-workspace-layout]].
