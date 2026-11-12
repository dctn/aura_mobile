---
name: cognito-sign-in
description: How the demo signs in on dev Cognito — windows client, preventUserExistenceErrors quirks, both-codes trick, SES sandbox
metadata:
  type: project
---

Pool `us-west-1_KSpdUo5a7`. The feature branches define four app clients: mac `407e5m02bus00paccsqvviq4im`,
windows `25r2s81bddihbt6lq5vgmcom0u`, ios `78pm5ikl9nvm409ebst83nri97`, android `5shodqdr25b9lhclsm2nqcaevu`
(`aws_docs/docs/services/identity.md` row 124). **Since the 21 Oct deploy from `main`, dev has only mac and
windows**: main's Identity stack defines no phone clients, so that deploy deleted them (see [[dev-backend-state]]).
The demo therefore uses the **windows** client (public, allows email OTP, in every API's JWT audience) and still
registers devices as platform `android`. When Identity is redeployed from the feature branches, switch
`demo/config/aura-dev.json` back to the android client. Check `GET /v1/config` on Identity for the live list.

**Pool hides account status (`preventUserExistenceErrors`):**
- Unknown email → SELECT_CHALLENGE without `EMAIL_OTP`; choosing it gives "The selected challenge is not available."
  The demo treats both as "no account" and signs up.
- Unconfirmed account → a fake EMAIL_OTP challenge and no email is sent.
- Confirmed account → `ResendConfirmationCode` returns a fake 200 and sends nothing.
- `SignUp` still honestly returns `UsernameExistsException`.
- So `CognitoClient.start()` asks for the sign-in code **and** a confirmation code (`also_confirm`); a 6-digit code
  confirms sign-up, an 8-digit one finishes sign-in. Tests in `demo/tests/test_auth.py`.

**SES sandbox (us-west-1):** production access off, 200/day. Codes reach only verified addresses. Verified:
the sender <project Gmail (SES sender)> and the user's test address (verified 21 Oct with their approval). The user has no
access to the project Gmail inbox. Cognito sends via SES "Aura Clarus" with a CustomMessage Lambda.
Verifying another address is an AWS write — ask first.

**Why:** these quirks cost a long debugging session ("no mail received", "challenge not available").
**How to apply:** if a code doesn't arrive, check SES verification and send stats before changing app code. Prod needs its own domain (DKIM/SPF/DMARC) and SES production access.
