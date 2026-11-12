# Aura mobile workspace (notes, designs, Claude memory)

This branch holds the files that lived beside the app checkouts on the first machine but were in no repo.
It has no app code. The apps are on the branches `ios-app`, `demo` and `demo-website`.

| Folder | What it is |
|---|---|
| `memory/` | Notes for Claude about the mobile work (`CLAUDE.md`, `MEMORY.md` index, one note per topic). They sat at the top of `aura_mobile/`. |
| `design/figma_new_design/`, `design/figma_old_design/` | Figma exports (PNG screens and zips) for the new and the old app design. |
| `claude-memory/` | Claude Code's workspace memory for the whole Aura folder (user profile, workspace layout, app and AWS notes). |

The project Gmail address and the demo user's email are replaced by placeholders, because this repo is public.

## Setting up a new machine

1. Clone and add one folder per branch, the same layout as before:
   ```
   git clone https://github.com/dctn/aura_mobile.git aura_mobile/aura_ios_app
   cd aura_mobile/aura_ios_app
   git checkout ios-app
   git worktree add ../demo demo
   git worktree add ../demo_website demo-website
   git worktree add ../workspace workspace
   ```
2. Copy `workspace/memory/*` to the top of `aura_mobile/`, and `workspace/design/*` there too.
3. Copy `workspace/claude-memory/*` to `~/.claude/projects/<slug of the new Aura folder path>/memory/`
   (for `D:\intellj\aura` the slug is `D--intellj-aura`).
4. Recreate `demo_website/.env.local` with `VITE_DEMO_API=<demo site URL>` (see `demo_website/README.md`).
5. Sign in again on that machine: Windows Credential Manager ("AuraMobileDemo") for the demo app, Keychain for iOS.
   No tokens or passwords are stored in any repo.

The AWS backend is a separate private repo, dctn/aura-aws-backend (branch `feature/beta-day-score`).
