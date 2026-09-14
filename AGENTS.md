# TipStroke agent guide

TipStroke is an Android-first, local-first drawing app. Optimize for “open canvas → draw”: keep the canvas dominant and permanent controls minimal.

## Non-negotiables

- The latency-sensitive surface stays native Android (`MotionEvent` + Jetpack Ink/native views), never behind a cross-platform UI abstraction or per-sample Compose state.
- Jetpack Ink authors wet strokes only. Raster tiles are the permanent painting source of truth.
- `:core` stays free of Android, Compose, Bitmap, MotionEvent, and Jetpack Ink types.
- Raster layers are sparse 256 px tiles. Never copy a full canvas/layer per stroke, redraw all tiles per sample, block the input thread on I/O, or keep unlimited history.
- Layer/document IDs and schema versions are stable extension points for future vector layers, masks, and animation. Do not implement those features speculatively.
- No accounts, network dependency, telemetry, ads, or cloud requirement.

## Modules

- `:core`: document/layer/brush models, transforms, tile math, drawing contracts, undo concepts.
- `:drawing-android`: stylus routing, Jetpack Ink authoring, sparse Android bitmap tiles, compositing, gestures.
- `:app`: lifecycle and minimal Compose controls around the native surface.

## Commands

```sh
./gradlew assembleDebug
./gradlew test
./gradlew lint
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Update the relevant document in `docs/` whenever architecture, input behavior, brush semantics, performance constraints, project format, or roadmap changes.
