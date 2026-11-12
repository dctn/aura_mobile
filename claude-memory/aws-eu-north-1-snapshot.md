---
name: aws-eu-north-1-snapshot
description: "The OLD Aura Clarus backend in eu-north-1 (still running, used by the Mac and Windows apps) and its read-only snapshot worktree aws_eu-north-1"
metadata:
  node_type: memory
  type: project
  originSessionId: 2352f890-89f8-4a5a-874e-47a97a8fb600
  modified: 2026-10-21T15:00:19.181Z
---

There are two AWS backends in use:
- **Old (eu-north-1, Stockholm):** "backend-v2" CDK, stacks Clarus-dev, Clarus-prod and Clarus-account. Still running (on 15 Oct: dev had 3 users, prod 1). The **Mac app and aura_window_app use it today**. The Amplify Gen 2 leftovers there are mostly deleted.
- **New (us-west-1):** the 10 microservices in `aws_docs`, which only the mobile demo uses so far.

`D:\intellj\aura\aws_eu-north-1` is a **git worktree of aws_docs** on orphan branch `eu-north-1_resource`, with 4 commits on 15 Oct. It is a read-only snapshot organised by feature (`clarus/01-api … 10-account`, `amplify/`, 70 CFN templates, `stepfunctions/`, `inventory.json`, `tools/` exporters). `raw/` is gitignored. It mirrors the Zoho tab "eu-north-1 server documentation".

**How to apply:** never merge this branch into main. Leave the eu-north-1 resources alone until the user approves removing them. Moving the desktop apps onto us-west-1 is still to do. See [[aws-microservices-rebuild]].
