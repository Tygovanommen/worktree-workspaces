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
  uses it; otherwise it branches from the up-to-date base branch.
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
   an existing checkout (moved into `klant-x/main/`). `main` opens as the first tab.
2. **New Ticket…** (toolbar switcher or the panel's **+**). Type `LOGIN-42` and tick the repos it touches. A tab opens
   with fresh worktrees on `feature/LOGIN-42`.
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

Every repo keeps a single `.git` in `main/`, and ticket folders hold worktrees of it. Shared `.idea` settings (PHP
interpreter, code style, run configurations, data sources) are copied from `main/.idea`. `workspace.xml` is never
copied, so tickets stay separate projects.

## Everyday use

| Where | What |
| --- | --- |
| **Workspaces** tool window (left) | Workspaces › tickets › repos with branch, changed files (`3 changed`) and unpushed commits (`↑1`). Double-click to open or focus a ticket; right-click for the rest. |
| Toolbar, next to the project name | `klant-x / LOGIN-42 ▾`: a searchable list of every ticket, plus New Ticket and New Workspace. |
| Tools › Workspaces, or Find Action | Switch Ticket, New Ticket, Finish Ticket, New Workspace, Add Repo. Give **Switch Ticket** a shortcut in Settings › Keymap. |

**New ticket.** The branch comes from the workspace's pattern (`feature/{ticket}`) and can be edited. Per repo the
plugin fetches the base branch, then:

- branch exists locally → worktree on it
- branch exists on `origin` → worktree tracking it
- otherwise → new branch from `origin/<base>`

Run `composer install` / `npm ci` only when the lock files differ from `main`.

**Ticket needs another repo after all.** Right-click the grey *main checkout* row › *Add frontend to PAY-7*.

**Finish ticket.** Lists uncommitted changes and commits that are on no remote, closes the tab, runs
`git worktree remove` per repo and deletes the folder. A folder that still holds other files (notes, scripts) is
kept.

## Configuration: `.workspace.json`

```json
{
  "name": "klant-x",
  "branchPattern": "feature/{ticket}",
  "repos": {
    "backend":  { "url": "git@github.com:org/klant-x-backend.git", "baseBranch": "develop",
                  "copy": [".env", ".env.local", "auth.json", "vendor", "node_modules"] },
    "frontend": { "baseBranch": "main", "copy": [".env", "node_modules"] }
  }
}
```

Open it from the panel (⚙ › *Edit .workspace.json*). `baseBranch` is detected from the remote when you add a repo.
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
