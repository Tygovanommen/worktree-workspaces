# Contributing

Bug reports, ideas and pull requests are welcome. For bigger changes, open an issue or discussion first.

## Build

You only need PhpStorm 2026.2+ installed (no Gradle).

```
./build.sh              # tests + build/distributions/worktree-workspaces-<version>.zip
tools/api-check.sh      # must end with "problems: none"
```

PhpStorm somewhere else? `PHPSTORM_CONTENTS=/path/to/PhpStorm.app/Contents ./build.sh`.

## Rules

See [`CLAUDE.md`](CLAUDE.md) for the code map. In short:

- `core/` is plain Java without IntelliJ imports; every change there gets a test in `CoreTests`.
- No git or file work on the UI thread.
- Only stable PhpStorm APIs.
- Never loosen the safety rules (don't touch `main/`, never silently discard work) without a test.
- Keep `.workspace.json` backward compatible.
