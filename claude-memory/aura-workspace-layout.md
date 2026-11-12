---
name: aura-workspace-layout
description: "What lives where under D:\\intellj\\aura (apps, research, company docs, aws_docs dev home with its v1 archive), plus stale pre-move paths and old memory folders."
metadata:
  node_type: memory
  type: project
  originSessionId: 150d304e-8db0-426d-b16d-6e3b043ee8c6
  modified: 2026-10-21T14:59:21.939Z
---

`D:\intellj\aura\` holds:
- `aura_window_app`: the PyQt6 Windows port (old path `D:\intellj\aura_window_app`; old memories in `~/.claude/projects/D--intellj-aura-window-app/memory`).
- `deep_focus_with_music`: 16 Hz AM research, the `focusmusic` generator and `focus_test_app`. Old path `D:\Pycharm\AI projects\deep_focus_with_music`; old memories in `~/.claude/projects/D--Pycharm-AI-projects/memory`. Those memories say: ask before acting, no builds without permission, the bells layer is off limits.
- `aura_IOS_app`: the Swift reference app, read only. See [[ios-app-is-reference-only]].
- `IBT software`: grant PDFs, the patent and architecture maps. Its `development_docs/` is a copy of `deep_focus_with_music/docs/`.
- `aws_docs`: **the home for new AWS development** (since 2026-10-07):
  - its README
  - the architecture page
  - `architecture-src/` (the page generator)
  - the new Mac app figure
  - `v1/`: an archive of the 14–24 Sep docs (01–08, inventory xlsx, atlas, first build spec)
  - `keys/` (never open)
- `internship_maheswara_h`: the internship record. See [[internship-work-record]].
- `aura_mobile` (since 2026-10-21): the mobile side. `demo/` is a phone-sized PyQt6 app for the 18 mobile features against dev us-west-1, with a "Test lab" band simulator. Not a git repo. **Mobile memory lives flat in `aura_mobile/` itself** (`CLAUDE.md` + `MEMORY.md` + one .md per fact; the user asked for it "straight inside aura_mobile/"). Read it before any mobile work.
- `aws_eu-north-1`: a git **worktree** of `aws_docs` on orphan branch `eu-north-1_resource` (unrelated history, never merges into main). A read-only snapshot (15 Oct) of the OLD backend in eu-north-1: `clarus/` CDK stacks Clarus-dev/prod/account (still running), `amplify/` Gen 2 leftovers (mostly deleted), 70 CFN templates, `inventory.json`, `tools/` exporters; `raw/` gitignored. Mirrors the Zoho tab "eu-north-1 server documentation".

**Why:** paths written before the 2026-09-14 move point at old locations, and old memories don't load from the new paths. The aws_docs reorganisation moved every old doc into `v1/`.

**How to apply:** treat pre-move paths as stale. Read the old memory folders when working on those projects. New cloud docs go in `aws_docs/` itself, never in `v1/`. See [[aws-project-memory]].
