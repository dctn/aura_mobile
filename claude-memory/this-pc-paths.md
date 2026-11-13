---
name: this-pc-paths
description: "This PC (Windows user \"stranger\") maps the other machine's paths D:\\intellj\\aura and C:\\Users\\PC to C:\\mine\\aura and C:\\Users\\stranger; tools found here"
metadata:
  node_type: memory
  type: reference
  originSessionId: d4f084ae-fa6c-4e39-9498-628220c5e16c
  modified: 2026-11-13T05:12:15.941Z
---

Memories written on the other machine use its paths. On this PC:
- `D:\intellj\aura\...` is `C:\mine\aura\...`
- `C:\Users\PC\...` is `C:\Users\stranger\...`; uv is `C:\Users\stranger\.local\bin\uv.exe`
- Node 26.13.0 is at `C:\Program Files\nodejs` (installed 2026-11-05; add it to PATH in old shells). The repos' CI uses Node 22. For the demo website tests, run with `NODE_OPTIONS=--no-webstorage`.
- AWS CLI 2.40.2 is installed (2026-11-13) and signed in as `maheswara` with `aws login`; the default region is us-west-1. Deploy with `MSYS_NO_PATHCONV=1` (otherwise Git Bash rewrites `/cdk-bootstrap/aura/version`), add `-c contentFfmpegStandIn=true` because `layers/ffmpeg.zip` is missing, and leave aura-dev-content out. Every `node_modules/@aura/*` package is relinked to its source (stale copies moved aside on 2026-11-13).
- The `aura_IOS_app` worktrees `wt-*` have broken git links: their gitdirs point at `D:/intellj`.
- In `aws_docs/node_modules`, `@aura/*` were plain copies; `@aura/contracts` was relinked to `packages/contracts` on 2026-11-05.
- Bash heredocs and printf in this shell turn `\a` and `\\` in Windows paths into other characters. Write files holding Windows paths with the Write tool.

**How to apply:** translate paths before using a memory, and see [[aura-workspace-layout]].
