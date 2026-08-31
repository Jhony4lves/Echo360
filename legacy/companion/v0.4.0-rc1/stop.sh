#!/data/data/com.termux/files/usr/bin/bash
cd "$(dirname "$0")"

if [ -f echo360.pid ]; then
  kill "$(cat echo360.pid)" 2>/dev/null || true
  rm -f echo360.pid
fi

termux-wake-unlock 2>/dev/null || true
echo "Echo360 Companion parado."
