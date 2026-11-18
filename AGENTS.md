# TipStroke agent guide

TipStroke is an Android-first, local-first drawing app. Optimize for “open canvas → draw”: keep the canvas dominant and permanent controls minimal.

## Non-negotiables

- The Android latency-sensitive surface stays native Android (`MotionEvent` + Jetpack Ink/native views), never behind a cross-platform UI abstraction or per-sample Compose state.
- The iOS surface stays native UIKit/Core Graphics. Do not route pointer samples through cross-platform UI state.
- Jetpack Ink authors wet strokes only. Raster tiles are the permanent painting source of truth.
- `:core` stays free of Android, Compose, Bitmap, MotionEvent, and Jetpack Ink types.
- Raster layers are sparse 256 px tiles. Never copy a full canvas/layer per stroke, redraw all tiles per sample, block the input thread on I/O, or keep unlimited history.
- Layer/document IDs and schema versions are stable extension points for future vector layers, masks, and animation. Do not implement those features speculatively.
- No accounts, network dependency, telemetry, ads, or cloud requirement.

## Modules

- `:core`: Kotlin Multiplatform document/layer/brush models, transforms, tile math, drawing contracts, undo concepts (`commonMain` / `commonTest`; JVM and iOS targets).
- `:shared-ui`: Compose Multiplatform controls using core contracts; no native drawing implementation or per-sample pointer state.
- `:drawing-android`: stylus routing, Jetpack Ink authoring, sparse Android bitmap tiles, compositing, gestures.
- `:app`: lifecycle and minimal Compose controls around the native surface.
- `ios/`: native iPhone/iPad canvas and platform adapters, linked to `:core`/`:shared-ui` through `TipStrokeShared.framework`, with local persistence and Xcode tests. See `docs/IOS.md`; builds run in Codemagic or macOS/Xcode.

## Commands

```sh
./gradlew assembleDebug
./gradlew test
./gradlew lint
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Update the relevant document in `docs/` whenever architecture, input behavior, brush semantics, performance constraints, project format, or roadmap changes.

## Shared feature development

- Put portable feature rules/serialization in `:core` and reusable controls in `:shared-ui`; Android and iOS hosts should supply platform adapters. Moving Kotlin targets alone does not provide feature parity.
- Preserve native input/rendering. Add an Apple adapter or explicitly update the parity table in `docs/IOS.md` when introducing Android-only behavior.
- Apple CI uses JDK 17, Xcode 26.0 and `-Ptipstroke.appleOnly=true`; no Android SDK is needed for that build path.
