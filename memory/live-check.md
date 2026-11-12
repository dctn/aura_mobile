---
name: live-check
description: demo/scripts/live_check.py — the end-to-end route check against dev, what it writes, and the last result
metadata:
  type: project
---

`uv run python scripts/live_check.py [--lab] [--trace]` from `demo/`. It restores the session saved by the app
(sign in once in the app first), calls every route the app uses, and prints ok/FAIL per route.

It writes ordinary test data to the signed-in dev account: turns health data on if off (as the app does), a check-in, 250 ml water, a task (then completed), one
calendar event tomorrow 07:00, a 1-minute timer session (pause/resume/stop), 30 min of samples, an export.
`--lab` runs DX-04 (one 5 h 30 m night on a past unused date), waits up to 8 min for the alert, then marks it done.
It never deletes the account.

**Last run, 22 Oct 2026:** 30 passed, 0 failed. (The run before it: checkins/logs 403 `consent_required` — the
saved session had never granted consent; fixed by auto-grant + waits, see [[dev-backend-state]].) `--lab` not run
yet.

**How to apply:** run it after any backend deploy before testing by hand; update the last-run line here.
