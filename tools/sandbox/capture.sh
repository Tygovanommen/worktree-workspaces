#!/usr/bin/env bash
# Regenerates the docs screenshots from a real PhpStorm running this plugin on fake workspaces.
#   tools/sandbox/capture.sh            → docs/screenshots/*.png
# Builds the plugin, runs demo.sh (sandboxed PhpStorm, your own settings untouched), drives it through the
# screenshot helper and quits it again. macOS only (window tabs, Islands theme as configured in your PhpStorm).
set -euo pipefail
cd "$(dirname "$0")/../.."
ROOT=/private/tmp/wsdemo
P="$ROOT/PhpstormProjects"
OUT="$PWD/docs/screenshots"
C="tools/sandbox/cmd.sh -d $ROOT"
mkdir -p "$OUT"

./build.sh >/dev/null
DEMO_OPEN=klant-x/LOGIN-42 tools/sandbox/demo.sh "$ROOT" >/dev/null

until [[ "$($C windows 2>/dev/null || true)" == *"project "* ]]; do sleep 2; done
sleep 8
$C click "text=Skip" >/dev/null                  # "Meet the Islands Theme"
$C open "$P/klant-x" PAY-7 >/dev/null; sleep 8
$C open "$P/klant-x" main >/dev/null; sleep 8   # opened last: first window tab
for p in "klant-x · main" "klant-x · PAY-7"; do $C focus "$p" >/dev/null; $C closefiles >/dev/null; done

$C focus "klant-x · LOGIN-42" >/dev/null; sleep 1
$C size 300 60 1360 860 >/dev/null
$C closefiles >/dev/null
$C hidetoolwindow Project >/dev/null
$C toolwindow Workspaces >/dev/null
$C file "$P/klant-x/LOGIN-42/backend/app/Http/Controllers/Auth/LoginController.php" >/dev/null
sleep 8
$C notifications >/dev/null; sleep 1
$C shot "$OUT/window.png" frame

$C click "acc=klant-x / LOGIN-42, menu" >/dev/null; sleep 2
$C shot "$OUT/switcher.png" frame
$C key esc >/dev/null; sleep 1

$C action WorktreeWorkspaces.NewTicket >/dev/null; sleep 3
$C type SEARCH-12 >/dev/null; sleep 2
$C shot "$OUT/new-ticket.png" dialog
$C key esc >/dev/null; sleep 1

$C action WorktreeWorkspaces.FinishTicket >/dev/null; sleep 3
$C shot "$OUT/finish-ticket.png" dialog
$C key esc >/dev/null; sleep 1

[ -n "${KEEP:-}" ] || kill "$(cat "$ROOT/ide.pid")"     # KEEP=1: leave it running
echo "screenshots in $OUT"
