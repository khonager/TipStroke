# TipStroke

An Android-first raster drawing experiment focused on excellent stylus feel and an architecture that can later add vector layers and animation without weakening the Android hot path.

[![Get it on Obtainium](https://img.shields.io/badge/Get_it_on-Obtainium-5c2d91?logo=android&logoColor=white)](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/khonager/TipStroke)

The current milestone is a local drawing library with editable layered canvases. Stylus input draws on the selected paint layer; configurable finger gestures can navigate, smudge, pick color, undo, redo, or do nothing. Image layers retain their original source pixels while scale, opacity, and a non-destructive erase mask remain editable. Rectangular selections can constrain drawing or erasing on either layer type.

## Install

Tap **Get it on Obtainium** above on an Android device to add TipStroke from its [GitHub releases](https://github.com/khonager/TipStroke/releases). Obtainium will then install new signed APKs as releases are published. You can also download the latest APK directly from the releases page.

## Build

Set `ANDROID_HOME`/`ANDROID_SDK_ROOT`, or create an untracked `local.properties` containing `sdk.dir=/path/to/Android/Sdk`.

The short commands are intentionally similar to Flutter/npm workflows:

```sh
./tipstroke build    # APK → app/build/outputs/apk/debug/app-debug.apk
./tipstroke install  # connected Android phone or running emulator
./tipstroke test
./tipstroke check
```

Android Studio is optional. To install manually, enable USB debugging on your phone, connect it, confirm it appears in `adb devices`, then run `./tipstroke install`. On a laptop, run the APK in an Android emulator; TipStroke is not a native Linux desktop app yet.

On NixOS, `nix develop` now provides the JDK without pulling the unfree Android Studio package that caused the earlier evaluation error. Keep using your existing Android SDK through `ANDROID_HOME`/`ANDROID_SDK_ROOT` or `local.properties`; Gradle also finds that SDK's platform tools when installing. The flake is optional.

### GitHub releases

Pushes and pull requests are tested by GitHub Actions, with a debug APK attached to each successful workflow run. To publish an Obtainium-compatible release:

1. Update `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Create a signed Android keystore and add these GitHub Actions repository secrets: `TIPSTROKE_SIGNING_KEY` (the keystore encoded with `base64 -w 0`), `TIPSTROKE_KEYSTORE_PASSWORD`, `TIPSTROKE_KEY_ALIAS`, and `TIPSTROKE_KEY_PASSWORD`.
3. Push a tag matching the version name, such as `v0.1.0`.

The release workflow tests and lints the project, verifies the APK signature, and attaches `TipStroke-vX.Y.Z.apk` plus its SHA-256 checksum to a GitHub release. Keep the keystore and its passwords backed up: Android will not install an update signed with a different key over an existing installation.

## Layers and images

Open **Layers** in the top bar. Add paint layers with **+ Paint** or use **+ Image** to choose any raster format Android can decode on the device. Layer order, visibility, and opacity are editable. Select an image and tap **Move & resize on canvas**: drag to move, pinch to resize, and twist to rotate it. Choose **Eraser** to hide image pixels through a source-resolution mask; the imported file remains untouched. Imported images use a persisted document URI as the authoritative original and store transforms and masks separately, so scaling down and back to 100% does not resample or discard the original pixels.

Tap **Select**, choose **Lasso** or **Rectangle**, and draw with a finger or stylus to constrain edits on the current paint or image layer. On paint layers, choose **Move** and drag to reposition only the selected pixels; the move is undoable. **Adjust** beside the brush tools opens a compact Brush Studio with hardness, pressure-size, pressure-opacity, and speed-taper controls. TipStroke remembers size, opacity, and tuning separately for every brush and for the eraser.

Pencil and Airbrush use TipStroke-owned Jetpack Ink 1.1 custom families. Pencil combines pressure, tilt, barrel orientation, speed taper, and procedural paper grain. Airbrush lays down textured particles with deterministic scatter and natural buildup instead of overlapping blurred circles. Both use the same brush family for the wet stroke and the final sparse-tile rasterization.

## Gallery, export, and gestures

TipStroke starts in **Your drawings**. Create a named canvas up to 8192×8192, or reopen a local project with its paint tiles, image sources, transforms, layer ordering, and opacity intact. Projects autosave every 30 seconds, when the app backgrounds, and before returning to the gallery.

Use **Export** in the editor to save PNG, JPEG, or WebP at canvas size, 50%, or 25%. PNG and WebP can preserve a transparent background; JPEG always composites onto white.

Open **Settings** from the gallery to assign one-finger drag/hold and two-/three-finger taps. One-finger actions include canvas navigation, tile-local smudging, and color picking; tap actions include undo, redo, or disabled. Hold delay, smudge strength, and canvas rotation lock are configurable and stored locally.

See [architecture](docs/ARCHITECTURE.md), [input behavior](docs/INPUT.md), and the [reference-device checklist](docs/TESTING.md).
