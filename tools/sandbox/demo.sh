#!/usr/bin/env bash
# Fake workspaces + a sandboxed PhpStorm with this plugin, for tests (e2e.sh) and the docs screenshots (capture.sh).
#   tools/sandbox/demo.sh [demo-root]             new sandbox; default root /tmp/wsdemo; needs ./build.sh first
#   DEMO_OPEN=klant-x/LOGIN-42 demo.sh            opens that folder first instead of klant-x/main
#   tools/sandbox/demo.sh --restart [demo-root]   quits and starts the same sandbox again, state kept
#                                                 (reinstalls the plugin from build/, so also for a new build)
#   tools/sandbox/demo.sh --probe [demo-root]     recompiles helper/Commands.java + Probe.java (loaded fresh for
#                                                 every command, so no restart needed)
#   tools/sandbox/demo.sh --stop [demo-root]
# Creates <root>/PhpstormProjects/{klant-x,klant-y} and starts PhpStorm with its own config / system / plugins
# under <root>/ide (your own PhpStorm and its settings are not touched; only the license key is copied).
set -euo pipefail
cd "$(dirname "$0")/../.."
MODE=new
case "${1:-}" in --restart|--probe|--stop) MODE="${1#--}"; shift ;; esac
ROOT="${1:-/tmp/wsdemo}"
PS="${PHPSTORM_CONTENTS:-$HOME/Applications/PhpStorm.app/Contents}"
USER_CONFIG="${PHPSTORM_CONFIG:-$HOME/Library/Application Support/JetBrains/PhpStorm2026.2}"
JDK="$PS/jbr/Contents/Home/bin"
ZIP=$(ls build/distributions/worktree-workspaces-*.zip | tail -1)
CP=$(find "$PS/lib" -name '*.jar' | tr '\n' ':')

case "$ROOT" in /tmp/*|/private/tmp/*) ;; *) echo "demo root must be under /tmp" >&2; exit 1 ;; esac
mkdir -p "$ROOT"
ROOT=$(cd "$ROOT" && pwd -P)
IDE="$ROOT/ide"
P="$ROOT/PhpstormProjects"
H="$ROOT/helper-build"

stop() {
  local pid
  pid=$(cat "$ROOT/ide.pid" 2>/dev/null || true)
  if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
    echo "stopping the sandboxed PhpStorm"
    # quit through the helper so settings are saved; a kill (fallback) saves nothing
    rm -f "$ROOT/cmd/"*.cmd
    tools/sandbox/cmd.sh -d "$ROOT" quit >/dev/null 2>&1 || true
    for _ in $(seq 1 60); do kill -0 "$pid" 2>/dev/null || break; sleep 0.5; done
    kill "$pid" 2>/dev/null || true
    while kill -0 "$pid" 2>/dev/null; do sleep 0.5; done
    sleep 2 # the launcher of a new instance would otherwise find the old one's lock and hand over to it
  fi
  rm -f "$ROOT/cmd/"*.cmd
}

probe() {
  rm -rf "$H/commands" && mkdir -p "$H/commands"
  "$JDK/javac" --release 21 -proc:none -nowarn -cp "$CP:build/main" -d "$H/commands" \
    tools/sandbox/helper/Commands.java tools/sandbox/helper/Probe.java
}

install_plugins() {
  rm -rf "$IDE/plugins" "$H/classes"
  mkdir -p "$IDE/plugins/wsdemo-shots/lib" "$H/classes"
  (cd "$IDE/plugins" && unzip -q "$OLDPWD/$ZIP")
  # the test / screenshot helper (tools/sandbox/helper), only ever installed here
  "$JDK/javac" --release 21 -proc:none -nowarn -cp "$CP:build/main" -d "$H/classes" tools/sandbox/helper/ShotHelper.java
  cp -R tools/sandbox/helper/META-INF "$H/classes/"
  (cd "$H/classes" && zip -qr "$IDE/plugins/wsdemo-shots/lib/wsdemo-shots.jar" .)
  probe
}

start() {
  echo "starting sandboxed PhpStorm (log: $IDE/log/idea.log)"
  PHPSTORM_VM_OPTIONS="$IDE/phpstorm.vmoptions" nohup "$PS/MacOS/phpstorm" "$@" >>"$IDE/log/stdout.log" 2>&1 &
  echo $! > "$ROOT/ide.pid"
}

case "$MODE" in
  stop) stop; exit 0 ;;
  probe) probe; exit 0 ;;
  restart) stop; install_plugins; start; exit 0 ;;
esac

stop
rm -rf "$ROOT"
mkdir -p "$ROOT"

tools/sandbox/seed-repos.sh "$ROOT"
"$JDK/java" -cp build/core tools/sandbox/DemoWorkspaces.java "$ROOT"
# for New Workspace (e2e.sh): a remote to clone and an existing checkout to adopt, not in any workspace yet
git clone -q --bare "$ROOT/seed/klant-x-frontend" "$ROOT/remotes/klant-z-web.git"
git clone -q --bare "$ROOT/seed/klant-x-backend" "$ROOT/remotes/klant-z-api.git"
git clone -q "$ROOT/remotes/klant-z-api.git" "$P/klant-z-api"
cp "$P/klant-z-api/.env.example" "$P/klant-z-api/.env"

mkdir -p "$IDE/config/options" "$IDE/system" "$IDE/log"
cp "$USER_CONFIG/phpstorm.key" "$IDE/config/" 2>/dev/null || echo "no phpstorm.key: PhpStorm will ask for a license"
for f in colors.scheme.xml editor-font.xml console-font.xml terminal-font.xml; do
  [ -f "$USER_CONFIG/options/$f" ] && cp "$USER_CONFIG/options/$f" "$IDE/config/options/"
done
cp "$USER_CONFIG/disabled_plugins.txt" "$IDE/config/" 2>/dev/null || true
install_plugins

cat > "$IDE/config/options/worktreeWorkspaces.xml" <<EOF
<application>
  <component name="WorktreeWorkspaces">
    <option name="workspaces">
      <list>
        <option value="$P/klant-x" />
        <option value="$P/klant-y" />
      </list>
    </option>
    <option name="lastParentFolder" value="$P" />
    <option name="ticketsCreated" value="10" />
  </component>
</application>
EOF
cat > "$IDE/config/options/trusted-paths.xml" <<EOF
<application>
  <component name="Trusted.Paths.Settings">
    <option name="TRUSTED_PATHS">
      <list>
        <option value="$ROOT" />
      </list>
    </option>
  </component>
</application>
EOF
cat > "$IDE/config/options/ide.general.xml" <<EOF
<application>
  <component name="GeneralSettings">
    <option name="confirmExit" value="false" />
    <option name="showTipsOnStartup" value="false" />
  </component>
</application>
EOF

{
  cat "$PS/bin/phpstorm.vmoptions"
  echo "-Didea.config.path=$IDE/config"
  echo "-Didea.system.path=$IDE/system"
  echo "-Didea.plugins.path=$IDE/plugins"
  echo "-Didea.log.path=$IDE/log"
  echo "-Didea.trust.all.projects=true"
  echo "-Didea.initially.ask.config=never"
  echo "-Dwsdemo.dir=$ROOT"
  # the panel and dialogs show paths under user.home as ~/…: ~/PhpstormProjects/klant-x
  echo "-Duser.home=$ROOT"
} > "$IDE/phpstorm.vmoptions"

start "$P/${DEMO_OPEN:-klant-x/main}"
