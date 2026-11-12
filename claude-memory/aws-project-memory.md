---
name: aws-project-memory
description: "All AWS / new cloud architecture memory lives in D:\intellj\aura\aws_docs\memory, loaded by aws_docs\CLAUDE.md; read it before any AWS work."
metadata:
  type: reference
---

The AWS rebuild memory (account access and the read-only rule, the new architecture, the build plan, and the docs rules) lives in `D:\intellj\aura\aws_docs\memory\`, with the index at `memory\MEMORY.md`. `D:\intellj\aura\aws_docs\CLAUDE.md` imports it, so it loads automatically when working inside aws_docs.

**Why:** the user asked on 2026-10-07 that the AWS memory files live inside the aws_docs folder itself.

**How to apply:** before any AWS or cloud work, read `aws_docs\CLAUDE.md` and the files in `aws_docs\memory\`. Save new AWS decisions there, not in this global folder. Core rules even before reading it: AWS stays read-only without written permission, never touch ap-south-1, and never open `aws_docs\keys\`. See [[aura-workspace-layout]].
