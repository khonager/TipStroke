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
while IFS=$'\t' read -r family device; do
    xcodebuild test \
      -project ios/TipStroke.xcodeproj -scheme TipStroke \
      -destination "platform=iOS Simulator,id=$device" \
      -parallel-testing-enabled NO \
      -derivedDataPath build/ios/DerivedData \
      -resultBundlePath "build/ios/${family}-$(date +%s).xcresult" \
      CODE_SIGNING_ALLOWED=NO
    xcrun simctl shutdown "$device" || true
done < build/ios/test-devices.tsv
