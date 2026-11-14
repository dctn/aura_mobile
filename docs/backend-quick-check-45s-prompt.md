# Prompt for the AWS Claude session: accept a 45-second Quick check

> Work in `aws_docs` (repo dctn/aura-aws-backend), dev us-west-1 only, following aws_docs/CLAUDE.md and its memory.
>
> **Why:** the user finds the 3-minute Quick check too long. The Android app (`aura_mobile/aura_android`) now runs each
> part for **45 seconds**: the reaction test (PVT-B style) and the memory span part. The iPhone app
> (`aura_mobile/aura_ios_app`) still runs 3 minutes, so the backend must accept **both** lengths.
>
> **What fails today:**
> - `packages/contracts/src/health-data.ts` (`submitCheck`, about line 409): `pvt.durationSeconds` is
>   `z.number().int().min(60).max(600)`, so a 45 s run is refused with `400 validation_failed` ("pvt.durationSeconds
>   is too small") and isn't saved. The Android app now tells the user the server doesn't take the check yet.
> - `services/health-data/src/service/attention.ts`:
>   - `PVT_B.minSeconds` is 170 and `maxSeconds` is 200. A shorter run gets `invalidReason` = `not_3_minutes`.
>   - `minResponses` is 30. A 45 s run shows only about 15 dots (a dot every 1–4 s plus the 1 s result).
>
> **Branch:** cut `feature/quick-check-45s` from the branch dev runs today. Check `docs/04-build-log.md` and memory
> for which branch that is, since day zones, morning capacity and beta day score are all on dev. Ask me if it's
> unclear.
>
> **Changes:**
> 1. **Contract:** lower the `durationSeconds` minimum to 30 and keep the maximum at 600. Everything else in
>    `submitCheck` stays the same. The app still sends one reaction time per dot, its false starts and the memory
>    `spanCorrect`/`spanAttempts`.
> 2. **Two test lengths in `attention.ts`,** each with its own limits:
>    - **3-minute (`PVT_B`):** keep it exactly as it is today.
>    - **45-second (new `PVT_45`):**
>      - `minSeconds` 40, `maxSeconds` 60. The Android app's offline stub already uses 40–60 s and 8 answers;
>      - `minResponses` 8, which is about half of ~15 dots, the same "about half answered" rule as the 3-minute test;
>      - the same 100 ms false-start limit, 355 ms lapse threshold and 0.2 false-start share.
>    - Pick the length from `durationSeconds`. A run that fits neither window keeps the reason code `not_3_minutes`,
>      because the apps already map that code, or add a clearer code such as `wrong_length` and tell me its name.
> 3. **Don't mix the two lengths in one normal.** Speed and the performance score from 45 s aren't on the same scale as
>    3 minutes: fewer dots, and fewer lapses because fatigue builds over the run.
>    - Store the length on each check, for example `testSeconds: 45 | 180` on `StoredCheck`.
>    - Build the Attention normal and the day's score only from checks of the same length as the one being scored.
>    - The existing 3-minute checks keep counting for 3-minute runs.
>    - Update the "checks counted / needed" progress the same way.
>    - Give the 45 s length its own SD floors. `PERFORMANCE_SD_FLOOR` is 1/60 today ("one error in a run of about 60
>      dots"), so about 1/16 for 45 s. Keep the speed floor at 5% unless you find a reason to change it.
> 4. **Be honest in the docs:** Basner 2011 validated only the 3-minute PVT-B. Say in `docs/services/health-data.md`
>    and the code comment that the 45 s length is this build's choice, made for usability, and is not a validated
>    test. Keep the citations as they are.
> 5. **Memory span:** the app now stops the memory part after 45 s and sends `spanCorrect`/`spanAttempts` for the rows
>    answered by then. The schema has no duration, and the memory part is stored, not scored
>    (`services/health-data/src/service/scores.ts`), so it should need no change. Report it if you find otherwise.
> 6. **Wording:** the `kind` description in the contract ("the 3-minute reaction test only") and the comments in
>    `attention.ts` should say both lengths are accepted.
>
> **Tests:**
> - Unit tests for both windows: 45 s valid, 45 s with too few answers, 60 s refused as the wrong length, 180 s still
>   valid.
> - A normal that ignores checks of the other length.
> - The contract accepting 45.
> - Update `apps/mvp-desktop/feature-check/steps/morning.ts`, which expects a 1-minute run to be `not_3_minutes`. That
>   expectation should still hold.
>
> **Before deploying:** run `npm run lint`, `npm test`, then `cdk diff` for `aura-dev-health-data`. Show me the diff,
> and deploy only after I approve. Add a build-log row, and draft any Zoho rows locally first.
>
> **Git:** commit locally as dctn, with no Co-Authored-By lines. **Do not push** until I confirm the GitHub account is
> locked down after the 10–12 Nov attack. Never merge or run any "ci:" commit by the attacker identity.
>
> **When done, report:** the final limits for both lengths, any new reason code, the field name that stores the
> length, and an example `POST /v1/checks` body and reply for a 45 s run.
