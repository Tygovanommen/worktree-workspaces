# Worktree Workspaces — context for Claude

PhpStorm plugin by Netonic: one PhpStorm window, one tab per ticket, backend + frontend together via git worktrees.
User-facing docs: `README.md` (public on GitHub, MIT). Original plan, mock-ups and decisions: `docs/PLAN.md`. Performance and parallel
Claude sessions: `docs/PERFORMANCE.md`.

## Status (14 Oct 2026)

- Version 0.2.4, id `nl.netonic.worktree-workspaces`, vendor Netonic, `since-build` 262 (PhpStorm 2026.2+).
  Distributed via GitHub Releases + `updatePlugins.xml` (custom plugin repository), not the Marketplace.
- Verified: compiles against PhpStorm 2026.2.3 (build 262.10968.76) jars; `CoreTests` pass on real git repos;
  `tools/api-check.sh` reports no internal / experimental / deprecated API use and no missing members; every
  `plugin.xml` class, icon, extension point and action group exists in build 262; two independent code reviews
  (12 + 21 findings fixed; open: some config reads on the EDT, see backlog).
- **Not verified yet: running inside PhpStorm.** Use the checklist at the bottom on first install and fix what breaks.

## Model on disk

```
<workspace>/                 e.g. ~/PhpstormProjects/klant-x
  .workspace.json            name, branchPattern ("feature/{ticket}"), repos{name: {url, baseBranch, copy[]}},
                             tickets{folder: {branch}} (written on create, removed on finish)
  main/                      real clones (main/backend, main/frontend); also a project ("klant-x · main")
  <TICKET>/                  one PhpStorm project per ticket; key "A-1/x" → folder + tab "A-1-x", branch "feature/A-1/x"
    .idea/                   copied from main/.idea minus workspace.xml etc.; .name = "klant-x · TICKET"
    <repo>/                  git worktree of main/<repo>; repos the ticket skips are attached from main/
```

Single-repo workspace (one repo when created): `main/` is the clone itself (`main/.git`), each `<TICKET>/` is a
worktree of it with its `.idea/` inside, links are `klant-x-main` / `klant-x-login-42`. Detected from disk
(`Workspace.isSingleRepo`), not stored; the repo's key in `repos` is the workspace name (`singleRepoName`). Always go
through `Workspace.repoDir` / `mainRepoDir`, never `<ticket>/<repo>` by hand. Adding a second repo is refused.
`RepoStatus` ignores untracked `.idea/` and `.DS_Store`, and Finish `git clean`s them before `worktree remove`.

Next to the workspace (its parent, e.g. `~/PhpstormProjects`) the plugin keeps symlinks so tools that only look one
folder deep (Herd, Valet) find every repo: `klant-x-backend → klant-x/main/backend`,
`klant-x-login-42-backend → klant-x/LOGIN-42/backend` (`core/Links`). Made on clone/adopt/add worktree, removed on
Finish, and every background scan (`WorkspaceModel` → `WorkspaceOps.syncLinks`) adds missing ones and removes ours
whose target is gone (ticket deleted elsewhere). No manual action.

A folder next to `main/` is a ticket only if it has `.idea/` or a git checkout (`Workspace.looksLikeTicket`).
Ticket keys may contain `/` (`Workspace.isValidTicketKey`, `ticketFolderName`); only the folder name is a ticket name.

## Code map

`src/main/java/nl/netonic/workspaces/`

- `core/` — **pure JDK, no IntelliJ imports** (keep it that way; it is what `CoreTests` covers).
  - `WorkspaceConfig` + `Json` — `.workspace.json` read/write (own tiny JSON, no Gson); `ticketBranch(folder)` = saved
    branch, else the pattern.
  - `Workspace`, `Ticket`, `TicketRepo` — folder model; `Ticket.repos()` resolves CLONE / WORKTREE / ATTACHED / MISSING.
  - `GitRunner` — git CLI with `GIT_TERMINAL_PROMPT=0`, `GIT_OPTIONAL_LOCKS=0`, timeouts.
  - `RepoStatus` — porcelain v2 parse + unpushed count (`rev-list HEAD --not --remotes`).
  - `WorkspaceOps` — create workspace, clone/adopt repo (saves `adoptedFrom`), create ticket / add worktree, finish
    checks, finish, undo workspace (`checkBeforeUndo`, `undoDestinations`, `undoWorkspace`),
    `listBranches`. Base branch: `refs/heads/x` / `refs/remotes/<r>/x` must exist; a plain name is local first, then
    `origin/x`, then HEAD (warning). The base is always fetched; config writes go through `updateConfig` (re-read first).
  - `FileCloner` — `cp -c -R` (APFS clone) → `cp -R` → Java copy; recursive delete that never follows symlinks.
  - `Links` — symlinks next to the workspace (name: lowercase `workspace-[ticket-]repo`, relative target).
  - `IdeaFiles` — prepares a ticket's `.idea` (copy shared settings, module, `.name`, `vcs.xml`, attached content roots).
- `ide/` — IntelliJ layer.
  - `WorkspaceSettings` (app state, `worktreeWorkspaces.xml`: workspaces + all options incl. `defaultRepos`, the
    New Workspace rows, `newWorkspaceOpenIn`, the dialog's default *Open in*; `opsOptions()` → core's
    `WorkspaceOps.Options` for links / default copy list), `WorkspaceModel` (background scan → `TOPIC`, also syncs links).
  - `RepoTerminals` (a terminal tab per repo on a project's first open, flag in `PropertiesComponent`; again on
    showing the Terminal window after its last tab was closed, `OnTerminalShown`),
    `MarkdownRunFolder` (sets the Markdown registry key `markdown.command.runner.use.file.directory` on a workspace
    project's open, else ▶ in `frontend/README.md` runs in the ticket folder; resets it only if we set it),
    `CloneListener` (`checkoutListener` before `ProjectDirCheckoutListener`: offers to move a fresh clone into a
    workspace, else PhpStorm opens it as usual), `RecentProjects` (Recent Projects keeps only `main` per workspace:
    the workspace folder and closed tickets are removed on every scan and after a tab closes, never while PhpStorm
    quits, so open ticket tabs still reopen; `RecentProjectsManager.hasPath` / `removePath`; option `tidyRecentProjects`),
    `OpenAsTab` (`projectAttachProcessor`, `order="first"`, macOS: *Merge in Current* in the Open Project dialog,
    opens the project in a new frame and merges it in; option `openAsTabButton`), `TicketColors` (on a ticket's
    open: a project color from PhpStorm's 9-color palette that no other open project has; the index we set is kept in
    `PropertiesComponent`, a color the user picked is never changed; option `colorTickets`), `WindowTabs` (macOS
    window tabs: a dot in each project's color per tab and a light fill on unselected tabs; option `styleWindowTabs`).
  - `Tickets` (current ticket, open/focus, macOS merge-into-tabs; `OpenIn` TAB / NEW_WINDOW / THIS_WINDOW for New
    Workspace, THIS_WINDOW = `closeAndDispose` then open, since frame reuse needs Kotlin-internal `OpenProjectTaskBuilder`), `ProjectRoots` (sync attached roots + Git mappings
    of an open project), `TicketStartupActivity`, `RefreshListeners`, `Background` (progress task + notifications),
    `IdeGit` (git path/env from PhpStorm; `branches()` from an open `GitRepository` of main/<repo> or a worktree,
    else `git for-each-ref`), `ActionContext` (selection → ticket/workspace), `Notifier`,
    `Feedback` (GitHub links, pre-filled issue form, one-time star nudge after the 3rd ticket).
  - `ui/` — tool window panel + renderer, dialogs (new workspace / ticket, add repo, finish, clone into workspace),
    `WorkspacesConfigurable` (Settings › Tools › Worktree Workspaces).
  - `actions/` — all actions; `TicketSwitcherWidgetAction` is the toolbar dropdown (`TicketPopups.switcher`: current
    workspace in full, the others as submenus); Switch Ticket opens `TicketPopups.allTickets` (flat, searchable).
- `src/main/resources/META-INF/plugin.xml` — registrations; `pluginIcon*.svg`; `icons/workspaces*.svg` (tool window).

## Build and test

```
./build.sh             # tests + zip; PHPSTORM_CONTENTS defaults to ~/Applications/PhpStorm.app/Contents
tools/api-check.sh     # must end with "problems: none"
```

- PhpStorm 2026.2's jars are Java 25 class files, so javac must be 25; output targets `--release 21`.
  `JAVA_HOME` defaults to PhpStorm's bundled JBR (has `javac`, no `jar`: `build.sh` falls back to `zip`).
- CI: `tests.yml` runs `CoreTests` on every push (macOS + Linux, no PhpStorm). `release.yml` runs by hand (Actions › Release ›
  Run workflow; creates tag `v<version>` itself) or on a pushed `v*` tag:
  downloads PhpStorm `PHPSTORM_VERSION` for Linux (sha256-checked, cached, only `lib/`, `plugins/vcs-git/`,
  `product-info.json`), runs `build.sh` + `api-check.sh`, and publishes the GitHub release with the zip. Keep
  `PHPSTORM_VERSION` on the `since-build` release. `build.sh` / `api-check.sh` accept both the macOS and Linux layout.
- No Gradle setup yet. If you add one (IntelliJ Platform Gradle Plugin 2.x, `phpstorm("2026.2")`), keep `build.sh`
  working or replace it in the README.
- Release: bump `<version>` + `<change-notes>` in `plugin.xml`, run `tools/release.sh` (local build + API check,
  points `updatePlugins.xml` at the new release), commit, push, then Actions › Release › Run workflow. It fails if
  release `v<version>` already exists, or if a pushed tag doesn't match `<version>`.
- `Feedback.REPO` and the URLs in `README.md`, `updatePlugins.xml`, `.github/` assume
  `github.com/Tygovanommen/worktree-workspaces`; change them together if the repo moves.

## Rules for changes

- No git or file IO on the EDT: use `Background.run` or a pooled thread, then `invokeLater` for UI.
  Actions extend `WsAction` (update on BGT).
- Prefer stable APIs; run `tools/api-check.sh` after every change.
- Safety, never relax without a test:
  - nothing ever deletes or moves `main/` (`WorkspaceOps.isProtected`, case-insensitive), except Undo Workspace,
    which moves main's repos out (never deletes one) only after every ticket's worktrees are gone, refuses on any
    uncommitted change, keeps ticket branches, and only deletes the workspace folder when nothing but our files is
    left (`.workspace.json`, main/'s `.idea`, `.DS_Store`);
  - Finish is all-or-nothing for uncommitted changes unless the user ticks discard;
  - a ticket folder that still has other files after the worktrees are removed is kept;
  - never copy `.idea/workspace.xml` (shared `ProjectId` merges projects);
  - close projects (check `closeAndDispose`'s result) before moving or removing their folders;
  - links never replace an existing file, folder or link, and only links pointing into the finished ticket are
    removed (`Files.delete` on the link, never following it); the scan only removes dangling links named
    `<workspace>-…` that point into the workspace; a symlink is never adopted.
- Keep `.workspace.json` backward compatible; unknown fields are kept as-is on save (`WorkspaceConfig.extra`).
- Add a `CoreTests` case for any change in `core/`.

## Platform notes (build 262)

- Toolbar widget extends `com.intellij.openapi.wm.impl.ExpandableComboAction` (impl package, not annotated internal):
  the most likely thing to break on PhpStorm upgrades. The panel and Tools › Workspaces still work without it.
- Opening a ticket: `ProjectUtil.openOrImport(path, null, true)`; on macOS `MergeAllWindowsAction` is then run via
  `ActionManager.tryToExecute` with the new frame as context (toggle in the panel's ⚙).
- `ProjectActivity` is a Kotlin suspend interface; the Java implementation returns `Unit.INSTANCE`.
- Git4Idea is a dependency for `GitExecutableManager` (git path from Settings › Git) and `GitRepositoryManager`
  (branch list in New Ticket, same as the Git branches popup).
- Repo terminals: `TerminalToolWindowTabsManager` / `TerminalToolWindowTabBuilder` (named tabs, deferred start) are
  `@ApiStatus.Experimental` and live in the terminal's `intellij.terminal.frontend` content module, which a v1
  descriptor can't depend on. `RepoTerminals` calls them by reflection through the class loader of the
  `Terminal.OpenInReworkedTerminal` action (so `api-check.sh` doesn't see them); on any failure it runs
  `Terminal.OpenInTerminal` with a `VIRTUAL_FILE` data context (stable, but shows the window). Recheck on upgrades.
- `CheckoutListener.processCheckedOutDirectory` is `@RequiresBackgroundThread`: `CloneListener` shows its dialog
  through `invokeAndWait`; never bail out off the EDT (that silently skipped the dialog before).
- PhpStorm may show a trust dialog for new ticket folders (trust APIs are internal, so the plugin doesn't touch them).
- Open Project dialog (`ProjectUtil.confirmOpenOrAttachProject`): This Window / New Window / one button from the first
  enabled `ProjectAttachProcessor` (PhpStorm's `ModuleAttachProcessor` = Attach) / Cancel; it can't get a fourth.
  `OpenAsTab` takes that slot only with a path and an open project (`isEnabled`), so module detach and
  `canAttachToProject()` still reach PhpStorm's processor. It overrides the stable `attachToProject`, not the
  `@Experimental` `attachToProjectAsync`, and opens via `invokeLater` (it runs inside PhpStorm's own open).
- macOS window tabs (`MacWinTabsHandlerV2`) are drawn by PhpStorm in Swing: `com.intellij.ui.mac.WindowTabsComponent`,
  a `JBTabsImpl` whose `TabInfo.getObject()` is the window's `IdeFrameImpl`. Its painter ignores `TabInfo` colors and
  uses only the theme keys `MainWindow.Tab.*`, so `WindowTabs` sets `MainWindow.Tab.background` / `hoverBackground`
  in `UIManager` (again on `LafManagerListener`, originals put back when off) and a dot via `TabInfo.setIcon`. The bar
  is rebuilt on merge / move: an AWT `ContainerEvent` listener on that class name restyles it. Recheck on upgrades.
- `TicketColors` sets the toolbar color through `ProjectWindowCustomizerService` (`getCurrentProjectColorIndex`,
  `clearToolbarColorsAndInMemoryCache`, `setAssociatedColorsIndex`; `WindowTabs` reads `getGradientProjectColor`: all
  `@ApiStatus.Internal`), called by reflection the way Change Project Color does it; on failure it logs and leaves
  PhpStorm's color. Recheck on upgrades.

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
   `baseBranch`, `main` opens as tab "name · main" with both repos and two Git roots. Remove a row (×) down to one:
   the name field goes away and the repo lands in `main/` itself; *Open in* New tab / New window / This window
   (no tab outside macOS, no This window without an open project) starts on Settings › New Workspaces › *Open in*; a ticket of it is a worktree folder with its own
   `.idea`, shows clean, and finishes cleanly. Settings › Repos changes the starting rows.
3. New Ticket with both repos: tab "name · KEY", branches `feature/KEY`, `vendor/` and `node_modules/` present.
   Key `KEY/x`: folder + tab `KEY-x`, branch `feature/KEY/x`, saved under `tickets` in `.workspace.json`.
   *Branch from* lists Local / Remote like the Git branches popup; a local pick is used as-is, a remote one fetched.
4. New Ticket with one repo: the other repo shows from main (grey in the panel); right-click › *Add … to …* works
   and the attached root disappears.
5. Switcher: lists the current workspace's tickets, other workspaces as submenus (open tabs first); focuses an open
   tab, opens a closed one as a tab (macOS merge). *Search All Tickets…* / Switch Ticket: typing `klant-y` or `login` finds
   `klant-y / LOGIN-42`.
6. Finish: dirty worktree needs *Discard*; an extra file keeps the folder; a clean ticket is removed and its merged
   branch deleted.
7. Restart PhpStorm: workspaces remembered, status refreshes when PhpStorm regains focus.
8. Links: `~/PhpstormProjects/name-backend` (main) and `name-key-backend` (ticket) exist, Herd lists them as
   `.test` sites; Finish removes the ticket's links; an older workspace gets main's links on the next refresh;
   a ticket folder deleted in Finder loses its links on the next refresh.
9. Settings › Tools › Worktree Workspaces: every option saves and applies; links off removes them on the next refresh.
10. Terminals: first open of a ticket gives one tab per repo, named after it, in its folder; reopening adds none.
    Close every terminal tab, open the Terminal window again: the repo tabs are back, no extra "Local" tab.
    If the tabs are unnamed and the Terminal window pops open, the reflection path failed (see idea.log).
11. Markdown: ▶ next to `npm install` in a ticket's `frontend/README.md` runs in `frontend/`; Registry shows
    `markdown.command.runner.use.file.directory` on; switching the option off resets it.
12. Get from VCS: the dialog appears; New / Add to workspace moves the clone, leaves a link, opens main;
    Open as Usual opens the clone as before.
13. Undo Workspace (panel: right-click the workspace): a ticket with a changed file blocks it; otherwise the
    dialog lists where each repo goes, tabs close, repos land there as plain clones (ticket branches still there),
    links and the workspace folder are gone, and the repos open as projects if you ran it from the workspace's window.
14. Recent Projects (project widget / File › Open Recent): one `name · main` per workspace; the bare workspace folder
    (e.g. `~/PhpstormProjects/nobears`) is gone after a refresh; closing a ticket tab removes it from the list;
    quit with a ticket tab open: it reopens on the next start. With the option off, closed tickets stay in the list.
15. Open Project (File › Open, any folder, macOS): the dialog shows *Merge in Current* instead of *Attach*; it opens
    the project as a tab of the current window. With the option off, *Attach* is back.
16. Toolbar colors: open three tickets as tabs: each tab's toolbar has a different project color; pick one via the
    project widget › Change Project Color, reopen the ticket: your color stays. Window tabs (macOS): each has a dot in
    its project's color (it follows a color change), unselected tabs have a light fill; still there after merging
    another window in, switching theme, and switching the option off and on (off: plain tabs again).
