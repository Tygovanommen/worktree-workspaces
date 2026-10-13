# Performance and running Claude in parallel

Notes from 13 Oct 2026, before the plugin had been run inside PhpStorm. Measure once it is in daily use.

## The plugin itself

- Git state is read per repo per ticket with `git status --porcelain=v2 --branch` and
  `git rev-list --count HEAD --not --remotes`. The scan runs in the background, at most once per 10 s when PhpStorm
  gets focus, and after plugin actions. It never runs on file changes.
- Both commands run with `GIT_OPTIONAL_LOCKS=0`, so the scan never takes `index.lock` and can't clash with a commit
  Claude (or you) is making in the same worktree.
- Cost grows with the number of workspaces × tickets × repos, including closed tickets. Planned improvement: scan in
  parallel and only the workspaces that have an open tab.

## PhpStorm

- Each open ticket tab is a full project (indexes, analysis, VCS). Memory is about the same as separate windows (one
  PhpStorm process either way); one tab holding backend + frontend is slightly cheaper than two windows.
- The default heap is 2 GB. With 3+ Laravel + Nuxt tickets open, raise it to 4–6 GB (Help › Change Memory Settings).
- A new ticket indexes once on first open; reopening is fast because indexes are kept per project. Close tabs you
  aren't using; the ticket stays in the panel.
- Optional, speeds up git status for PhpStorm, Claude and the plugin. Run once in each repo under `main/` (worktrees
  share the setting):

  ```
  git config core.fsmonitor true
  git config core.untrackedCache true
  ```

## Running Claude in several tickets and workspaces

- **Safe by design:** every ticket has its own folders and branch, so sessions in different tickets can't overwrite
  each other's files. `vendor/` and `node_modules/` are APFS clones, so installs are independent. Git refuses to check
  out one branch in two worktrees.
- **Where to start Claude:** in the ticket folder (e.g. `klant-x/LOGIN-42/`), so it sees backend and frontend
  together. A `CLAUDE.md` in the workspace folder (`klant-x/`) is picked up by every ticket of that workspace: a good
  place to explain how backend and frontend fit together.
- **One session per ticket.** Two sessions in the same ticket share a working tree and trip over each other.
- **Shared runtime state is the real risk.** `.env` is copied from main, so every ticket uses the same database: a
  `php artisan migrate` on one ticket's branch changes it for all. Two dev servers (`npm run dev`, `artisan serve`)
  clash on ports.
- **Finish ticket and `.claude/`:** Claude creates `.claude/` in the folder it runs in. Finish Ticket currently treats
  that as "other files" and keeps the ticket folder. Planned fix: treat `.claude/` like `.idea/`.

## Planned changes

1. Finish Ticket: treat `.claude/` (and `CLAUDE.local.md`?) as metadata.
2. Lighter scan: parallel, only workspaces with an open tab, skip unchanged repos.
3. Per-ticket database name in the ticket's `.env` (e.g. `DB_DATABASE=klant_x_login_42`), optional per workspace.
