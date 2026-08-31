#!/data/data/com.termux/files/usr/bin/bash
set -e
cd "$(dirname "$0")"

termux-wake-lock 2>/dev/null || true

# Encerra apenas instancias anteriores deste proprio Companion.
if [ -f echo360.pid ]; then
  kill "$(cat echo360.pid)" 2>/dev/null || true
  rm -f echo360.pid
fi

nohup node "$PWD/server.js" > "$PWD/echo360.log" 2>&1 &
echo $! > "$PWD/echo360.pid"
sleep 2

URL="http://127.0.0.1:8760"
VERSION="$(node -p "require('./package.json').version")"
echo "Echo360 v$VERSION: $URL"
echo "Log: $PWD/echo360.log"

if command -v termux-open-url >/dev/null 2>&1; then
  termux-open-url "$URL" >/dev/null 2>&1 || true
fi
