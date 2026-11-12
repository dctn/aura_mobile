---
name: aws-microservices-rebuild
description: "13 Oct 2026: the user ordered a clean-slate rebuild of the Aura AWS backend as 10 microservices (hexagonal, own table/API each), documented in the Zoho sheet CLARUS | BACKED DETAILS"
metadata:
  node_type: memory
  type: project
  originSessionId: 150d304e-8db0-426d-b16d-6e3b043ee8c6
  modified: 2026-10-13T14:44:02.823Z
---

On 2026-10-13 the user asked for the whole AWS backend as microservices, starting fresh both in AWS us-west-1 and locally. The approved plan is at `C:\Users\PC\.claude\plans\piped-nibbling-quilt.md`.

**The 10 services** come from the user's sketches: Identity, Task, Calendar, Habit, Session, Capacity, Health Data, Content, Planner and Notification.
- Each has its own DynamoDB table, Lambdas, HTTP API and CDK stack, and they talk through EventBridge.
- Code is hexagonal: presentation → service ← data-access. Tests are split into unit, integration and E2E per app page.
- The code rules are clean code plus the Singleton, Factory, Strategy and Composite patterns.

**Kept in AWS through the wipe:** the bootstrap, `aura-dev-foundation`, `aura-dev-email`, `aura-dev-identity` (the Cognito pool) and the content bucket.

**Kept locally:** the algorithms (capacity engine, the Python pipeline) are carried over.

**Docs:** each service gets its own tab in the Zoho workbook "CLARUS | BACKED DETAILS".
- Account: Adarna Zoho. Resource id: j3g97f6c4f7a29dbd4c81940d2c8e5cd2dac8.
- Each tab has 4 sections (DynamoDB, Lambda, S3, Cognito), with the same 9 columns as the Parameters tab.
- A tab is filled after its service is deployed. Identity comes first.

**Why:** the user wants a proper microservice architecture that is easy to maintain and scale.

**How to apply:** build one service at a time and fill its sheet tab once it's deployed. Never put old-architecture content into new docs. This replaces the phase plan in [[aws-project-memory]].
