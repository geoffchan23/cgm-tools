#!/bin/bash
# launchd entry point: run the weekly-report skill headlessly in Claude Code.
# Installed by tools/install-weekly-job.sh; logs to data/weekly-report.log.
set -uo pipefail
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
LOG="$REPO/glucose-app/data/weekly-report.log"
export PATH="$HOME/.nvm/versions/node/v22.21.0/bin:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:$HOME/Library/Android/sdk/platform-tools"
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
mkdir -p "$(dirname "$LOG")"
{
  echo "=== $(date '+%F %T') weekly report"
  cd "$REPO" && claude -p "/weekly-report" \
    --allowedTools "Bash(glucose-app/tools/weekly-report.sh*)" "Read" "Artifact" \
    --output-format text
  echo "=== exit $? at $(date '+%F %T')"
} >> "$LOG" 2>&1
