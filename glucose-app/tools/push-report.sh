#!/bin/bash
# Copy a generated report into the app's private reports folder on the phone,
# where the Reports screen lists it. Works because the app is a debug build
# (`run-as`). The file name must be report-<first>_<last>.html so the app can
# show the date range as the title.
#
# Usage: tools/push-report.sh <adb-serial> <report.html> <first-day> <last-day>
set -euo pipefail
[ $# -eq 4 ] || { echo "usage: $0 <adb-serial> <report.html> <YYYY-MM-DD first> <YYYY-MM-DD last>"; exit 2; }
SERIAL=$1; FILE=$2; FIRST=$3; LAST=$4
PKG=com.geoffchan.glucosewidget
NAME="report-${FIRST}_${LAST}.html"

adb -s "$SERIAL" push "$FILE" /data/local/tmp/"$NAME" >/dev/null 2>&1
adb -s "$SERIAL" shell run-as $PKG mkdir -p files/reports
adb -s "$SERIAL" shell run-as $PKG cp /data/local/tmp/"$NAME" files/reports/"$NAME"
adb -s "$SERIAL" shell rm /data/local/tmp/"$NAME"
echo "pushed $NAME → app reports"
