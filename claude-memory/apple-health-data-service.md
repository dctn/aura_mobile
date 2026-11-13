---
name: apple-health-data-service
description: 11th backend service aura-apple-health-data stores the iPhone's Apple Health daily totals and workouts; live on dev 2026-11-13; MVP uses Apple Health only, band alone once it returns
metadata:
  type: project
---

On 2026-11-13 the user asked for a new AWS service named `aura-apple-health-data`. It is built in `aws_docs/services/apple-health-data/` and deployed to dev from this PC: API `https://3g6z31d0r6.execute-api.us-west-1.amazonaws.com`, table `aura-dev-apple-health-data`.

- **Stores:** daily totals and workouts only, no raw samples. Routes: `PUT /v1/apple-health/days/{date}`, a batch of up to 31 days, `GET` days and workouts. Events: `apple-health-data.day.changed`.
- **Health Data:** copies each day into `METRIC#<date>`, so the existing reads, the scores and every app show it. Mindful minutes, weight, wrist temperature and walking distance stay only in the new service, because Health Data has no columns for them.
- **Source rule (user):** Apple Health only for the MVP; once the band is back, use only band data. Health Data never overwrites a band-computed day.
- **Still to do:**
  - **iOS app:** read these HealthKit types and send them (separate mobile work); background sync needs Apple Developer signing.
  - **Zoho:** "Apple Health Data server" tab plus routing rows, drafted locally first.
  - **Tests:** the new stack tests weren't run to the end on this PC.
  - **Git:** local branches `feature/capacity-fixes` and then `feature/apple-health-data`, not pushed, because of the GitHub attack.

**Why:** the MVP launches without the band, so Apple Health is the health source for every device.

**How to apply:** extend this service for more Apple Health data; never make Health Data prefer Apple Health over band data. See [[github-attack-nov-2026]] and [[this-pc-paths]].
