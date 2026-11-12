---
name: ios-app-is-reference-only
description: aura_IOS_app is the Swift reference and must never be modified; work happens in aura_window_app and deep_focus_with_music.
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 150d304e-8db0-426d-b16d-6e3b043ee8c6
  modified: 2026-09-14T05:15:23.852Z
---

`D:\intellj\aura\aura_IOS_app` is reference only: "here IOS app is referance; don't touch it" (stated 2026-09-14).

**Why:** It is the behavioural source of truth that the Windows PyQt6 port is checked against, not a project being built.

**How to apply:** Read it freely, but never edit, format, build, git-operate or clean files inside it. Build work goes into `aura_window_app` and `deep_focus_with_music`, planned against time and tasks. See [[aura-workspace-layout]].
