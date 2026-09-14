# TipStroke

An Android-first raster drawing experiment focused on excellent stylus feel and an architecture that can later add vector layers and animation without weakening the Android hot path.

The current milestone opens directly into a 2048×2048 layered canvas. Stylus input draws on the selected paint layer; finger input never paints. Two fingers pan, zoom, and rotate. Two-finger tap undoes and three-finger tap redoes. Image layers retain their original source pixels while scale and opacity remain editable.

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

## Layers and images

Open **Layers** in the top bar. Add paint layers with **+ Paint** or use **+ Image** to choose any raster format Android can decode on the device. Layer order, visibility, and opacity are editable. Imported images use a persisted document URI as the authoritative original and store scale separately, so scaling down and back to 100% does not resample or discard the original pixels. The finite 2048×2048 canvas still clips and composites the displayed result at canvas coordinates.

See [architecture](docs/ARCHITECTURE.md), [input behavior](docs/INPUT.md), and the [reference-device checklist](docs/TESTING.md).
