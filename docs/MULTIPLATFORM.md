# Shared Kotlin migration

The target is one set of product rules, project formats and editor controls,
with native latency-sensitive drawing surfaces. Kotlin Multiplatform shares
logic; Compose Multiplatform shares UI. Neither requires pointer samples to
pass through Compose state.

The Android tablet remains the primary product and performance reference.
The desktop delivery choices are the same app through Kotlin Multiplatform or
emulated Android. Do not develop a separate desktop feature implementation.
The existing Java2D preview is legacy scaffolding, not the parity strategy.
XP-Pen on NixOS is the first drawing-pad validation target; other devices should
use standard platform input support rather than brand-specific product logic.

## Current integration

- `:core/src/commonMain` contains document/layer/brush models, geometry, tile
  math, selection transforms, stamp paths and timeline logic. Its existing tests
  live in `commonTest`. JVM output is consumed by Android and desktop. Apple
  targets are `iosArm64`, `iosSimulatorArm64`, and `iosX64`.
- Core no longer uses Java UUID, time or math APIs. UUID strings keep their
  standard representation; document model timestamps use `kotlin.time.Instant`.
  On-disk manifests and schema versions are unchanged by this migration.
- `:shared-ui` contains the precision sliders and blend-mode picker. Android
  uses these controls, and desktop uses the same sliders including logarithmic
  size travel and numeric step buttons. Android-only color-drop interactions
  and native preview surfaces remain in `:app`.
  Vertical slider drawing and hit testing now use identical density-scaled
  endpoints, with portable regression tests at 1× through 3× display density.
- The native desktop renderer is still the smaller Java2D renderer. Shared
  controls do not imply that layers or advanced brushes are implemented there.
- The Swift app now links `TipStrokeShared.framework` from `:shared-ui`, exporting
  `:core`. Xcode builds it automatically before Swift compilation. Shared color
  controls, precision sliders, blend-mode UI and basic brush rules are live iOS
  integrations; UIKit remains the canvas and gallery host.
- Android's full color picker and brush-package codec moved to common source sets.
  Android retains thin palette/persistence/stream adapters. Both applications consume
  the same package validation, preset curves and color-control implementation.
- Shared `BrushTip` coverage now drives both Android and native iOS stamp rendering.
  Swift uses core quick-shape recognition and exposure-based timeline playback.
  The saved-palette state/control implementation is shared; each host supplies storage.
  Native Apple selection/fill/smudge/pixel/shape tools and frame editing are manual
  adapters, with their current limitations tracked in `IOS.md`.
- `AppleEditorBridge` sends coarse settings to Swift. It never sees UITouch or
  pixel buffers. NativeBrush snapshots freeze a stroke's settings while UIKit
  applies shared pressure/tilt math and native sparse-tile painting.
- `-Ptipstroke.appleOnly=true` permits Apple builds without an Android SDK. CI
  checks shared simulator tests and the integrated app; Codemagic runs both iPhone
  and iPad UI flows and builds device artifacts. See [iOS scope](IOS.md).

## Next integration boundaries

Replace the independent preview with the shared editor incrementally. Move
editor commands/state, gallery behavior and project serialization out of
Android dependencies before sharing their screens. Provide platform adapters
for file pickers, storage, thumbnails and native rendering. Preserve stable IDs,
schema validation and unknown data; never flatten unsupported projects silently.
Migrate one complete workflow at a time, retaining the working Android behavior.

For the shared desktop app, validate pressure, tilt, eraser/buttons, coordinate
mapping and cancellation with an XP-Pen pad on NixOS. Keep any required platform
input adapters behind the shared drawing contracts; do not fork editor features
or build a second brush library/project implementation. The current preview's
AWT mouse events are not evidence of full tablet support.

Android keeps MotionEvent/Jetpack Ink/native tile rendering. iOS keeps UIKit and
Core Graphics. The shared desktop app may use platform-specific input/rendering adapters.
Shared UI receives coarse state and commands, never an update for every pointer
sample. Complete shared editor integration, renderer compatibility and cross-platform
project compatibility remain unfinished work. Until then, use `./tipstroke emulator`
for the complete Android feature set on a laptop. Its scrcpy flexible virtual
display resizes Android with the desktop window; the fixed-screen Qt emulator
window remains an explicit `TIPSTROKE_EMULATOR_WINDOW=tablet` fallback.

## Verification

```sh
./gradlew :core:jvmTest :shared-ui:jvmTest :desktop:test
./gradlew :core:compileCommonMainKotlinMetadata :shared-ui:compileCommonMainKotlinMetadata
./gradlew :app:testDebugUnitTest :app:assembleDebug
# macOS + Xcode (also configured in the Shared Kotlin checks CI workflow):
./gradlew -Ptipstroke.appleOnly=true :core:iosSimulatorArm64Test :shared-ui:iosSimulatorArm64Test
./gradlew -Ptipstroke.appleOnly=true :shared-ui:linkReleaseFrameworkIosArm64
./tipstroke ios-test
```

Apple builds cannot be validated on Linux. The configured macOS CI job must
pass before treating the Apple artifacts as verified. Continue physical-device
checks for latency, stylus pressure/tilt, memory and wet/final stroke handoff.

## Desktop GPU and scaling support

AMD, Intel and NVIDIA are intended desktop targets; there is no vendor-specific
renderer selection in app code. Compose/Skiko selects the desktop UI backend;
the current Java2D tile engine is separate from that UI renderer. For driver
diagnosis, `SKIKO_RENDER_API=SOFTWARE ./tipstroke desktop-preview` requests software UI
rendering. This is a compatibility fallback, not a performance target.

The Android emulator requests host graphics, with an explicit software fallback
through `TIPSTROKE_EMULATOR_GPU=swiftshader`. Nix launches expose the system's
graphics-driver data path so ICD discovery can find the installed driver,
including NVIDIA, without hardcoding Mesa or a specific GPU. Installing a
working host driver is the responsibility of the OS, not the app launcher.

Before claiming NVIDIA validation, test launch, continuous strokes, undo,
pan/zoom, export and window resize on NVIDIA hardware under both X11 and
Wayland. Also test moving across displays at 100%, 125%, 150% and 200% scaling.
An AMD result does not establish NVIDIA performance or compatibility.
