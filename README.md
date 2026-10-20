# Worktree Workspaces

[![Latest release](https://img.shields.io/github/v/release/Tygovanommen/worktree-workspaces?label=download)](https://github.com/Tygovanommen/worktree-workspaces/releases/latest)
[![Tests](https://github.com/Tygovanommen/worktree-workspaces/actions/workflows/tests.yml/badge.svg)](https://github.com/Tygovanommen/worktree-workspaces/actions/workflows/tests.yml)
![PhpStorm 2026.2+](https://img.shields.io/badge/PhpStorm-2026.2%2B-7B5CF5)
[![MIT license](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

![Worktree Workspaces](docs/cover.png)

**One PhpStorm window. One tab per ticket. Backend and frontend side by side.**

Working on several tickets that each touch a backend and a frontend repo usually means stashing, switching branches
and reinstalling dependencies, or juggling a pile of windows. This plugin gives every ticket its own folder with a
[git worktree](https://git-scm.com/docs/git-worktree) of each repo, opened as one tab. Switching tickets is
switching tabs.

## Features

- **A tab per ticket** with all its repos on the ticket's branch.
- **Ready to run:** `.env`, `vendor/` and `node_modules/` are copied from your main checkout (instant on macOS).
- **Only the repos you need:** repos a ticket doesn't touch are shown from `main/` and can be added later.
- **Overview:** a *Workspaces* panel and a toolbar switcher show every ticket with its branch and git state.
- **Safe cleanup:** *Finish Ticket* warns about uncommitted or unpushed work before removing anything.
- **No lock-in:** tickets are plain PhpStorm projects and plain git worktrees.

![A tab per ticket, a switcher in the toolbar, and a panel with git state](docs/plan/ui-mockup.png)

## Install

1. Download the zip from the [latest release](https://github.com/Tygovanommen/worktree-workspaces/releases/latest).
2. PhpStorm › Settings › Plugins › ⚙ › **Install Plugin from Disk…** › pick the zip › restart.

To get updates, add this under Settings › Plugins › ⚙ › **Manage Plugin Repositories…**:
`https://raw.githubusercontent.com/Tygovanommen/worktree-workspaces/main/updatePlugins.xml`

Requires PhpStorm 2026.2 or newer.

## Quick start

1. **Tools › Workspaces › New Workspace…** Pick a name and add your repos (a git URL or an existing checkout).
2. **New Ticket…** from the toolbar switcher or the panel's **+**. Enter `LOGIN-42` and tick the repos it touches.
   A new tab opens on branch `feature/LOGIN-42`.
3. Work, commit, push. When it's merged: right-click the ticket › **Finish Ticket…**

**macOS tip:** set *System Settings › Desktop & Dock › Prefer tabs when opening documents* to **Always** so tickets
stay tabs after a restart.

## How it works

```
~/PhpstormProjects/klant-x/        a workspace
  .workspace.json                  settings: repos, base branches, branch pattern
  main/                            your real clones
    backend/  frontend/
  LOGIN-42/                        a ticket: git worktrees on feature/LOGIN-42
    backend/  frontend/
```

![Each ticket is one folder and one tab, with a worktree per repo it touches](docs/plan/concept.png)

- If the ticket's branch already exists (locally or on `origin`), it is used; otherwise it's created from the base
  branch.
- Next to the workspace the plugin keeps a link per repo (`klant-x-login-42-backend`), so Herd and Valet find
  every ticket.
- Each repo gets its own terminal tab when a ticket opens for the first time, and again when you reopen the
  Terminal after closing all its tabs.
- The ▶ next to a command in a README (`npm install` in `frontend/README.md`) runs it in that README's folder,
  not in the ticket folder.
- *Undo Workspace* (right-click the workspace) moves the repos back out and removes the workspace.

All options are under *Settings › Tools › Worktree Workspaces*.

## Feedback

- ⭐ **Star the repo** if it saves you time.
- 🐞 **Report a bug** with **Tools › Workspaces › Report an Issue…**
- 💬 **Ask a question or share an idea** in [Discussions](https://github.com/Tygovanommen/worktree-workspaces/discussions).

## Contributing

Run `./build.sh` to test and build (only needs PhpStorm installed). See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

[MIT](LICENSE). Made by [Netonic](https://netonic.nl).
