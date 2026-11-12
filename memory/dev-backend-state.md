---
name: dev-backend-state
description: What works on dev for the mobile app (22 Oct 2026: all routes back), the 21 Oct Identity loss, consent copy lag
metadata:
  type: project
---

**24 Oct 2026:** re-verified — `get-routes` on `z4gym50rj6` shows the full set (consent, exports, lists, avatar) and
`live_check.py` passes **30/30** again (samples/checkins/logs all accepted, export preparing). Dev is clear for demo work.

**22 Oct 2026:** Identity's mobile routes are deployed again (`get-routes` shows consent, exports, lists, avatar);
`live_check.py` passes 30/30. Health Data checks consent against **its own copy** (`health-data/src/service/consent.ts`),
updated by the `identity.consent.changed` event a few seconds after a PUT — a write right after a grant can still get
403 `consent_required`. Replayed on dev 22 Oct (off → refused → app client granted + resent, accepted after 2.4 s).
Turning consent off cancels the person's booked assembles and drops queued packets; it deletes nothing.

History — **21 Oct 2026:** dev Identity API (`z4gym50rj6`) has only 6 routes: `GET /v1/config`, `GET /v1/health`, `GET /v1/me`,
`PUT /v1/me/profile`, `PUT /v1/me/settings`, `DELETE /v1/me`. The `mobile-features` code also has consent
(`GET/PUT /v1/me/consent`), exports, lists, avatar, and `healthGoals` on the profile.

**Cause:** at 07:04 IST a malicious commit `ded90c1` ("Add Github Actions Security workflow", authored as IBT Aura)
was pushed to `main` of dctn/aura-aws-backend. That push also ran CI `deploy-dev` (01:35Z), redeploying every stack
from `main`, which doesn't have `feature-logic-mvp`/`mobile-features`. Health Data (deployed 20 Oct) still has the
mobile alerts. See the user-level memory `aws-docs-repo-compromise` and `aws_docs/memory/repo-security-incident.md`.

Main holds only S0 and S1, and no feature branch has been merged into it. Dev had been deployed by hand from
`feature-logic-mvp`/`mobile-features`. So the main deploy also **deleted the ios/android Cognito clients** and
**emptied Identity's `DATA_SERVICES`**: the deletion saga no longer purges the data services. Don't use Delete
account in the demo until Identity is redeployed, or it leaves orphan data behind.

**Effect in the app:**
- Works: sign-in, me, tasks, calendar, sessions, presets, alerts list/done, alert switches, inbox, metrics reads.
- Fails: consent (404 Not Found) → Health Data refuses `POST /v1/samples`, `/v1/checkins`, `/v1/logs` with
  404 "The identity service refused the request." (the app now shows a consent message instead);
  profile goals (400 contract); export (404). So the Test lab and Reconnect can't fire alerts.

**Lambda concurrency (checked 21 Oct, `aws lambda get-account-settings`):** the account's limit in us-west-1 is
**10** concurrent executions. API Gateway stages allow burst 100 / rate 50, so Lambda is the bottleneck: ~12 parallel
reads at startup gave 503 "throttled". The app caps itself at 6 calls in flight. Raising it is a Service Quotas
request (the user's call). The Test lab also stops at the first refused write and frees the run's dates.

**Fix path:** first remove `ded90c1`'s workflow from `main` (it runs on a push to *any* branch) and secure the
ibtaura GitHub account. Then do the planned merges `feature-logic-mvp` → `main`, then `mobile-features` → `main`
(user approved the auto-deploy earlier). Merging before the removal would carry the workflow along and fire it.
Alternatively, a targeted hand deploy of Identity from the feature branch, with fresh written approval. Afterwards: grant consent, re-run `scripts/live_check.py`, then `--lab`.

**How to apply:** before debugging a 404 in the app, list deployed routes read-only:
`aws apigatewayv2 get-routes --api-id <id> --region us-west-1`. Update this file when dev changes.
