#!/usr/bin/env bash
# Mini plugin verifier: lists the PhpStorm jars the plugin uses and flags Internal / Experimental /
# ScheduledForRemoval / Deprecated APIs and members that don't exist in the target build.
# Run after ./build.sh:
#   PHPSTORM_CONTENTS=~/Applications/PhpStorm.app/Contents JAVA_HOME=<jdk25> tools/api-check.sh
set -euo pipefail
cd "$(dirname "$0")/.."
PS="${PHPSTORM_CONTENTS:-$HOME/Applications/PhpStorm.app/Contents}"
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
ASM="$PS/lib/intellij.libraries.asm.jar"
CP=$(find "$PS/lib" "$PS/plugins/vcs-git/lib" -name '*.jar' | tr '\n' ':')

mkdir -p build/tools
"$JAVAC" -cp "$ASM" -d build/tools tools/ApiCheck.java
python3 -c "import json,sys; print('\n'.join(json.load(open(sys.argv[1]))['launch'][0]['bootClassPathJarNames']))" \
  "$PS/Resources/product-info.json" > build/tools/boot.txt
"$JAVA" -cp "$ASM:build/tools" ApiCheck build/main "$CP" build/tools/boot.txt
