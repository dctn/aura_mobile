---
name: test-lab-rules
description: Constraints the Test lab works within — past unused dates, compute cap, alert-per-day limit, timings, no push
metadata:
  type: project
---

- Band data goes to `POST /v1/samples`, one packet a minute, in batches. Needs health-data consent on the account.
- Health Data computes a day itself about 2 min after its last packet. **Compute now** (`POST /v1/metrics/daily/{date}/compute`)
  is capped at **5 per hour** per user; the lab counts them and refuses a 6th.
- Each feature raises at most one alert per day and a computed day keeps its result, so the lab always uses **past,
  unused** dates (tracked in `%APPDATA%\AuraMobileDemo\lab-<sub>.json`). Past ~35 days back, runs blur into the rules'
  30-day history; a clean slate means deleting and re-creating the account. **Reset lab** only forgets used dates.
- The lab watches `GET /v1/alerts` every 20 s for 12 min plus the scenario's timer wait. NI-04 fires ~2 h later.
- One run at a time. BS-02 always uses Compute now.
- Push isn't set up on dev (APNs secret empty): alerts show in the inbox and on Recovery; the app polls every 60 s.

**How to apply:** when a lab row times out, first check consent and [[dev-backend-state]], then the day's metrics status, before suspecting the rule.

**22 Oct 2026 fixes (first real lab runs on dev):**
- *Ghost runs:* on 21 Oct the lab kept 18 runs the server refused (0 packets; consent missing). They held 134 of
  the last 150 days, so DX-03 showed "No unused dates left" and DX-01/DX-02 went ~145 days back. `LabState.load` now
  drops runs that sent nothing (`LabRun.is_ghost`; new runs carry `accepted`) and frees their dates; a run refused
  or stopped before its first write is forgotten at once. 155 → 21 used dates.
- *DX-02:* the rule reads `wakeRegularityMin` = std dev of wake times over the **14 nights before** the Monday
  (`pipeline/night.py`), and uses it ahead of `bedtimeDeviationMin`. The old 7-night scenario gave 111 (< 120) on
  dev. Now 14 alternating nights (wake 03:45 / 10:45) → ~218.
- DX-01 passed on dev (alert on its target day, 31 May). That far back only worked by luck: see the 35-day expiry below.
- The lab row's "Last run" shows when it ran and which day its data was for (it used to show only the data day).

**22 Oct 2026, second round (user: "it show Looking only, where is the output"):**
- *Rows said only "Looking…":* while a run was watched, a "no alert yet" answer was dropped. Now
  `LabRunner.check(run, rule)` returns `LabCheck` (alert, or whether AWS computed the target day + the rule's metric
  read from the rule text, e.g. `sleepLatencyMin = 0`), and every look updates the row (`lab.describe_check`).
- *DX-03 never fires with HR 64 latency:* Health Data counts latency only for still minutes at 1.12–1.25× the night's
  floor (10th percentile HR, `pipeline/sleep.py`). 64 vs 58 sleep HR read as sleep (dev: 0/0/26/25/0).
  `band.LATENCY_HR_RATIO = 1.16` → 38–40 min in the real pipeline.
- *Runs sharing a day file:* a night starts the evening before its date, and a day that's already final takes new
  packets only in a **late assemble 30 min later** (`ingest.ts` LATE_MINUTES). DX-03 (17–21 Oct) made 16 Oct final
  3 min before DX-04 sent that night. `Scenario.footprint` (dates + the evening before) is now what a run reserves.
- *Proving a scenario offline:* the backend's own Python pipeline runs on lab packets — put
  `aws_docs/services/health-data/src` on `sys.path`, `context.build(day, prev_day, {}, {})` then `sleep.compute(ctx)`;
  run with `uv run --no-project --with numpy --with scipy --with tzdata python -B` (no bytecode into aws_docs).
  It reproduced dev's numbers exactly. Read-only debugging on dev: `aws logs filter-log-events` on
  `/aws/lambda/aura-dev-health-data-{samples-api,ingest,assemble}` ("day assembled … packets" shows what a day held).
- *A night-counting rule shows every night:* for a rule like "sleepLatencyMin > 30 on 4+ nights" (`runner.night_count`)
  `check(run, rule, days)` also reads the metric for each night via `metrics_range(..., fields=<metric>)`; the row
  says e.g. "by night 0, 0, 26, 25, 37. 1 of 5 nights over 30; the rule needs 4".
- *Seeing a late assemble on dev (read-only):* `aws scheduler get-schedule --group-name aura-dev-health-data
  --name aura-dev-asm-<hash>-<date>-late` gives its `at(...)` UTC time; DX-04's 16 Oct one was 16:05:57Z.
- Runs sent before the app was restarted keep the old code (the 21:06 DX-03 re-run on 22 Oct still used HR 64):
  close and reopen the app after a lab fix.
- DX-04 passed on dev 22 Oct: the 16 Oct late assemble ran 16:06:42Z → totalSleepMin 330, alert "Short night - a nap
  will help" on 16 Oct. So the backend path works end to end once a night lands in an unshared day file.

**22 Oct 2026, third round (DX-02 = 0, DX-03 = 0, 0, 39, 40, 39):**
- *Band minutes expire 35 days after their own time* (`aws_docs` health-data `data-access/keys.ts` SAMPLE_DAYS, TTL =
  packet t + 35 d). A minute older than that is expired on arrival and DynamoDB's TTL sweep can delete it before the
  assemble: 16 Sep assembled 610/665 and 17 Sep 508/665 packets (first hours missing), so DX-03's first two nights
  read 0. The lab now writes only into the last `WINDOW_DAYS` = 33 days (`runner.NO_ROOM` when full). One account
  holds a few scenarios at a time (all 18 need ~140 days): wait for new days or sign up a fresh account.
  DX-01's 31 May pass was luck (the sweep was slow).
- *DX-02's 03:00 nights found no sleep:* the main sleep must be 10% under the median HR of still waking minutes
  10:00–22:00 (`sleep.py` `_waking_hr`, WAKING_DIP_RATIO); the lab day was only ~10 h long, so the next night's 20:00
  bedtime was most of that window. `DayShape.day_minutes` (quiet waking segment, HR 72, 6 steps/min) = 390 for DX-02;
  offline every night is found and wakeRegularityMin = 218.
- Harness for a whole scenario with carried state: `C:\Users\PC\.claude\jobs\2352f890\tmp\dx02_sim.py <FID> <target>`
  (temporary; the recipe: group packets by local date, `context.build(day, prev, {}, {"sleep": state})`,
  `sleep.compute`, then `night.wake_regularity_min({"sleep": state_before}, date)`).

## 23 Oct: all features at once
- `ALL_AT_ONCE` (scenarios.py, feature_id "ALL"): one 26-day month ending on a Monday covering 16 alerts. Phases:
  days 0-11 swinging 20:00/03:00 beds (DX-02, PB-01); then sedentary with resting HR creeping up (DX-09, PB-04);
  latency nights 12-16 (DX-03); desk bout day 13 (PB-03); stress day 15 (DX-07); fever-like day 17 (DX-06/05);
  SpO2 dips from day 17; burnout week 19-25 (short late nights, HR up, low HRV, stress, breathing pauses: PB-02,
  DX-01, DX-04, PB-05); three low moods (NI-02); 1st/15th always inside (NI-03); hydration timer (NI-04).
- Offline proof: all 13 band rules fire on two different Mondays (Health Data's `compute_day` day by day, state
  carried, profile with birthDate, rules re-written in Python). Needs a fresh account (27 unused days) on dev.
- Found on the way: **PB-04 needs `birthDate`** in the BODY profile (workout.py `has_profile_hr_anchor`), else no
  vo2max at all; the runner refuses PB-04/ALL runs without it. **PB-05 needs BioZ bursts** (respiration.py), so
  `DayShape.breath_pauses` adds a 13-min 4 Hz burst with 14-s pauses (6 disturbances). The single PB-02 (three flat
  weeks scored Low) and PB-05 scenarios never fired before; both fixed and proven offline. **DX-06's
  hrvAboveBaseline** reads the hour before sleep, so the wind-down now follows a low-HRV night.
- Harness: `month_sim.py`/`all_sim.py`/`all_real.py`/`singles.py` in the job tmp dir. The day file needs assemble's
  burst stitching (bioz pieces leave the packets, joined per burstId), and `--with boto3`.
- **Dev result (23 Oct, commit 2925c46):** a first try on the old account (created 21 Oct) proved nothing. Its days
  already held the per-feature runs' data, and the lab counted their old alerts as "fired". On a fresh account
  (created 14:31Z, birth date set first), all 27 days were final about 6 min after sending ended. 15 of 16 alerts
  fired; NI-04 waits on the 2 h hydration timer (`alert-schedules.ts`). A re-test needs a fresh account each time.
