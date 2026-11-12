---
name: aura-ios-reference-repo
description: "What is inside aura_IOS_app — one macOS SwiftUI app repo (ibtaura/Aura-Clarus-Desktop-app) with 4 worktrees, no iOS target; screens, Theme, backend, rules"
metadata:
  node_type: memory
  type: reference
  originSessionId: 2352f890-89f8-4a5a-874e-47a97a8fb600
  modified: 2026-10-21T15:00:08.682Z
---

`D:\intellj\aura\aura_IOS_app` is **not** a git repo itself. It holds a 580 MB zip and one repo, `ibtaura/Aura-Clarus-Desktop-app`, the founder's repo, with 4 worktrees:
- `Aura-Clarus-Desktop-app/` on `rebuild/backend-v2`
- `wt-content-cdk/` on `backend/content-cdk`
- `wt-focus-music/` on `mac/focus-music-stream`
- `wt-port/`, detached, last commit 5 Oct

Other branches: `main`, `staging`, `production`, `refactor/reorg-models-services`.

- **macOS app only.** There is no iOS target, despite the folder name. Screens: Calendar, Categories, Dashboard, Focus, Meeting, Notifications, Profile, Recovery, Trends, Work. Theme: `Aura Clarus Desktop app/Theme/Theme.swift`.
- **Also in the repo:** `Packages/ClarusKit` (Swift client), `backend/` (the eu-north-1 backend-v2 CDK, the source of the `aws_eu-north-1` snapshot), and `contract/openapi.json`.
- **Backend:** eu-north-1 backend-v2 (`Resources/ClarusConfig/`). The `amplify*` files are legacy.
- **Its rules:** read `docs/aura-port.md` and `docs/HANDOFF.md` first. Never push to staging, production or main, or deploy prod, without the founder's word.
- **Security note:** on 20–21 Oct, CloudTrail showed OIDC role attempts from GitHub repo `ibtaura/Aura-Clarus-Desktop-app` main. AWS denied them. That repo's main likely has the same malicious workflow. See [[aws-docs-repo-compromise]].

**How to apply:** read only, never modify; see [[ios-app-is-reference-only]]. Use it for screen behaviour and colours.
