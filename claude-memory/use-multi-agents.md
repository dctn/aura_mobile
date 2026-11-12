---
name: use-multi-agents
description: "User wants work split across parallel subagents by default (\"use multi-agents always\", 25 Oct 2026)"
metadata:
  node_type: memory
  type: feedback
  originSessionId: 2352f890-89f8-4a5a-874e-47a97a8fb600
  modified: 2026-10-25T07:44:26.005Z
---

Split implementation work across parallel subagents (Agent tool), each owning separate files, instead of doing it all
inline.

**Why:** the user said "use multi-agents always" mid-task on 25 Oct 2026 (the demo website's Today/Recovery/Search
restructure).

**How to apply:**
- Give each agent a disjoint set of files, so they don't collide.
- Brief each agent fully: the plan section, the files to own, the files not to touch, and the gates.
- Run the final gates, the browser walk and the report yourself.
- Small lookups or one-line fixes can still be done inline.

See [[user-profile]].
