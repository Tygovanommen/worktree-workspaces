#!/usr/bin/env bash
# Sends one command to the screenshot helper in the sandboxed PhpStorm and prints its output.
#   tools/sandbox/cmd.sh [-d demo-root] <command...>     e.g. cmd.sh windows / cmd.sh shot /tmp/x.png frame
set -euo pipefail
ROOT=/tmp/wsdemo
if [ "${1:-}" = "-d" ]; then ROOT="$2"; shift 2; fi
DIR="$ROOT/cmd"
mkdir -p "$DIR"
ID="$(date +%s)-$$-$RANDOM"
echo "$*" > "$DIR/$ID.tmp" && mv "$DIR/$ID.tmp" "$DIR/$ID.cmd"
for _ in $(seq 1 300); do
  if [ -f "$DIR/$ID.done" ]; then cat "$DIR/$ID.out"; echo; exit 0; fi
  sleep 0.1
done
echo "timeout: is the sandboxed PhpStorm running?" >&2
exit 1
