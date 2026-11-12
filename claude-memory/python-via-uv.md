---
name: python-via-uv
description: "On this Windows PC, run Python through uv (not bare python), and make printed text ASCII-safe because the console is cp1252."
metadata:
  node_type: memory
  type: reference
  originSessionId: 150d304e-8db0-426d-b16d-6e3b043ee8c6
  modified: 2026-10-07T16:08:44.517Z
---

Run Python as `/c/Users/PC/.local/bin/uv run --no-project --quiet [--with <pkg>] python -I <script>`, for example `--with pillow` for images or `--with pypdf` for PDF text.

The console is cp1252, so printing non-ASCII text crashes with UnicodeEncodeError. Use `.encode('ascii','replace').decode()` on printed output.

Headless screenshots for checking pages: `"/c/Program Files (x86)/Microsoft/Edge/Application/msedge.exe" --headless=new --screenshot=... --window-size=W,H file:///...`.

The uv cache was about 30 GB in Sep 2026. Clean it only if the user agrees.
