#!/bin/bash
# Runs the real app-hosted raster/persistence tests and UI flow on both device families.
set -euo pipefail
cd "$(dirname "$0")/.."
if ! command -v xcodebuild >/dev/null 2>&1; then
    echo 'iOS tests require macOS with Xcode. Run the ios-simulator workflow in Codemagic.' >&2
    exit 1
fi
mkdir -p build/ios
# Resolve available devices dynamically instead of pinning a simulator model/runtime that may not be installed.
xcrun simctl list devices available --json > build/ios/simulators.json
python3 - <<'PY' > build/ios/test-devices.tsv
import json
with open('build/ios/simulators.json') as stream:
    runtimes = json.load(stream)['devices']
for family in ('iPhone', 'iPad'):
    candidates = [device for runtime, devices in runtimes.items() if '.iOS-' in runtime
                  for device in devices if device.get('isAvailable') and device['name'].startswith(family)]
    if not candidates:
        raise SystemExit(f'No available {family} simulator. Install an iOS Simulator runtime in Xcode.')
    print(f"{family}\t{candidates[0]['udid']}")
PY
result=0
summary=()
while IFS=$'\t' read -r family device; do
    result_bundle="build/ios/${family}-$(date +%s).xcresult"
    printf '\nRunning %s simulator tests (%s)\n' "$family" "$result_bundle"
    if xcodebuild test \
      -project ios/TipStroke.xcodeproj -scheme TipStroke \
      -destination "platform=iOS Simulator,id=$device" \
      -parallel-testing-enabled NO \
      -derivedDataPath build/ios/DerivedData \
      -resultBundlePath "$result_bundle" \
      CODE_SIGNING_ALLOWED=NO </dev/null; then
        summary+=("$family passed ($result_bundle)")
    else
        summary+=("$family failed (xcodebuild exit $?, $result_bundle)")
        result=1
    fi

    if ! shutdown_output=$(xcrun simctl shutdown "$device" 2>&1); then
        # Xcode may already have shut down the simulator after testing.
        case "$shutdown_output" in
            *"Unable to shutdown device in current state: Shutdown") ;;
            *) printf 'Warning while shutting down the %s simulator\n%s\n' "$family" "$shutdown_output" >&2 ;;
        esac
    fi
done < build/ios/test-devices.tsv
printf '\nSimulator test summary\n'
printf '  %s\n' "${summary[@]}"
exit "$result"
