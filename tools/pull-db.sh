#!/bin/bash
# Pull the glucose database off the phone into ./data/ for local analysis.
#
# Requires: phone reachable over ADB (wireless: `adb connect <ip:port>` —
# port shown on the phone's Wireless debugging screen; it rotates).
# Works because the app is a debug build (`run-as` access).
#
# Usage: tools/pull-db.sh [adb-serial]
set -euo pipefail

PKG=com.geoffchan.glucosewidget
DIR="$(cd "$(dirname "$0")/.." && pwd)/data"
mkdir -p "$DIR"

SERIAL_ARG=()
[ $# -ge 1 ] && SERIAL_ARG=(-s "$1")

for f in glucose.db glucose.db-wal glucose.db-shm; do
  adb "${SERIAL_ARG[@]}" exec-out run-as $PKG cat "databases/$f" > "$DIR/$f" 2>/dev/null || rm -f "$DIR/$f"
done

[ -s "$DIR/glucose.db" ] || { echo "pull failed — is the phone connected? (adb devices)"; exit 1; }

sqlite3 "$DIR/glucose.db" \
  "PRAGMA wal_checkpoint(TRUNCATE);
   SELECT 'readings: ' || COUNT(*) || '  (' ||
          COALESCE(datetime(MIN(timestampMs)/1000,'unixepoch','localtime'),'-') || ' → ' ||
          COALESCE(datetime(MAX(timestampMs)/1000,'unixepoch','localtime'),'-') || ')'
   FROM readings;
   SELECT 'journal entries: ' || COUNT(*) FROM journal;"

echo "database at $DIR/glucose.db"
