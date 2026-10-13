#!/usr/bin/env bash
# Offline build against an installed PhpStorm (no Gradle / internet needed).
#   PHPSTORM_CONTENTS=/path/to/PhpStorm.app/Contents JAVA_HOME=/path/to/jdk25 ./build.sh
# Produces build/distributions/worktree-workspaces-<version>.zip  (Settings › Plugins › ⚙ › Install Plugin from Disk…)
set -euo pipefail
cd "$(dirname "$0")"
PS="${PHPSTORM_CONTENTS:-$HOME/Applications/PhpStorm.app/Contents}"
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
JAR="${JAVA_HOME:+$JAVA_HOME/bin/}jar"
VERSION=$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' src/main/resources/META-INF/plugin.xml | head -1)

CP=$(find "$PS/lib" "$PS/plugins/vcs-git/lib" -name '*.jar' | tr '\n' ':')
rm -rf build && mkdir -p build/core build/test build/main build/plugin/worktree-workspaces/lib build/distributions

echo "== core + tests"
"$JAVAC" --release 21 -d build/core $(find src/main/java/nl/netonic/workspaces/core -name '*.java')
"$JAVAC" --release 21 -cp build/core -d build/test $(find src/test/java -name '*.java')
"$JAVA" -cp build/core:build/test nl.netonic.workspaces.core.CoreTests

echo "== plugin"
"$JAVAC" --release 21 -proc:none -Xlint:all,-serial,-processing,-path -cp "$CP" -d build/main $(find src/main/java -name '*.java')
cp -R src/main/resources/. build/main/
(cd build/main && "$JAR" --create --file "../plugin/worktree-workspaces/lib/worktree-workspaces-$VERSION.jar" .)
(cd build/plugin && zip -qr "../distributions/worktree-workspaces-$VERSION.zip" worktree-workspaces)
echo "== built build/distributions/worktree-workspaces-$VERSION.zip"
