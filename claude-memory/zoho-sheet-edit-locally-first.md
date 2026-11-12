---
name: zoho-sheet-edit-locally-first
description: "Shared Zoho workbook \"CLARUS | BACKED DETAILS\" — draft docs locally, get approval, then write; never clear or revert it"
metadata:
  node_type: memory
  type: feedback
  originSessionId: 150d304e-8db0-426d-b16d-6e3b043ee8c6
  modified: 2026-10-13T16:13:09.688Z
---

Draft every Zoho sheet change locally first (a local .xlsx plus `aws_docs/docs/services/<name>.md`), show it to the user, and only write to Zoho once they approve. Never clear ranges or roll back versions in the shared workbook.

**Why:** on 2026-10-13 I wrote rows straight into the shared "Identity server" tab and then cleared it to restyle it. The user said "restore it first" and "we need edit in local then move to zoho". Zoho versions cover the whole workbook, so a revert would wipe other people's edits to other tabs.

**How to apply:** for each service tab, build the local file, ask for review, then insert rows under the existing headers. Want code shown as GitHub permalinks (pinned to a commit), not pasted code. Want a professional layout that reads easily: a summary, an Example column, a glossary. See [[aws-microservices-rebuild]].

**Writing mechanics (learned 2026-10-13):** fill cells with `set_content_to_multiple_cells` in batches of 50 cells or fewer. Use `insert_row` only under a full 9-column header row, because it fills only the columns the header has. Inserted rows take the style of the row above them, so run `format_ranges` once at the end: reset everything to white Arial 10 with wrap, then apply the section, header and summary styles.
