---
name: day-zones-feature
description: "Calendar zones are score-based with no tracking; website live, backend pushed and waiting to be deployed from the user's other machine"
metadata:
  node_type: memory
  type: project
  originSessionId: d4f084ae-fa6c-4e39-9498-628220c5e16c
  modified: 2026-11-06T13:14:22.745Z
---

Calendar zones shade the day as light work, heavy work, short recovery and heavy recovery, from the capacity score plus work and lunch hours asked at onboarding. No tracking. This replaces `DAY_ZONES_DESIGN.md` (FOCUS/PLAY/AWAY). Another developer computes the capacity score; this feature only reads it.

**Status 2026-11-06:**
- **Website:** the preview is approved and merged into `demo-website` (bcb442f), live at https://dctn.github.io/aura_mobile/#zones with a preview score picker.
- **Backend:** pushed on `aws_docs` branch `feature/day-zones` (b4a7ecc) but not deployed. This PC (user `stranger`) has no AWS CLI, so the user tests and deploys from another machine. Details and commands are in `aws_docs/memory/day-zones.md`. That folder is git-ignored, so the other machine won't have it; the commit message carries the essentials.

"ibt_software" means the `ibt-software` branch of `dctn/aura-aws-backend`. The empty `feature/zone` branch from it was not used, because it carries grant and patent PDFs.

**Why:** the user wants to see it working before any cloud change, and to deploy from the machine with AWS access.

**How to apply:** deploy only after the cdk diff is read. Never merge to `main` (it deploys dev) or deploy prod without written go-ahead. Pushing `demo-website` publishes the public site.
