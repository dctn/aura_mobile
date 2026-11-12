---
name: aura-window-app
description: "aura_window_app — the PyQt6 Windows port: backend it uses (old eu-north-1 backend-v2), layout, commands, house rules, git state"
metadata:
  node_type: memory
  type: project
  originSessionId: 2352f890-89f8-4a5a-874e-47a97a8fb600
  modified: 2026-10-21T15:00:01.622Z
---

`D:\intellj\aura\aura_window_app` is a PyQt6 Windows port of the Aura Clarus Mac app. Package `aura-clarus-desktop` 1.0.0, Python 3.12, repo `dctn/aura_window_app`, branch `main`, last commit 5 Oct 2026 (mini window work).

- **Backend:** the OLD "backend-v2" CDK in **eu-north-1**, not the new us-west-1 microservices. That backend has a REST `/v1/...` API, one table, `/v1/sync` and a live WebSocket. Stage config `src/aura/resources/clarus/clarus-{dev,prod}.json` is copied verbatim from the Swift repo; never edit it by hand. The focus-music content API is dev only and was built in the console. Stage: `AURA_CLARUS_STAGE`; otherwise a frozen build uses prod and a source run uses dev.
- **Layout:** `src/aura/{core,services,ui}`. `core` has no Qt (only `app_model.py` may use QtCore). `services/` holds `clarus_api.py`, `clarus_auth.py` (Cognito EMAIL_OTP over raw JSON), `secrets.py` (keyring), blockers, Google, Slack, Vosk speech and music. `ui/tokens.py` is ported from Theme.swift.
- **Commands:** `uv run aura`; tests `uv run pytest -n 8` (about 4,400 tests, about 2 min, MockTransport only); `uv run ruff check src tests`; build with PyInstaller `aura.spec` plus Inno Setup `installer.iss`.
- **House rules (CLAUDE.md, 134 KB):** connect signals to bound methods, never lambdas. One QSS writer, and one `setStyleSheet` writer per widget. No box-shadows. Motion goes through `core/motion.py`. Name the Swift symbol a value came from. Secrets only go through `secrets.py`. Sync through the Swift repo's `docs/aura-port.md`. Screenshots in `mistakes/` are bug reports that need a regression test. Merge to main only when the user asks. Never deploy the backend from this repo.
- `.env` and `google_app_credentials.json` are here; never open them.

**How to apply:** reuse its patterns, not its code: the mobile demo copied its auth, http and Qt rules. Remember it still points at eu-north-1, so moving it to us-west-1 would be a separate job. See [[aws-eu-north-1-snapshot]] and [[ios-app-is-reference-only]].
