#!/bin/bash
# launchd entry point (installed by tools/install-weekly-job.sh): run the
# weekly report pipeline and log it. Deliberately plain shell, no Claude —
# under launchd the Claude binary hangs on macOS's Desktop folder protection
# (TCC) as long as this repo lives under ~/Desktop, and it can't be granted
# consent from a background job. The pipeline only needs bash, adb, python3.
# Republishing the artifact + discussing the week happens in an interactive
# session via the `weekly-report` skill.
set -uo pipefail
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
LOG="$REPO/glucose-app/data/weekly-report.log"
export PATH="/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:$HOME/Library/Android/sdk/platform-tools"
mkdir -p "$(dirname "$LOG")"
{
  echo "=== $(date '+%F %T') weekly report"
  "$REPO/glucose-app/tools/weekly-report.sh"
  echo "=== exit $? at $(date '+%F %T')"
} >> "$LOG" 2>&1
