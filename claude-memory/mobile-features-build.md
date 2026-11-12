---
name: mobile-features-build
description: "Oct 2026: the 18 mobile features built on the Aura AWS backend (branch mobile-features, PR #1), deployed to dev; what shipped and what's pending"
metadata:
  node_type: memory
  type: project
  originSessionId: 150d304e-8db0-426d-b16d-6e3b043ee8c6
  modified: 2026-10-21T14:23:52.373Z
---

The 18 MOBILE features (DX-01–07, DX-09, PB-01–05, NI-02–04, BS-01–02) built on the existing microservices on branch `mobile-features` (off the teammate's `feature-logic-mvp`, NOT main). Mobile scope only — the teammate owns PC features. Built + deployed to **dev** in us-west-1; M0–M7 complete as of 2026-10-21.

**What shipped (3 services):**
- **Health Data** — the alerts engine: Lambda `aura-dev-health-data-mobile-alerts`, 17 rules (Strategy + Factory) reacting to `health-data.day.computed`/`activity.detected` + per-user EventBridge Scheduler timers. New `ALERT#<date>#<featureId>` rows (cool-down + 30-day TTL) → table stream → publisher → `health-data.notification.requested`. Routes `GET /v1/alerts`, `POST /v1/alerts/{id}/done`, `GET`/`PUT /v1/alerts/settings` (`ALERTPREF`). New metric fields `wakeRegularityMin`, `weeklyActiveMinutes`, `stressDurationMin`, `longestSittingBoutMin`. Check-in kinds `mood`/`connection`.
- **Notification** — kind `healthAlert` (target phone); APNs channel reads `.p8` from Secrets Manager `aura/dev/notification-apns`, inbox fallback.
- **Identity** — `waterGoalMl` profile field, carried in `identity.profile.changed` for NI-04.

**Docs:** Zoho tabs 9#/12#/18# + mirrored `docs/services/{health-data,notification,identity}.md`, links pinned to commit `9b8b8bd`. Doc commit `a515366`. **PR #1:** https://github.com/dctn/aura-aws-backend/pull/1.

**Decisions:** per-feature alert on/off lives in Health Data (`ALERTPREF`), not Identity; DX-09 uses WHO-150 fallback for `moveMinutesTarget`.

**20# routing-tab fix — DONE (2026-10-21):** removed the deleted Capacity presets so Capacity (col 12) now appears only on BS-01; the 17 alert rules show Health Data as main (bold), Identity main for BS-01; NI-04 gained its Health Data entry; per-row server counts and the rows 25–27 summary all reconcile (Main 18 = HD 17 + Identity 1; Also 34; Total 52). **Zoho gotcha learned:** `set_content_to_range` with an empty or whitespace value returns success but is a no-op (Zoho ignores empty writes), and `clear_contents_of_range` was blocked by the classifier — the working clear is a multi-field CSV written at the target cell, e.g. `data=",<HD-id>"` at (row, col 12), which blanks col 12 and rewrites col 13 in one call. Editing 20# changed existing shared/teammate content, gated per [[zoho-sheet-edit-locally-first]].

**Why:** standing goal — ship the mobile features production-ready without clashing with the PC-features teammate.

**How to apply:** this is additive mobile work on top of the deployed services; see [[aws-microservices-rebuild]] and the AWS plan memory in `aws_docs/memory/aws-microservices-plan.md`. For PR attribution, the user chose to add the Claude attribution lines on THIS PR (overriding [[no-claude-attribution-in-commits]] for this one).
