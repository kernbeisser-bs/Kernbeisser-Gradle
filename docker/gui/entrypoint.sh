#!/usr/bin/env bash
set -euo pipefail

DIST=/opt/kernbeisser-dist
APP=/app

if [[ ! -f "$DIST/kernbeisser.jar" ]]; then
  echo "ERROR: $DIST/kernbeisser.jar not found. Run 'make build' on the host first." >&2
  exit 1
fi

Xvfb "$DISPLAY" -screen 0 "${SCREEN_GEOMETRY:-1600x1000x24}" -nolisten tcp &
for _ in $(seq 50); do [[ -e /tmp/.X11-unix/X${DISPLAY#:} ]] && break; sleep 0.1; done
fluxbox >/dev/null 2>&1 &
x11vnc -display "$DISPLAY" -forever -shared -nopw -quiet -rfbport 5900 >/dev/null 2>&1 &
websockify --web /usr/share/novnc 6080 localhost:5900 >/dev/null 2>&1 &

# The app writes config.json and output folders, so run it from a writable copy.
rm -rf "$APP" && cp -r "$DIST" "$APP" && cd "$APP"
cat > config.json <<EOF
{
  "dbAccess": {
    "url": "${DB_URL}",
    "username": "${DB_USER}",
    "password": "${DB_PASSWORD}",
    "encoding": "UTF-8"
  },
  "reports": {
    "reportDirectory": { "path": "reports" },
    "outputDirectory": { "path": "output" }
  }
}
EOF

echo "Kernbeisser GUI: http://localhost:${NOVNC_PORT:-6080}/vnc.html?autoconnect=1&resize=remote"
echo "Debugger (JDWP): localhost:5005 (suspend=${JDWP_SUSPEND:-n})"

exec java \
  -Xmx1024m \
  -agentlib:jdwp=transport=dt_socket,server=y,suspend="${JDWP_SUSPEND:-n}",address=*:5005 \
  -splash:KernbeisserSplash.png \
  -javaagent:security.jar \
  -jar kernbeisser.jar \
  -configFile:"$APP/config.json"
