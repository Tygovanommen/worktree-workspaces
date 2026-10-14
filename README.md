# Worktree Workspaces

[![Latest release](https://img.shields.io/github/v/release/Tygovanommen/worktree-workspaces?label=download)](https://github.com/Tygovanommen/worktree-workspaces/releases/latest)
[![Tests](https://github.com/Tygovanommen/worktree-workspaces/actions/workflows/tests.yml/badge.svg)](https://github.com/Tygovanommen/worktree-workspaces/actions/workflows/tests.yml)
![PhpStorm 2026.2+](https://img.shields.io/badge/PhpStorm-2026.2%2B-7B5CF5)
[![MIT license](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

![Worktree Workspaces](docs/cover.png)

**One PhpStorm window. One tab per ticket. Backend and frontend side by side.**

You're on `LOGIN-42`, a hotfix comes in, and a colleague wants a review of `PAY-7`. Each of those touches both the
backend and the frontend repo. That means stashing, switching branches, re-running `composer install`, or juggling
six PhpStorm windows and remembering which backend folder goes with which frontend folder.

Worktree Workspaces gives every ticket its own folder with a [git worktree](https://git-scm.com/docs/git-worktree)
of each repo it touches, and opens it as one PhpStorm tab. Switching tickets is switching tabs.

> PhpStorm's built-in worktree support handles [one repository per project](https://www.jetbrains.com/help/phpstorm/use-git-worktrees.html).
> This plugin is for when a ticket spans several.

## Features

- **A tab per ticket.** `klant-x · LOGIN-42` holds `backend/` and `frontend/`, both on `feature/LOGIN-42`, with
  multi-root Git. Click another tab and you're on another ticket, with editors, run configs and breakpoints untouched.
- **Ready to run in seconds.** `.env`, `auth.json`, `vendor/` and `node_modules/` are copied from your main checkout.
  On macOS that's an APFS clone: instant, and no extra disk space.
- **Picks up where a colleague left off.** If `feature/LOGIN-42` already exists locally or on `origin`, the worktree
  uses it; otherwise it branches from the base branch you pick, local or remote.
- **Only the repos you need.** A backend-only ticket gets a backend worktree; the frontend is attached read-write from
  `main/` and can get its own worktree later with one click.
- **Everything at a glance.** A *Workspaces* panel lists every ticket with branch, changed files and unpushed commits,
  plus a searchable ticket switcher in the toolbar.
- **Safe cleanup.** *Finish Ticket* checks for uncommitted and unpushed work, removes the worktrees, deletes the
  branch only if it's merged, and never throws away changes unless you tick *Discard*.
- **No lock-in.** Ticket folders are plain PhpStorm projects with plain git worktrees. Uninstall the plugin and
  everything still opens.

![Mock-up: a tab per ticket, a switcher in the toolbar, and a panel with git state](docs/plan/ui-mockup.png)

## Install

1. Download `worktree-workspaces-<version>.zip` from the [latest release](https://github.com/Tygovanommen/worktree-workspaces/releases/latest).
2. PhpStorm › Settings › Plugins › ⚙ › **Install Plugin from Disk…** › pick the zip › restart.

**Updates inside PhpStorm:** Settings › Plugins › ⚙ › **Manage Plugin Repositories…** › add
`https://raw.githubusercontent.com/Tygovanommen/worktree-workspaces/main/updatePlugins.xml`.
PhpStorm then offers new versions like any other plugin.

Requires PhpStorm 2026.2 or newer.

## Quick start

1. **Tools › Workspaces › New Workspace…** Give it a name (`klant-x`) and add your repos, either as a git URL or as
   an existing checkout (moved into `klant-x/main/`; a checkout may have the workspace's own name, e.g. turn
   `~/PhpstormProjects/klant-x` into workspace `klant-x`). `main` opens as the first tab. The rows start as
   `backend` and `frontend` (change that in Settings); remove the ones you don't need. With just one repo there is no
   name: `main/` is the repo itself.
2. **New Ticket…** (toolbar switcher or the panel's **+**). Type `LOGIN-42` and tick the repos it touches. A tab opens
   with fresh worktrees on `feature/LOGIN-42`. Type `LOGIN-42/login-form` for `feature/LOGIN-42/login-form`.
3. Work, commit, push. When it's merged: right-click the ticket › **Finish Ticket…**

**macOS tip:** set *System Settings › Desktop & Dock › Prefer tabs when opening documents* to **Always** so ticket
windows stay tabs after a restart. The first time a ticket opens, PhpStorm may ask whether to trust it. Tick
*Trust projects in `klant-x`* so it only asks once.

## How it works

```
~/PhpstormProjects/klant-x/        workspace ("klant" is Dutch for client)
  .workspace.json                  repos, base branches, branch pattern, files to copy
  main/                            tab "klant-x · main"      real clones
    backend/  frontend/
  LOGIN-42/                        tab "klant-x · LOGIN-42"  git worktrees on feature/LOGIN-42
    backend/  frontend/
  PAY-7/                           tab "klant-x · PAY-7"
    backend/                       frontend is attached from main/
```

![Each ticket is one folder and one tab, with a worktree per repo it touches](docs/plan/concept.png)

Next to the workspace the plugin keeps a link per repo, so tools that only look one folder deep (Herd, Valet, ...)
still find everything: `klant-x-backend → klant-x/main/backend` and `klant-x-login-42-backend →
klant-x/LOGIN-42/backend`, so Herd serves `klant-x-login-42-backend.test`. Adopting a checkout leaves such a link at its
old path. It all happens automatically: links are made with tickets and repos, removed when a ticket is finished (or
its folder is deleted), and never replace anything that's already there.

A workspace with one repo skips the repo folders: `main/` is the clone, `LOGIN-42/` its worktree, and the links are
`klant-x-main` and `klant-x-login-42`. Such a workspace can't take a second repo later.

Every repo keeps a single `.git` in `main/`, and ticket folders hold worktrees of it. Shared `.idea` settings (PHP
interpreter, code style, run configurations, data sources) are copied from `main/.idea`. `workspace.xml` is never
copied, so tickets stay separate projects.

## Everyday use

| Where | What |
| --- | --- |
| **Workspaces** tool window (left) | Workspaces › tickets › repos with branch, changed files (`3 changed`) and unpushed commits (`↑1`). Double-click to open or focus a ticket; right-click for the rest. |
| Toolbar, next to the project name | `klant-x / LOGIN-42 ▾`: a searchable list of every ticket, plus New Ticket and New Workspace. |
| Tools › Workspaces, or Find Action | Switch Ticket, New Ticket, Finish Ticket, New Workspace, Add Repo, Undo Workspace. Give **Switch Ticket** a shortcut in Settings › Keymap. |

**New ticket.** The branch comes from the workspace's pattern (`feature/{ticket}`) and can be edited. The ticket
may contain `/`: `LOGIN-42/login-form` gives branch `feature/LOGIN-42/login-form`, folder `LOGIN-42-login-form/` and
tab `klant-x · LOGIN-42-login-form`.

Per repo, *Branch from* lists the same local and remote branches as PhpStorm's Git branches popup, with the local
base branch preselected (else `origin/<base>`). The plugin fetches the base branch, then:

- branch exists locally → worktree on it
- branch exists on `origin` → worktree tracking it
- otherwise → new branch from the branch you picked: a local branch as it is, a remote one freshly fetched

Run `composer install` / `npm ci` only when the lock files differ from `main`.

**Ticket needs another repo after all.** Right-click the grey *main checkout* row › *Add frontend to PAY-7*.

**Finish ticket.** Lists uncommitted changes and commits that are on no remote, closes the tab, runs
`git worktree remove` per repo and deletes the folder. A folder that still holds other files (notes, scripts) is
kept.

**Undo workspace.** Right-click the workspace › *Undo Workspace…* (or Tools › Workspaces) reverses New Workspace:
its tabs close, every ticket's worktrees are removed (branches and commits stay in the repo), and each repo moves back
out of `main/`, to where it was adopted from, or next to the workspace as `klant-x-frontend` for a cloned one (a
single-repo workspace's repo takes the workspace's own path). The links go, and the workspace folder is removed unless
it still holds files of yours. Nothing is done while a ticket has uncommitted changes. Workspaces made with an older version
don't know where adopted checkouts came from, so those land next to the workspace too.

**Terminals.** When a ticket (or main) opens for the first time, each repo gets its own terminal tab in its own
folder (`backend`, `frontend`), so `composer install`, `npm install` and git work like in a single-repo project.
Right-click a repo in the panel › *Open in Terminal* for another one.

**Cloning.** After *Get from VCS* clones a repo, the plugin offers to put it in a new or existing workspace: the clone
moves to `<workspace>/main/<repo>` and main opens. *Open as Usual* leaves it alone.

## Settings

*Settings › Tools › Worktree Workspaces* (or the panel's ⚙ › *Settings…*):

| Setting | Default |
| --- | --- |
| Open tickets as tabs in one window (macOS) | on |
| Open a terminal per repo when a ticket or main opens | on |
| Offer to put a cloned repo in a workspace (after *Get from VCS*) | on |
| Link every repo next to its workspace (for Herd and similar tools) | on; switching it off removes the links |
| New workspaces: location, branch pattern, repos, files to copy | last used folder, `feature/{ticket}`, `backend, frontend`, `.env, .env.local, auth.json, vendor, node_modules` |
| Finish Ticket: delete fully merged branches | on |
| Refresh git status when PhpStorm gets focus | on |

## Configuration: `.workspace.json`

```json
{
  "name": "klant-x",
  "branchPattern": "feature/{ticket}",
  "repos": {
    "backend":  { "url": "git@github.com:org/klant-x-backend.git", "baseBranch": "develop",
                  "copy": [".env", ".env.local", "auth.json", "vendor", "node_modules"] },
    "frontend": { "baseBranch": "main", "copy": [".env", "node_modules"] }
  },
  "tickets": {
    "LOGIN-42-login-form": { "branch": "feature/LOGIN-42/login-form" }
  }
}
```

Open it from the panel (⚙ › *Edit .workspace.json*). `baseBranch` is detected from the remote when you add a repo;
a plain name means the local branch if there is one, else `origin`'s. `tickets` is kept by the plugin: the branch
each ticket was created on (used when a repo is added to it later), removed again on *Finish Ticket*.
Commit the file to a shared place and teammates can register it with *Add Existing Workspace…*.

## Running AI agents in parallel

Each ticket is an isolated folder with its own branch and its own `vendor/` and `node_modules/`, which makes it a
good fit for running Claude Code, Codex or another agent per ticket. Start the agent in the ticket folder
(`klant-x/LOGIN-42/`) so it sees backend and frontend together. A `CLAUDE.md` / `AGENTS.md` in the workspace folder
is shared by every ticket. Tickets still share whatever `.env` points at, such as the database and dev-server ports.
See [docs/PERFORMANCE.md](docs/PERFORMANCE.md).

## Status and roadmap

**0.1 is an early release.** It's built for a macOS + Laravel/Nuxt workflow. Windows and Linux should work (files
are copied instead of cloned), but they haven't been tested, and merging windows into tabs is macOS-only. Please
[report](https://github.com/Tygovanommen/worktree-workspaces/issues/new/choose) anything that breaks.

Next up:

- Setup commands after creating a ticket (`composer install`, `npm ci`, migrations, `herd link`)
- A database name per ticket in its `.env`, so migrations don't collide
- A Jira picker for New Ticket, ticket notes, and tidying up merged tickets
- Treating `.claude/` like `.idea/` when finishing a ticket

Have an idea? [Start a discussion](https://github.com/Tygovanommen/worktree-workspaces/discussions).

## Feedback and support

This plugin is free and open source. If it saves you time:

- ⭐ **Star the repo.** It's the main way other developers find it.
- 🐞 **Report bugs** with **Tools › Workspaces › Report an Issue…**. It opens a GitHub issue with your PhpStorm and
  plugin versions filled in.
- 💬 **Ask questions or share how you use it** in [Discussions](https://github.com/Tygovanommen/worktree-workspaces/discussions).
- 🛠️ **Contribute.** See [CONTRIBUTING.md](CONTRIBUTING.md).

## Building from source

All you need is PhpStorm 2026.2+. The build uses its bundled Java runtime and jars: no Gradle, no downloads.

```
./build.sh             # runs the core tests, then builds build/distributions/worktree-workspaces-<version>.zip
tools/api-check.sh     # flags internal, experimental or deprecated PhpStorm APIs
```

`core/` is plain Java (config, git worktree operations, copying, `.idea` preparation) and is tested against real git
repos. `ide/` is the PhpStorm layer. [`CLAUDE.md`](CLAUDE.md) has the code map and design rules, and
[`docs/PLAN.md`](docs/PLAN.md) has the original plan and decisions.

## License

[MIT](LICENSE). Made by [Netonic](https://netonic.nl).
