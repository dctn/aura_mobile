# aura_mobile — instructions for Claude

This folder holds the mobile side of Aura Clarus. Today that is `demo/`, a phone-sized PyQt6 app that tests the
18 mobile features (DX-01–07, DX-09, PB-01–05, NI-02–04, BS-01–02) against the **dev** backend in us-west-1.
The backend code for those features lives in `../aws_docs` (branch `mobile-features`); this folder is the client.

Read the project memory below before mobile work. Keep it current: one fact per file in this folder, update a file
instead of adding a duplicate, and add a line to `MEMORY.md` for each new file.

## Hard rules
- Talk only to **dev us-west-1**, with the user's own test account. Never prod, never **ap-south-1**.
- AWS is **read-only** unless the user gives written permission naming the scope (e.g. one SES identity, one deploy).
- Don't modify `../aws_docs`, `../aura_window_app` or `../aura_IOS_app` from mobile work. `aura_IOS_app` is reference only.
- Tokens live only in Windows Credential Manager ("AuraMobileDemo"). Client ids and API URLs are public config.
  No secrets or personal data in files here.
- `demo/` is its own git repo (22 Oct): remote `github.com/dctn/aura_mobile` (private), branch `demo`. Commit or push
  only when the user asks; commits by dctn only, no Co-Authored-By lines. `aura_mobile/` itself is not in git.
- Run Python through uv from `demo/`: `/c/Users/PC/.local/bin/uv run --quiet ...`. Gates: `uv run pytest`,
  `uv run ruff check src tests scripts`.
- Never delete the test account from scripts.

## Project memory
@MEMORY.md
