#!/usr/bin/env bash
# Boots a real Paper server with NovaGuard installed and checks that the plugin enables without errors.
# Usage: scripts/smoke-test.sh <minecraft-version> <path-to-NovaGuard.jar>
set -euo pipefail

VERSION="$1"
PLUGIN_JAR="$2"
UA="NovaGuard-CI (github.com/novablazebro-cpu/gyser-for-new-pocket-version-)"
WORK="$(mktemp -d)"

BUILDS=$(curl -fsSL -H "User-Agent: $UA" "https://fill.papermc.io/v3/projects/paper/versions/$VERSION/builds")
URL=$(echo "$BUILDS" | jq -r 'first(.[] | select(.channel == "STABLE") | .downloads."server:default".url) // empty')
if [ -z "$URL" ]; then
  URL=$(echo "$BUILDS" | jq -r '.[0].downloads."server:default".url')
fi
echo "Paper download: $URL"
curl -fsSL -H "User-Agent: $UA" -o "$WORK/paper.jar" "$URL"

mkdir -p "$WORK/plugins"
cp "$PLUGIN_JAR" "$WORK/plugins/NovaGuard.jar"
# Test server only: accept the EULA and run offline like the real server.
echo "eula=true" > "$WORK/eula.txt"
printf "online-mode=false\nlevel-type=minecraft\\:flat\ngenerate-structures=false\nspawn-protection=0\n" > "$WORK/server.properties"

cd "$WORK"
mkfifo console
# Open the pipe read+write first: this never blocks, and keeps the pipe open for the server's input.
exec 3<> console
timeout 600 java -Xms512M -Xmx1536M -jar paper.jar --nogui < console > server.log 2>&1 &
SERVER_PID=$!

for _ in $(seq 1 360); do
  if grep -q "Done (" server.log; then break; fi
  if ! kill -0 "$SERVER_PID" 2>/dev/null; then echo "Server exited early"; cat server.log; exit 1; fi
  sleep 1
done
grep -q "Done (" server.log || { echo "Server did not start in time"; cat server.log; exit 1; }

# Exercise the command once from the console.
echo "novaguard info nobody" >&3
sleep 3
echo "stop" >&3
wait "$SERVER_PID" || true
exec 3>&-

echo "----- server log (NovaGuard lines) -----"
grep -i "novaguard" server.log || true

FAIL=0
grep -q "NovaGuard enabled" server.log || { echo "ERROR: NovaGuard did not enable"; FAIL=1; }
if grep -E "Error occurred while enabling NovaGuard|Could not load 'plugins/NovaGuard.jar'|Could not pass event .* to NovaGuard" server.log; then
  FAIL=1
fi
if grep -B2 -A20 -E "Exception" server.log | grep -q "io.github.novablaze.novaguard"; then
  echo "ERROR: exception from NovaGuard code"
  grep -B2 -A20 -E "Exception" server.log
  FAIL=1
fi
if [ "$FAIL" -ne 0 ]; then
  cat server.log
  exit 1
fi
echo "Smoke test passed."
