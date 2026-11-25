#!/usr/bin/env bash
# Measures the IDE's cold start on the running emulator: installs the APK, then starts
# MainActivity from a stopped process several times (`am start -W -S`) and reports the
# median TotalTime, the time until the first frame, to the job summary.
# Usage: startup-benchmark.sh <signed.apk> [runs]
set -euo pipefail

apk=$1
runs=${2:-7}
package=io.github.deuzivanviana.nativeforge
activity=io.nativeforge.app.MainActivity

adb install -r "$apk" > /dev/null
# The first start after install compiles the Baseline Profile in the background; let it finish.
adb shell cmd package compile -m speed-profile -f "$package" > /dev/null || true
adb shell am start -W -S -n "$package/$activity" > /dev/null
sleep 5

times=()
for i in $(seq "$runs"); do
  t=$(adb shell am start -W -S -n "$package/$activity" | tr -d '\r' | awk -F': ' '/TotalTime/ { print $2 }')
  echo "run $i: ${t} ms"
  times+=("$t")
  sleep 2
done
median=$(printf '%s\n' "${times[@]}" | sort -n | awk '{ a[NR] = $1 } END { print a[int((NR + 1) / 2)] }')
echo "Median cold start: $median ms"
{
  echo "### Cold start (emulator, API ${API_LEVEL:-?})"
  echo ""
  echo "Median of $runs runs: **$median ms** (runs: ${times[*]})"
} >> "${GITHUB_STEP_SUMMARY:-/dev/stdout}"
