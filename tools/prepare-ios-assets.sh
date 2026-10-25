#!/bin/sh
set -eu
# Reuse the existing TipStroke identity, producing Apple's required icon dimensions on macOS.
repo=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
source_icon="$repo/app/src/main/res/drawable-nodpi/tipstroke_launcher.png"
target_icon="$repo/ios/TipStroke/Assets.xcassets/AppIcon.appiconset/AppIcon.png"
if [ ! -f "$target_icon" ] || [ "$source_icon" -nt "$target_icon" ]; then
    sips -z 1024 1024 "$source_icon" --out "$target_icon" >/dev/null
fi
