#!/usr/bin/env bash
# Prepares a release: local build + API check as a pre-flight, then points updatePlugins.xml at the new release.
#   tools/release.sh            (after bumping <version> and <change-notes> in plugin.xml)
# Then commit and push, and run Actions › Release › Run workflow on GitHub: it builds the zip, creates tag
# v<version> and publishes the release with the zip as the download.
set -euo pipefail
cd "$(dirname "$0")/.."
VERSION=$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' src/main/resources/META-INF/plugin.xml | head -1)
./build.sh
tools/api-check.sh | tail -1 | grep -q none || { echo "api-check reported problems"; exit 1; }
sed -i.bak -E \
  -e "s#/releases/download/v[^/]+/worktree-workspaces-[^\"]+\.zip#/releases/download/v$VERSION/worktree-workspaces-$VERSION.zip#" \
  -e "s#^( +)version=\"[^\"]+\"#\1version=\"$VERSION\"#" \
  updatePlugins.xml && rm updatePlugins.xml.bak
echo "== ready: commit + push, then GitHub › Actions › Release › Run workflow (publishes v$VERSION)"
