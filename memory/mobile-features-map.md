---
name: mobile-features-map
description: The 18 mobile features — owning service, the app's action, and whether the lab scenario is direct or derived
metadata:
  type: project
---

Source of truth: Zoho workbook "CLARUS | BACKED DETAILS", tab "mobile features" (routing in tab 20#). Backend built
on branch `mobile-features` of `aws_docs` (PR #1), see `aws_docs/memory/aws-microservices-plan.md`.

Health Data owns the 17 alert rules (Lambda `aura-dev-health-data-mobile-alerts`, rows `ALERT#<date>#<featureId>`,
routes `GET /v1/alerts`, `POST /v1/alerts/{id}/done`, `GET/PUT /v1/alerts/settings` with `{off: [...]}`).
Alerts carry `done` (bool), not `status`. Notification delivers kind `healthAlert` (APNs empty on dev → inbox only).
Identity owns BS-01 (profile, `healthGoals` incl. `waterGoalMl`, consent, export, delete).

| Feature | Lab | App action (then `POST /v1/alerts/{id}/done`) |
|---|---|---|
| DX-01 sleep-debt payback | derived | bedtime goal 30 min earlier (profile); local reminder 30 min before bed |
| DX-02 anchor wake time | derived | set wake-time goal; local wake alarm |
| DX-03 better-nights plan | derived | 10-min wind-down + to-do lines → tasks; ISI sleep check (insomnia check-in) once DX-03 spans 3 ISO weeks |
| DX-04 short-night nap | **direct** | 10-min nap Session timer |
| DX-05 easy-day call | derived | move today's/tomorrow's hard training (title words) to tomorrow, then recovery break |
| DX-06 getting-ill warning | derived | cancel ticked hard training (DELETE event) + optional bedtime −30 min |
| DX-07 stress reset | derived | 5-min breathing, then stressDrop from `GET /v1/trends/sessions/{id}` |
| DX-09 weekly movement | **direct** | two walk calendar events Sat/Sun; Home bar weeklyActiveMinutes/150 |
| PB-01 body-clock reset | derived | 3 mornings light (wake+30, 07:30–09:30) + 21:00 Dim screens events |
| PB-02 burnout warning | derived | recovery-day event + mood check |
| PB-03 sitting breaker | **direct** | 3-min move timer |
| PB-04 fitness keeper | derived | three 18:00 events; Search → Strength log (`strength {minutes}`) |
| PB-05 health-check flag | derived | 'Book a doctor visit' task (week) |
| NI-02 mood check + help | **direct** | mood check-in; HelpDialog (Tele-MANAS, iCall, 112) if ≤2 or week low |
| NI-03 connection nudge | **direct** | call task and/or meal event, then connection check-in |
| NI-04 hydration | **direct** | log a glass (fires ~2 h after timer); glasses + ml on Search |
| BS-01 identity & data | — | Profile: body fields + goals as two PUTs; Home 'Finish your profile' |
| BS-02 band sync | **direct** | Reconnect: backfill since last sync (30–720 min, prefs `lastSync:<sub>`); pair via settings `pairedBandId` |

Checked against the team checklist ("Mobile features - cognitive maintenance.md", column "What they can do") on
21 Oct; the gaps found then were built the same day. The richer flows live in `demo/src/aura_mobile/ui/actions.py`
(steps) and `ui/sheets.py` (dialogs); local alarms in `ui/alarms.py`.

**How to apply:** direct rules cross a raw threshold and fire reliably; derived ones depend on history and the deployed pipeline. Test direct first (DX-04, PB-03, DX-09). See [[test-lab-rules]].
