# Worktree Workspaces — context for Claude

PhpStorm plugin by Netonic: one PhpStorm window, one tab per ticket, backend + frontend together via git worktrees.
User-facing docs: `README.md` (public on GitHub, MIT). Original plan, mock-ups and decisions: `docs/PLAN.md`. Performance and parallel
Claude sessions: `docs/PERFORMANCE.md`.

## Status (13 Oct 2026)

- Version 0.1.0, id `nl.netonic.worktree-workspaces`, vendor Netonic, `since-build` 262 (PhpStorm 2026.2+).
  Distributed via GitHub Releases + `updatePlugins.xml` (custom plugin repository), not the Marketplace.
- Verified: compiles against PhpStorm 2026.2.3 (build 262.10968.76) jars; `CoreTests` pass on real git repos;
  `tools/api-check.sh` reports no internal / experimental / deprecated API use and no missing members; every
  `plugin.xml` class, icon, extension point and action group exists in build 262; two independent code reviews
  (12 + 21 findings fixed; open: some config reads on the EDT, see backlog).
- **Not verified yet: running inside PhpStorm.** Use the checklist at the bottom on first install and fix what breaks.

## Model on disk

```
<workspace>/                 e.g. ~/PhpstormProjects/klant-x
  .workspace.json            name, branchPattern ("feature/{ticket}"), repos{name: {url, baseBranch, copy[]}}
  main/                      real clones (main/backend, main/frontend); also a project ("klant-x · main")
  <TICKET>/                  one PhpStorm project per ticket
    .idea/                   copied from main/.idea minus workspace.xml etc.; .name = "klant-x · TICKET"
    <repo>/                  git worktree of main/<repo>; repos the ticket skips are attached from main/
```

A folder next to `main/` is a ticket only if it has `.idea/` or a git checkout (`Workspace.looksLikeTicket`).

## Code map

`src/main/java/nl/netonic/workspaces/`

- `core/` — **pure JDK, no IntelliJ imports** (keep it that way; it is what `CoreTests` covers).
  - `WorkspaceConfig` + `Json` — `.workspace.json` read/write (own tiny JSON, no Gson).
  - `Workspace`, `Ticket`, `TicketRepo` — folder model; `Ticket.repos()` resolves CLONE / WORKTREE / ATTACHED / MISSING.
  - `GitRunner` — git CLI with `GIT_TERMINAL_PROMPT=0`, `GIT_OPTIONAL_LOCKS=0`, timeouts.
  - `RepoStatus` — porcelain v2 parse + unpushed count (`rev-list HEAD --not --remotes`).
  - `WorkspaceOps` — create workspace, clone/adopt repo, create ticket / add worktree, finish checks, finish.
  - `FileCloner` — `cp -c -R` (APFS clone) → `cp -R` → Java copy; recursive delete that never follows symlinks.
  - `IdeaFiles` — prepares a ticket's `.idea` (copy shared settings, module, `.name`, `vcs.xml`, attached content roots).
- `ide/` — IntelliJ layer.
  - `WorkspaceSettings` (app state, `worktreeWorkspaces.xml`), `WorkspaceModel` (background scan → `TOPIC`).
  - `Tickets` (current ticket, open/focus, macOS merge-into-tabs), `ProjectRoots` (sync attached roots + Git mappings
    of an open project), `TicketStartupActivity`, `RefreshListeners`, `Background` (progress task + notifications),
    `IdeGit` (git path/env from PhpStorm), `ActionContext` (selection → ticket/workspace), `Notifier`,
    `Feedback` (GitHub links, pre-filled issue form, one-time star nudge after the 3rd ticket).
  - `ui/` — tool window panel + renderer, dialogs (new workspace / ticket, add repo, finish).
  - `actions/` — all actions; `TicketSwitcherWidgetAction` is the toolbar dropdown.
- `src/main/resources/META-INF/plugin.xml` — registrations; `pluginIcon*.svg`; `icons/workspaces*.svg` (tool window).

## Build and test

```
./build.sh             # tests + zip; PHPSTORM_CONTENTS defaults to ~/Applications/PhpStorm.app/Contents
tools/api-check.sh     # must end with "problems: none"
```

- PhpStorm 2026.2's jars are Java 25 class files, so javac must be 25; output targets `--release 21`.
  `JAVA_HOME` defaults to PhpStorm's bundled JBR (has `javac`, no `jar`: `build.sh` falls back to `zip`).
- CI: `tests.yml` runs `CoreTests` on every push (macOS + Linux, no PhpStorm). `release.yml` runs on a `v*` tag:
  downloads PhpStorm `PHPSTORM_VERSION` for Linux (sha256-checked, cached, only `lib/`, `plugins/vcs-git/`,
  `product-info.json`), runs `build.sh` + `api-check.sh`, and publishes the GitHub release with the zip. Keep
  `PHPSTORM_VERSION` on the `since-build` release. `build.sh` / `api-check.sh` accept both the macOS and Linux layout.
- No Gradle setup yet. If you add one (IntelliJ Platform Gradle Plugin 2.x, `phpstorm("2026.2")`), keep `build.sh`
  working or replace it in the README.
- Release: bump `<version>` + `<change-notes>` in `plugin.xml`, run `tools/release.sh` (local build + API check,
  points `updatePlugins.xml` at the new release), commit, `git tag v<version>`, push the tag. `release.yml` fails if
  the tag doesn't match `<version>`.
- `Feedback.REPO` and the URLs in `README.md`, `updatePlugins.xml`, `.github/` assume
  `github.com/Tygovanommen/worktree-workspaces`; change them together if the repo moves.

## Rules for changes

- No git or file IO on the EDT: use `Background.run` or a pooled thread, then `invokeLater` for UI.
  Actions extend `WsAction` (update on BGT).
- Prefer stable APIs; run `tools/api-check.sh` after every change.
- Safety, never relax without a test:
  - nothing ever deletes or moves `main/` (`WorkspaceOps.isProtected`, case-insensitive);
  - Finish is all-or-nothing for uncommitted changes unless the user ticks discard;
  - a ticket folder that still has other files after the worktrees are removed is kept;
  - never copy `.idea/workspace.xml` (shared `ProjectId` merges projects);
  - close projects (check `closeAndDispose`'s result) before moving or removing their folders.
- Keep `.workspace.json` backward compatible; unknown fields are kept as-is on save (`WorkspaceConfig.extra`).
- Add a `CoreTests` case for any change in `core/`.

## Platform notes (build 262)

- Toolbar widget extends `com.intellij.openapi.wm.impl.ExpandableComboAction` (impl package, not annotated internal):
  the most likely thing to break on PhpStorm upgrades. The panel and Tools › Workspaces still work without it.
- Opening a ticket: `ProjectUtil.openOrImport(path, null, true)`; on macOS `MergeAllWindowsAction` is then run via
  `ActionManager.tryToExecute` with the new frame as context (toggle in the panel's ⚙).
- `ProjectActivity` is a Kotlin suspend interface; the Java implementation returns `Unit.INSTANCE`.
- Git4Idea is a dependency only for `GitExecutableManager` (git path from Settings › Git).
- PhpStorm may show a trust dialog for new ticket folders (trust APIs are internal, so the plugin doesn't touch them).

## Backlog

From `docs/PERFORMANCE.md` and `docs/PLAN.md`, roughly in order:

1. Finish Ticket: treat `.claude/` like `.idea/` (Claude creates it in the ticket folder).
2. Lighter status scan: parallel, only workspaces with an open tab, skip unchanged repos.
3. Per-ticket `DB_DATABASE` in the ticket's `.env` (optional per workspace), so migrations don't hit a shared DB.
4. Setup commands after create (`composer install`, `npm ci`, migrations, `herd link`).
5. Jira picker for New Ticket; ticket notes/status; auto-tidy merged tickets and idle tabs.
6. Default shortcut for Switch Ticket (currently unbound).
7. Move remaining config reads off the EDT (`Tickets.current` cache miss in the panel/switcher,
   `ActionContext.allWorkspaces()` in New Ticket / Add Repo, `repos()` in dialogs): use `WorkspaceModel.snapshot()`.

## First-run checklist (manual, in PhpStorm)

1. Install the zip and restart: *Workspaces* tool window on the left, Tools › Workspaces menu, toolbar widget next to
   the project name once a workspace exists.
2. New Workspace with one git URL and one existing checkout: the checkout is moved, `.workspace.json` has the right
   `baseBranch`, `main` opens as tab "name · main" with both repos and two Git roots.
3. New Ticket with both repos: tab "name · KEY", branches `feature/KEY`, `vendor/` and `node_modules/` present.
4. New Ticket with one repo: the other repo shows from main (grey in the panel); right-click › *Add … to …* works
   and the attached root disappears.
5. Switcher: lists all tickets, focuses an open tab, opens a closed one as a tab (macOS merge).
6. Finish: dirty worktree needs *Discard*; an extra file keeps the folder; a clean ticket is removed and its merged
   branch deleted.
7. Restart PhpStorm: workspaces remembered, status refreshes when PhpStorm regains focus.
