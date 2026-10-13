# Contributing

Bug reports, ideas and pull requests are welcome. For anything bigger than a small fix, open an issue or a
discussion first so we can agree on the approach.

## Build

You only need PhpStorm 2026.2+ installed: the build uses its bundled Java runtime and jars (no Gradle, no download).

```
./build.sh              # core tests + build/distributions/worktree-workspaces-<version>.zip
tools/api-check.sh      # must end with "problems: none"
```

PhpStorm somewhere else? `PHPSTORM_CONTENTS=/path/to/PhpStorm.app/Contents ./build.sh`.
Install the zip with Settings › Plugins › ⚙ › **Install Plugin from Disk…**.

## Ground rules

[`CLAUDE.md`](CLAUDE.md) has the code map and the rules; the short version:

- `core/` stays plain Java with no IntelliJ imports, and every change there gets a case in `CoreTests`.
- No git or file IO on the UI thread.
- Only stable PhpStorm APIs: `tools/api-check.sh` must report no problems.
- The safety rules (never touch `main/`, never discard uncommitted work silently, keep folders with other files)
  are not relaxed without a test.
- Keep `.workspace.json` backward compatible.
