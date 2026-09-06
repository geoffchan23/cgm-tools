#!/bin/bash
# Install (or reinstall) the Sunday 10 PM launchd job that runs the weekly
# report through Claude Code on this Mac. Run once; re-run after moving the repo.
#
#   tools/install-weekly-job.sh            # install / update
#   tools/install-weekly-job.sh --remove   # uninstall
#   launchctl start com.geoffchan.cgm-weekly-report   # run it now, for testing
set -euo pipefail
LABEL=com.geoffchan.cgm-weekly-report
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
REPO="$(cd "$(dirname "$0")/../.." && pwd)"

if [ "${1:-}" = "--remove" ]; then
  launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
  rm -f "$PLIST"; echo "removed $LABEL"; exit 0
fi

cat > "$PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key><array>
    <string>/bin/bash</string>
    <string>$REPO/glucose-app/tools/weekly-report-cron.sh</string>
  </array>
  <key>StartCalendarInterval</key><dict>
    <key>Weekday</key><integer>0</integer>
    <key>Hour</key><integer>22</integer>
    <key>Minute</key><integer>0</integer>
  </dict>
  <key>StandardOutPath</key><string>$REPO/glucose-app/data/weekly-report.launchd.log</string>
  <key>StandardErrorPath</key><string>$REPO/glucose-app/data/weekly-report.launchd.log</string>
</dict></plist>
EOF
launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
launchctl bootstrap "gui/$(id -u)" "$PLIST"
echo "installed $LABEL: Sundays 22:00 → $REPO/glucose-app/data/weekly-report.log"
