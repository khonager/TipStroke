#!/usr/bin/env bash
set -euo pipefail
: "${CHANNEL:?}" "${REVISION:?}" "${GITHUB_REPOSITORY:?}"
export GH_REPO="$GITHUB_REPOSITORY"
case "$CHANNEL" in stable|unstable) ;; *) exit 2 ;; esac
release="linux-$CHANNEL-$REVISION"
mkdir -p "$RUNNER_TEMP/linux-publication"
cd "$RUNNER_TEMP/linux-publication"
if [ "${BUILD_EXISTS:-false}" = true ]; then
  gh release download "$release" --pattern tipstroke.json --clobber
else
  cp "$GITHUB_WORKSPACE/app/build/outputs/apk/release/app-release.apk" TipStroke.apk
  hash="sha256-$(openssl dgst -sha256 -binary TipStroke.apk | base64 -w0)"
  jq -n --arg channel "$CHANNEL" --arg revision "$REVISION" --arg hash "$hash" \
    --arg url "https://github.com/$GITHUB_REPOSITORY/releases/download/$release/TipStroke.apk" \
    '{schemaVersion:1, channel:$channel, revision:$revision, version:($channel + "-" + $revision[0:12]), url:$url, hash:$hash}' > tipstroke.json
  # Prerelease keeps packaging artifacts out of the Android stable release feed.
  gh release create "$release" TipStroke.apk tipstroke.json --target "$REVISION" \
    --prerelease --latest=false --title "Linux $CHANNEL ${REVISION:0:12}" \
    --notes "Immutable Linux package artifact from the $CHANNEL branch. Managed by Linux package channels."
fi
current=$(gh api "repos/$GITHUB_REPOSITORY/git/ref/heads/$CHANNEL" --jq .object.sha)
if [ "$current" != "$REVISION" ]; then
  echo "Branch advanced; leaving its channel pointer unchanged."
  exit 0
fi
pointer="linux-$CHANNEL"
if gh release view "$pointer" >/dev/null 2>&1; then
  gh release upload "$pointer" tipstroke.json --clobber
else
  gh release create "$pointer" tipstroke.json --target "$REVISION" \
    --prerelease --latest=false --title "Linux $CHANNEL channel" \
    --notes 'Nix channel pointer. The manifest identifies an immutable, hash-checked APK.'
fi
