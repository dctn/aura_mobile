---
name: github-attack-nov-2026
description: "Second GitHub Actions secret-stealing attack, 10-12 Nov 2026, on three repos via the ibtaura.info identity; never merge those \"ci:\" commits or run their workflows"
metadata:
  node_type: memory
  type: project
  originSessionId: d4f084ae-fa6c-4e39-9498-628220c5e16c
  modified: 2026-11-13T05:07:36.687Z
---

Found 2026-11-13 while pulling. Commits signed "IBT Aura <ibtaura.info@gmail.com>", in pairs: "ci: refresh build cache" adds about 1,450-1,580 lines to a workflow, and "ci: restore" removes it minutes later.

- **What the payload does:** collects GitHub secrets and vars, the job token and the OIDC token, tries to turn them into AWS role credentials, and sends everything to `http://18.143.168.96:8080/exfil`. One variant used a dummy address instead.
- **Repos hit:**
  - `dctn/aura-aws-backend`, `main`: 11 cycles in `.github/workflows/ci.yml`, 10-12 Nov. Its files are net unchanged, at `d6158d9`. Also branch `wfx-docs`.
  - `dctn/aura_window_app`, `main` (`build-check.yml`): 5 cycles. Also branch `wfx-docs`. The `deep_focus_with_music` folder uses the same remote.
  - `ibtaura/Aura-Clarus-Desktop-app`, `main`.
  - Not hit: `dctn/aura_mobile`.
- **Pull of 2026-11-13:** none of the commits pulled into local branches carried the payload. `aura_window_app` local `main` was deliberately NOT updated (local 1 ahead, 12 behind). Its old local commit is 9c21c09 "deleted docs files".
- **Before this:** the 19 and 21 Oct attacks in `aws_docs/memory/repo-security-incident.md`. That account lockdown and branch protection were never confirmed done.

**Why:** a credential with push access to these repos is in an attacker's hands, and a push to `aura-aws-backend` main runs CI with AWS OIDC roles.

**How to apply:** don't merge, cherry-pick or run anything from those commits or the `wfx-docs` branches. Don't push to the affected repos until the user confirms the account is locked down. Treat any new "ci:" commit by that identity as hostile.

**2026-11-13 CloudTrail:** no use of AWS keys outside GitHub runners, but the attacker's pushes made CI redeploy dev from main, so dev lost morning capacity, beta, zones and recovery rhythm (Planner 19 routes). Details in `aws_docs/memory/repo-attack-nov-2026.md`. This PC now has the AWS CLI 2.40.2, signed in as `maheswara`, and every `node_modules/@aura/*` package relinked to its source (stale 19 Oct copies were moved aside), which the deploy needs.
