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

ADB="adb"
[ $# -ge 1 ] && ADB="adb -s $1"

# Refuse to touch the local copy unless the device is actually there; a failed
# `adb exec-out` can write its error text into the file and clobber a good DB.
$ADB get-state >/dev/null 2>&1 || { echo "pull failed — phone not connected (adb devices)"; exit 1; }

TMP="$(mktemp -d)"
for f in glucose.db glucose.db-wal glucose.db-shm; do
  $ADB exec-out run-as $PKG cat "databases/$f" > "$TMP/$f" 2>/dev/null || rm -f "$TMP/$f"
done
head -c 16 "$TMP/glucose.db" 2>/dev/null | grep -q "SQLite format 3" \
  || { echo "pull failed — did not get a SQLite file (is the app installed?)"; rm -rf "$TMP"; exit 1; }
rm -f "$DIR"/glucose.db "$DIR"/glucose.db-wal "$DIR"/glucose.db-shm
mv "$TMP"/glucose.db* "$DIR"/ ; rm -rf "$TMP"

sqlite3 "$DIR/glucose.db" \
  "PRAGMA wal_checkpoint(TRUNCATE);
   SELECT 'readings: ' || COUNT(*) || '  (' ||
          COALESCE(datetime(MIN(timestampMs)/1000,'unixepoch','localtime'),'-') || ' → ' ||
          COALESCE(datetime(MAX(timestampMs)/1000,'unixepoch','localtime'),'-') || ')'
   FROM readings;
   SELECT 'journal entries: ' || COUNT(*) FROM journal;"

echo "database at $DIR/glucose.db"
