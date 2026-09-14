# TipStroke

An Android-first raster drawing experiment focused on excellent stylus feel and an architecture that can later add vector layers and animation without weakening the Android hot path.

The current milestone opens directly into a 2048×2048 canvas. Stylus input draws; finger input never paints. Two fingers pan, zoom, and rotate. Two-finger tap undoes and three-finger tap redoes.

## Build

Set `ANDROID_HOME`/`ANDROID_SDK_ROOT`, or create an untracked `local.properties` containing `sdk.dir=/path/to/Android/Sdk`.

```sh
./gradlew assembleDebug
./gradlew test
./gradlew lint
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On NixOS, enter `nix develop`, accept Android SDK licenses in your normal SDK setup if required, and use the normal Gradle wrapper commands above. The flake is optional.

See [architecture](docs/ARCHITECTURE.md), [input behavior](docs/INPUT.md), and the [reference-device checklist](docs/TESTING.md).
