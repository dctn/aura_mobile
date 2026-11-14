# Prompt for the AWS Claude session: Health Connect data

Paste this into a Claude session working in `aws_docs`. The Android app codes against the names below.

```
Work in aws_docs (repo dctn/aura-aws-backend), dev us-west-1 only, following aws_docs/CLAUDE.md and its memory.
The Android app (Kotlin, aura_mobile/aura_android) will send Google Health Connect data. Extend the existing
aura-apple-health-data service; do not create a new service.

Branch: off feature/apple-health-data, as feature/health-connect-data.

Routes, with the same auth, consent check, account lock, error format and limits as the Apple routes:
- PUT /v1/health-connect/days/{date}
  body {timeZone, recordedAt, sources[], metrics{...}, workouts[...]}; a later recordedAt wins; reply {day, written}
- POST /v1/health-connect/days/batch (1-31 days)
- GET /v1/health-connect/days?from&to
- GET /v1/health-connect/workouts?from&to

Metrics (all nullable; null means not available, never 0):
- Same names and limits as Apple: stepCount, activeKcal, exerciseMinutes, restingHrBpm, breathsPerMin, spo2Pct,
  mindfulMinutes, weightKg, sleepDeepMin, sleepRemMin, sleepAwakeMin.
- New: distanceKm (all distance, <= 500); hrvRmssdMs (<= 500; Health Connect measures RMSSD, not SDNN);
  skinTempDeltaC (-10 to 10); sleepLightMin (Health Connect's LIGHT stage, the counterpart of Apple's core sleep).
- No stand hours.

Workouts: {id, type, startedAt, endedAt, minutes, activeKcal, distanceKm, avgHrBpm, source}
- id is the Health Connect record id; type is the Health Connect exercise type name in lower snake case;
  at most 50 a day, replaced as a set.

Storage: the same table, with the source kept apart (e.g. HC#DAY#<date> and HC#WORKOUT#<date>#<id>, or a source
attribute). Pick the cleaner fit for the hexagonal layers.

Event: apple-health-data.day.changed (or a sibling event) carries source: "healthConnect". The Health Data consumer
copies the metrics into METRIC#<date>:
- mark metricsSource: "healthConnect";
- never overwrite a band-measured day;
- don't put RMSSD in an SDNN column: map it only if Health Data's HRV column is generic, otherwise keep it in this
  service only;
- map sleepLightMin the way the Apple consumer maps core sleep.

Contracts, tests and docs: zod schemas in packages/contracts/src/apple-health-data.ts; unit and integration tests,
plus an e2e page test if the Apple routes have one; update docs/services/apple-health-data.md; add a build-log row;
draft the Zoho rows locally first and show me before writing.

Before deploying: npm run lint, npm test, cdk diff for the changed stacks (aura-dev-apple-health-data, plus
aura-dev-health-data if the consumer changes). Show me the diff; deploy only after I approve.

Git: commit locally as dctn, no Co-Authored-By lines. Do NOT push to GitHub until I confirm the account is locked
down after the 10-12 Nov attack. Never merge or run any "ci:" commit by the attacker identity.

When done, report: the final route paths and body field names (list any change from the above), the dev API URL,
and an example request and reply for each route.
```
