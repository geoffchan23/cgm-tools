#!/bin/bash
# End-of-week report run, start to finish:
#   1. find the phone's wireless-debugging port on the LAN and connect
#   2. pull the database (also our backup)
#   3. generate analysis/report.html for the last 7 days
#   4. push it into the app's Reports screen
# Prints the artifact publish hint at the end; publishing is done by Claude
# Code (the Artifact tool), not by this script.
#
# Usage: tools/weekly-report.sh [--days N]
# Exit 3 = phone not reachable (nothing else attempted).
set -euo pipefail
cd "$(dirname "$0")/.."
DAYS=7
[ "${1:-}" = "--days" ] && DAYS=$2

PHONE_IP=10.0.0.216
export PATH="$PATH:/opt/homebrew/bin:$HOME/Library/Android/sdk/platform-tools"

# The wireless-debugging port rotates; mDNS advertises the current one.
# (`adb mdns services` never exits on its own; perl's alarm is macOS's `timeout`.)
PORT=$( (perl -e 'alarm 5; exec @ARGV' adb mdns services 2>/dev/null || true) \
        | awk -v ip="$PHONE_IP" '$3 ~ ip":" {split($3,a,":"); print a[2]; exit}')
if [ -z "$PORT" ]; then
  echo "phone not found on the network (is wireless debugging on and the Mac on the same Wi-Fi?)"; exit 3
fi
SERIAL="$PHONE_IP:$PORT"
adb connect "$SERIAL" | grep -q connected || { echo "adb connect $SERIAL failed"; exit 3; }

tools/pull-db.sh "$SERIAL"
cp data/glucose.db "data/glucose-backup-$(date +%F).db"
python3 analysis/report.py --days "$DAYS"
read -r FIRST LAST < analysis/report.range
tools/push-report.sh "$SERIAL" analysis/report.html "$FIRST" "$LAST"
echo "done: $FIRST → $LAST. Now publish analysis/report.html to the existing artifact URL."
