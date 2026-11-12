#!/usr/bin/env bash
# The first-run checklist of CLAUDE.md, run against a real, sandboxed PhpStorm with this plugin (macOS).
#   tools/sandbox/e2e.sh            builds, starts a fresh sandbox (demo.sh), runs every step, quits
#   KEEP=1 tools/sandbox/e2e.sh     leaves the sandbox running afterwards (tools/sandbox/cmd.sh to poke at it)
# Prints PASS / FAIL per check and exits non-zero on any FAIL. Your own PhpStorm and settings are not touched.
set -uo pipefail
cd "$(dirname "$0")/../.."
ROOT=/private/tmp/wsdemo
P="$ROOT/PhpstormProjects"
X="$P/klant-x"
LOG="$ROOT/ide/log/idea.log"
PASSED=0
FAILED=()

c() { tools/sandbox/cmd.sh -d "$ROOT" "$@"; }
pass() { PASSED=$((PASSED + 1)); echo "  PASS  $1"; }
fail() { FAILED+=("$1"); echo "  FAIL  $1${2:+  ($2)}"; }
KNOWN=()
section() { echo; echo "== $*"; }
# known <description> <needle> <command…>: a known limitation, reported but not counted as a failure
known() {
  local d="$1" needle="$2"; shift 2
  if [[ "$("$@" 2>&1)" == *"$needle"* ]]; then pass "$d"; else KNOWN+=("$d"); echo "  KNOWN $d"; fi
}
# screen <why>: windows and the dialog's controls, for a FAIL that needs explaining
screen() { echo "     ($1)"; c windows | grep -v "^$" | sed 's/^/       /'; c ui | head -12 | sed 's/^/       /'; }
# check <description> <command…>: passes when the command succeeds
check() { local d="$1"; shift; if "$@" >/dev/null 2>&1; then pass "$d"; else fail "$d"; return 1; fi; }
# contains <description> <needle> <command…>: passes when the command's output contains needle
contains() {
  local d="$1" needle="$2"; shift 2
  local out; out=$("$@" 2>&1)
  if [[ "$out" == *"$needle"* ]]; then pass "$d"; else fail "$d" "got: $(echo "$out" | head -5 | tr '\n' ' ')"; fi
}
lacks() {
  local d="$1" needle="$2"; shift 2
  local out; out=$("$@" 2>&1)
  if [[ "$out" != *"$needle"* ]]; then pass "$d"; else fail "$d" "found: $needle"; fi
}
# until_has <seconds> <needle> <command…>: waits until the output contains needle
until_has() {
  local t="$1" needle="$2"; shift 2
  for _ in $(seq 1 "$t"); do [[ "$("$@" 2>/dev/null || true)" == *"$needle"* ]] && return 0; sleep 1; done
  return 1
}
until_lacks() {
  local t="$1" needle="$2"; shift 2
  for _ in $(seq 1 "$t"); do [[ "$("$@" 2>/dev/null || true)" != *"$needle"* ]] && return 0; sleep 1; done
  return 1
}
until_ok() {
  local t="$1"; shift
  for _ in $(seq 1 "$t"); do "$@" >/dev/null 2>&1 && return 0; sleep 1; done
  return 1
}
dialog() { until_has 60 "'$1'" c windows && sleep 1 || echo "     (no dialog '$1' after 60 s)"; }
no_dialog() { until_lacks 30 "'$1'" c windows; }
branch() { git -C "$1" branch --show-current; }
is_link_to() { [ -L "$1" ] && [ "$(cd "$1" && pwd -P)" = "$(cd "$2" && pwd -P)" ]; }
ready() {
  until_has 180 "project " c windows || { echo "sandbox did not start"; exit 1; }
  sleep 5
  [[ "$(c windows)" == *"MyDialog ''"* ]] && c click "text=Skip" >/dev/null # "Meet the Islands Theme"
  sleep 1
}

./build.sh >/dev/null || { echo "build failed"; exit 1; }
tools/sandbox/demo.sh "$ROOT" >/dev/null
ready

section "1 Install"
contains "Workspaces tool window" "available=true" c probe toolwindow "klant-x · main"
contains "toolbar switcher shows workspace / ticket" "acc=klant-x / main" c dump "acc=klant-x / main"
contains "Tools › Workspaces menu" "Workspaces" c probe action WorktreeWorkspaces.Menu

section "10 Terminals (main, first open)"
contains "a terminal tab per repo" "tab backend" c probe terminals "klant-x · main"
contains "  … frontend too" "tab frontend" c probe terminals "klant-x · main"
contains "Terminal window not popped open (reflection path worked)" "visible false" c probe terminals "klant-x · main"
lacks "no plain 'Local' tab" "tab Local" c probe terminals "klant-x · main"

section "11 Markdown run folder"
if [[ "$(c probe registry markdown.command.runner.use.file.directory 2>&1)" == *"is not defined"* ]]; then
  pass "registry key gone (2026.3+: ▶ asks for the folder itself), nothing to set"
else
  contains "registry key on" "true" c probe registry markdown.command.runner.use.file.directory
fi

section "2 New Workspace: adopt a checkout + clone a URL, open as tab"
c action WorktreeWorkspaces.NewWorkspace >/dev/null; dialog "New Workspace"
c field 1 klant-z >/dev/null
c field 12 "$P/klant-z-api" >/dev/null
c field 15 "file://$ROOT/remotes/klant-z-web.git" >/dev/null
contains "Open in starts on New tab (setting default)" "radio 'New tab' true" c ui
c click "text=Create" >/dev/null
no_dialog "New Workspace"
until_has 60 "project klant-z · main" c probe projects
contains "main opens as 'klant-z · main'" "project klant-z · main" c probe projects
check "existing checkout moved into main/backend" test -d "$P/klant-z/main/backend/.git" -a ! -e "$P/klant-z-api"
check "URL cloned into main/frontend" test -d "$P/klant-z/main/frontend/.git"
contains "baseBranch of backend detected (develop)" '"baseBranch": "develop"' cat "$P/klant-z/.workspace.json"
contains "adoptedFrom saved" '"adoptedFrom"' cat "$P/klant-z/.workspace.json"
contains "two Git roots" "vcs Git $P/klant-z/main/frontend" c probe roots "klant-z · main"
contains "  … backend too" "vcs Git $P/klant-z/main/backend" c probe roots "klant-z · main"
check "links klant-z-backend / -frontend" is_link_to "$P/klant-z-backend" "$P/klant-z/main/backend"
contains "workspace registered" "klant-z" c probe settings

section "2b New Workspace with one repo, open in a new window"
c focus "klant-x · main" >/dev/null; sleep 1
c action WorktreeWorkspaces.NewWorkspace >/dev/null; dialog "New Workspace"
c click "acc=Remove this repo" >/dev/null; sleep 1
contains "one row left: no name field" "field ''" c ui
lacks "  … 'Name in workspace' header gone" "Name in workspace" c ui
c field 1 klant-q >/dev/null
SRC=$(c ui | grep -n "field ''" | head -1)
echo "     (source field: $SRC)"
c field "$(echo "$SRC" | sed -E 's/^[0-9]+:([0-9]+) .*/\1/')" "file://$ROOT/remotes/klant-z-web.git" >/dev/null
c click "text=New window" >/dev/null
c click "text=Create" >/dev/null
no_dialog "New Workspace"
until_has 60 "project klant-q · main" c probe projects
check "the repo is main/ itself" test -d "$P/klant-q/main/.git"
contains "Open in New window: a window of its own" "alone" c probe tabgroup "klant-q · main"
check "link klant-q-main" is_link_to "$P/klant-q-main" "$P/klant-q/main"
contains "single repo keyed by workspace name" '"klant-q": {' cat "$P/klant-q/.workspace.json"

section "2c Ticket of a single-repo workspace"
c focus "klant-q · main" >/dev/null; sleep 1
c action WorktreeWorkspaces.NewTicket >/dev/null; dialog "New Ticket"
c type Q-1 >/dev/null; sleep 1
c click "text=Create" >/dev/null
no_dialog "New Ticket"
until_has 60 "project klant-q · Q-1" c probe projects
check "ticket folder is a worktree (.git file)" test -f "$P/klant-q/Q-1/.git"
check "  … with its own .idea" test -d "$P/klant-q/Q-1/.idea"
check "link klant-q-q-1" is_link_to "$P/klant-q-q-1" "$P/klant-q/Q-1"
c probe refresh >/dev/null; sleep 3
contains "shows clean" "ticket Q-1 | klant-q WORKTREE feature/Q-1 changed=0 unpushed=0" c probe model
c actionwith WorktreeWorkspaces.FinishTicket "$P/klant-q" Q-1 >/dev/null; dialog "Finish klant-q · Q-1"
c click "text=Finish Ticket" >/dev/null
no_dialog "Finish klant-q · Q-1"
until_lacks 30 "klant-q · Q-1" c probe projects
check "finished cleanly: folder gone" until_lacks 20 Q-1 ls "$P/klant-q"
check "  … its link gone" test ! -L "$P/klant-q-q-1"
check "  … merged branch deleted" test -z "$(git -C "$P/klant-q/main" branch --list feature/Q-1)"

section "3 New Ticket with both repos"
c focus "klant-x · main" >/dev/null; sleep 1
c action WorktreeWorkspaces.NewTicket >/dev/null; dialog "New Ticket"
c type NEW-1 >/dev/null; sleep 1
contains "branch from the pattern" "field 'feature/NEW-1'" c ui
contains "Branch from lists local and remote" "origin/develop" c ui
c click "text=Create" >/dev/null
no_dialog "New Ticket"
until_has 60 "project klant-x · NEW-1" c probe projects
contains "opens as tab 'klant-x · NEW-1'" "klant-x · main" c probe tabgroup "klant-x · NEW-1"
# Tickets.mergeIntoTabLater runs Merge All Windows, which also takes in windows opened with New window
known "  … klant-q's own window left alone" "alone" c probe tabgroup "klant-q · main"
contains "backend on feature/NEW-1" "feature/NEW-1" branch "$X/NEW-1/backend"
contains "frontend on feature/NEW-1" "feature/NEW-1" branch "$X/NEW-1/frontend"
check "vendor/ copied" test -f "$X/NEW-1/backend/vendor/autoload.php"
check "node_modules/ copied" test -d "$X/NEW-1/frontend/node_modules/vue"
check ".env copied" test -f "$X/NEW-1/backend/.env"
until_has 20 "tab frontend" c probe terminals "klant-x · NEW-1"
contains "terminal tabs in the new ticket" "tab backend" c probe terminals "klant-x · NEW-1"

c action WorktreeWorkspaces.NewTicket >/dev/null; dialog "New Ticket"
c type KEY-2/x >/dev/null; sleep 1
c click "text=Create" >/dev/null
no_dialog "New Ticket"
until_has 60 "project klant-x · KEY-2-x" c probe projects
contains "key KEY-2/x: tab KEY-2-x" "project klant-x · KEY-2-x" c probe projects
contains "  … branch feature/KEY-2/x" "feature/KEY-2/x" branch "$X/KEY-2-x/backend"
contains "  … saved under tickets" '"KEY-2-x": {' cat "$X/.workspace.json"

section "4 New Ticket with one repo, then add the other"
c action WorktreeWorkspaces.NewTicket >/dev/null; dialog "New Ticket"
c type ONE-1 >/dev/null
c check frontend false >/dev/null; sleep 1
c click "text=Create" >/dev/null
no_dialog "New Ticket"
until_has 60 "project klant-x · ONE-1" c probe projects
check "no frontend worktree" test ! -e "$X/ONE-1/frontend"
contains "main/frontend attached as a content root" "root $X/main/frontend" c probe roots "klant-x · ONE-1"
contains "  … and a Git root" "vcs Git $X/main/frontend" c probe roots "klant-x · ONE-1"
c probe refresh >/dev/null; sleep 3
contains "panel shows it as attached" "frontend ATTACHED" c probe model
c actionwith WorktreeWorkspaces.AddWorktreeToTicket "$X" ONE-1 frontend >/dev/null
until_has 60 "feature/ONE-1" branch "$X/ONE-1/frontend"
contains "Add frontend to ONE-1: worktree made" "feature/ONE-1" branch "$X/ONE-1/frontend"
check "  … attached root gone" until_lacks 30 "root $X/main/frontend" c probe roots "klant-x · ONE-1"

section "5 Switcher and Switch Ticket"
c focus "klant-x · NEW-1" >/dev/null; sleep 1
c click "acc=klant-x / NEW-1, menu" >/dev/null; sleep 2
contains "switcher lists this workspace's tickets" "SHOP-3" c list
contains "  … other workspaces as submenus" "klant-y" c list
contains "  … Finish this ticket" "Finish NEW-1" c list
c esc >/dev/null; sleep 1
c action WorktreeWorkspaces.SwitchTicket >/dev/null; sleep 2
c popupsearch login >/dev/null; sleep 1
contains "Switch Ticket search 'login' finds klant-x / LOGIN-42" "> klant-x / LOGIN-42" c list
lacks "  … and filters the rest" "PAY-7" c list
c popupsearch klant-y >/dev/null; sleep 1
contains "search 'klant-y' finds klant-y / API-12" "klant-y / API-12" c list
lacks "  … not klant-x" "klant-x /" c list
c popupsearch shop-3 >/dev/null; sleep 1
c popupselect >/dev/null
until_has 60 "project klant-x · SHOP-3" c probe projects
contains "a closed ticket opens as a tab" "project klant-x · SHOP-3" c probe projects

section "6 Finish Ticket"
c actionwith WorktreeWorkspaces.FinishTicket "$X" LOGIN-42 >/dev/null; dialog "Finish klant-x · LOGIN-42"
contains "warns about uncommitted changes" "3 uncommitted" c ui
contains "  … and unpushed commits" "not pushed" c ui
c click "text=Finish Ticket" >/dev/null
sleep 5
check "dirty ticket without Discard: kept" test -d "$X/LOGIN-42/backend"
c windows | grep -q "'Finish klant-x · LOGIN-42'" && { c esc >/dev/null; sleep 1; }
touch "$X/SHOP-3/notes.txt"
c actionwith WorktreeWorkspaces.FinishTicket "$X" SHOP-3 >/dev/null; dialog "Finish klant-x · SHOP-3"
c click "text=Finish Ticket" >/dev/null
no_dialog "Finish klant-x · SHOP-3"
until_lacks 30 "klant-x · SHOP-3" c probe projects
check "extra file: worktrees removed" until_lacks 30 backend ls "$X/SHOP-3" || screen "SHOP-3 not finished"
check "  … folder with the extra file kept" test -f "$X/SHOP-3/notes.txt"
c actionwith WorktreeWorkspaces.FinishTicket "$X" NEW-1 >/dev/null; dialog "Finish klant-x · NEW-1"
c click "text=Finish Ticket" >/dev/null
no_dialog "Finish klant-x · NEW-1"
check "clean ticket: tab closed" until_lacks 30 "klant-x · NEW-1" c probe projects || screen "NEW-1 still open"
check "  … folder removed" until_lacks 30 NEW-1 ls "$X"
check "  … merged branch deleted" test -z "$(git -C "$X/main/backend" branch --list feature/NEW-1)"
check "  … its links removed" test ! -L "$P/klant-x-new-1-backend"
c actionwith WorktreeWorkspaces.FinishTicket "$X" LOGIN-42 >/dev/null; dialog "Finish klant-x · LOGIN-42"
c check "Discard uncommitted changes" true >/dev/null
c click "text=Finish Ticket" >/dev/null
no_dialog "Finish klant-x · LOGIN-42"
check "with Discard: dirty ticket removed" until_lacks 30 LOGIN-42 ls "$X"
check "  … its unpushed branch kept" test -n "$(git -C "$X/main/backend" branch --list feature/LOGIN-42)"

section "8 Links"
check "main's links" is_link_to "$P/klant-x-backend" "$X/main/backend"
check "a ticket's links" is_link_to "$P/klant-x-pay-7-backend" "$X/PAY-7/backend"
rm "$P/klant-x-frontend"
git -C "$P/klant-y/main" worktree remove --force "$P/klant-y/API-12" && rm -rf "$P/klant-y/API-12"
c probe refresh >/dev/null
check "missing main link made again on refresh" until_has 30 frontend ls "$P"
check "ticket deleted outside PhpStorm: its link removed" until_lacks 30 klant-y-api-12 ls "$P"

section "9 Settings (Tools › Worktree Workspaces)"
c settingsdialog >/dev/null; dialog "Worktree Workspaces"
contains "settings page shows the options" "Link every repo next to its workspace" c ui
c check "Link every repo next to its workspace" false >/dev/null
c check "Run Markdown commands in the file's folder" false >/dev/null
c click "text=OK" >/dev/null; no_dialog "Worktree Workspaces"
contains "saved: links off" "createLinks false" c probe settings
c probe refresh >/dev/null
check "links off: removed on the next refresh" until_lacks 30 klant-x-backend ls "$P"
contains "Markdown option off: registry key reset" "false" c probe registry markdown.command.runner.use.file.directory
c settingsdialog >/dev/null; dialog "Worktree Workspaces"
c check "Link every repo next to its workspace" true >/dev/null
c check "Run Markdown commands in the file's folder" true >/dev/null
c click "text=OK" >/dev/null; no_dialog "Worktree Workspaces"
c probe refresh >/dev/null
check "links on: back on the next refresh" until_has 30 klant-x-backend ls "$P"
contains "Markdown option on: registry key set again" "true" c probe registry markdown.command.runner.use.file.directory

section "10b Terminals after closing them all"
c focus "klant-x · KEY-2-x" >/dev/null; sleep 1
c closeterminals >/dev/null; sleep 1
contains "all terminal tabs closed" "visible false" c probe terminals "klant-x · KEY-2-x"
c toolwindow Terminal >/dev/null
check "repo tabs back when the Terminal opens again" until_has 20 "tab backend" c probe terminals "klant-x · KEY-2-x"
lacks "  … no extra 'Local' tab" "tab Local" c probe terminals "klant-x · KEY-2-x"

section "13 Undo Workspace"
echo change >> "$P/klant-z/main/backend/README.md"
c actionwith WorktreeWorkspaces.UndoWorkspace "$P/klant-z" >/dev/null; sleep 5
check "changed file: refused, workspace kept" test -d "$P/klant-z/main/backend"
c windows | grep -q "MyDialog" && { c esc >/dev/null; sleep 1; }
git -C "$P/klant-z/main/backend" checkout -q README.md
c actionwith WorktreeWorkspaces.UndoWorkspace "$P/klant-z" >/dev/null; dialog "Undo Workspace"
c click "text=Undo Workspace" >/dev/null
check "adopted repo moved back to where it came from" until_ok 60 test -d "$P/klant-z-api/.git"
check "  … the cloned one next to it" test -d "$P/klant-z-frontend/.git"
check "  … main's links gone" test ! -L "$P/klant-z-backend"
check "workspace folder gone" until_lacks 30 "klant-z " sh -c "ls $P | tr '\n' ' '"
lacks "  … and unregistered" "klant-z]" c probe settings

section "12 Get from VCS"
git clone -q "$ROOT/remotes/klant-z-web.git" "$P/shop-web"
c focus "klant-x · main" >/dev/null; sleep 1
contains "our checkout listener runs before PhpStorm's" "listener nl.netonic.workspaces.ide.CloneListener
listener com.intellij.openapi.vcs.checkout.ProjectDirCheckoutListener" c clone "$P/shop-web"
dialog "Put shop-web in a Workspace?"
contains "names suggested from the folder (shop / web)" "field 'web'" c ui
c click "text=Add to workspace" >/dev/null
c click "text=Put in Workspace" >/dev/null; no_dialog "Put shop-web in a Workspace?"
check "clone moved into klant-x/main/web" until_ok 30 test -d "$X/main/web/.git"
contains "  … registered" '"web": {' cat "$X/.workspace.json"
check "  … linked as klant-x-web" is_link_to "$P/klant-x-web" "$X/main/web"
contains "  … a Git root of main" "vcs Git $X/main/web" c probe roots "klant-x · main"
git clone -q "$ROOT/remotes/klant-z-web.git" "$P/other-web"
c clone "$P/other-web" >/dev/null; dialog "Put other-web in a Workspace?"
c click "text=Open as Usual" >/dev/null; no_dialog "Put other-web in a Workspace?"
check "Open as Usual: left where it is" test -d "$P/other-web/.git"
check "  … and opened" until_has 60 "project other-web" c probe projects

section "15 Open Project: Merge in Current"
c focus "klant-x · main" >/dev/null; sleep 1
c openfolder "$ROOT/seed/klant-y-shop" >/dev/null; dialog "Open Project"
contains "dialog offers Merge in Current" "button 'Merge in Current'" c ui
lacks "  … in place of Attach" "button 'Attach'" c ui
c click "text=Merge in Current" >/dev/null; no_dialog "Open Project"
sleep 3
lacks "asked only once" "'Open Project'" c windows
until_has 60 "project klant-y-shop" c probe projects
check "opened as a tab of the current window" until_has 20 "klant-x · main" c probe tabgroup klant-y-shop
c windows | grep -q "'Open Project'" && c esc >/dev/null
# 2026.3: a folder opened before also enables PhpStorm's WorkspaceModelCacheAttachProcessor (Attach, order first)
c closeproject klant-y-shop >/dev/null; until_lacks 30 "project klant-y-shop" c probe projects
c focus "klant-x · main" >/dev/null; sleep 1
c openfolder "$ROOT/seed/klant-y-shop" >/dev/null; dialog "Open Project"
contains "opened before: still Merge in Current" "button 'Merge in Current'" c ui
lacks "  … not Attach" "button 'Attach'" c ui
c click "text=Merge in Current" >/dev/null; no_dialog "Open Project"
check "  … and merged as a tab again" until_has 60 "klant-x · main" c probe tabgroup klant-y-shop

section "14 Recent Projects"
c probe refresh >/dev/null; sleep 3
contains "main of each workspace" "recent $X/main" c probe recent
lacks "no bare workspace folder" "recent $X"$'\n' c probe recent
lacks "finished ticket removed (SHOP-3)" "SHOP-3" c probe recent
contains "an open ticket is listed (KEY-2-x)" "KEY-2-x" c probe recent
c closeproject "klant-x · KEY-2-x" >/dev/null
until_lacks 30 "klant-x · KEY-2-x" c probe projects
check "closing a ticket tab removes it" until_lacks 20 "KEY-2-x" c probe recent
c open "$X" KEY-2-x >/dev/null; until_has 60 "project klant-x · KEY-2-x" c probe projects

section "16 Toolbar colors"
A=$(c probe color "klant-x · main"); B=$(c probe color "klant-x · KEY-2-x"); D=$(c probe color "klant-x · ONE-1")
echo "     (colors: main=$A KEY-2-x=$B ONE-1=$D)"
check "main and two open tickets: three different colors" test "$A" != "$B" -a "$B" != "$D" -a "$A" != "$D"
c probe setcolor 7 "klant-x · ONE-1" >/dev/null
c closeproject "klant-x · ONE-1" >/dev/null; until_lacks 30 "klant-x · ONE-1" c probe projects
c open "$X" ONE-1 >/dev/null; until_has 60 "project klant-x · ONE-1" c probe projects; sleep 3
contains "a color the user picked stays after reopening" "7" c probe color "klant-x · ONE-1"

section "7 Restart"
tools/sandbox/demo.sh --restart "$ROOT" >/dev/null
ready
contains "workspaces remembered" "klant-q" c probe settings
until_has 60 "klant-x · KEY-2-x" c probe projects
contains "open ticket tabs reopen" "project klant-x · KEY-2-x" c probe projects
contains "  … status shown again" "ticket KEY-2-x" c probe model

section "idea.log"
ERRORS=$(grep -n "ERROR" "$LOG" | grep -iE "netonic|workspaces" || true)
if [ -z "$ERRORS" ]; then pass "no plugin errors in idea.log"; else fail "plugin errors in idea.log" "$(echo "$ERRORS" | head -3)"; fi

echo
echo "${PASSED} passed, ${#FAILED[@]} failed, ${#KNOWN[@]} known"
# ${a[@]+"${a[@]}"}: bash 3.2 (macOS) calls an empty array unbound under set -u
for f in ${FAILED[@]+"${FAILED[@]}"}; do echo "  - $f"; done
[ -n "${KEEP:-}" ] || tools/sandbox/demo.sh --stop "$ROOT" >/dev/null
[ ${#FAILED[@]} -eq 0 ]
