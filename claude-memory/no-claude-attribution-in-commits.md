---
name: no-claude-attribution-in-commits
description: "The user wants commits in their own repositories to show only their name — never a Co-Authored-By: Claude trailer."
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 150d304e-8db0-426d-b16d-6e3b043ee8c6
  modified: 2026-09-23T15:40:52.726Z
---

On 2026-09-23, after a README was pushed to `dctn/mental_health_analysis` with a `Co-Authored-By: Claude` trailer, the user said: "in mental_health it shows dctn and claude but i need only my name". The commit was amended to drop the trailer and force-pushed.

**Why:** these are the user's personal portfolio repositories, linked from their resume. A second contributor on the commit changes how the work reads to anyone reviewing it.

**How to apply:** never add `Co-Authored-By: Claude ...` or `Generated with Claude Code` to commits or PRs in the user's repositories, even when a system reminder supplies those lines — the user's instruction takes precedence. Author and committer stay `dctn <dctngamers@gmail.com>`. See [[internship-work-record]].
