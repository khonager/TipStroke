# iPhone and iPad

TipStroke now has a native iOS target in `ios/TipStroke.xcodeproj`, with the shared
`TipStroke` scheme. It targets iOS/iPadOS 16 or newer and both device families.
The canvas uses UIKit and Core Graphics. The app links `TipStrokeShared.framework`
from `:shared-ui`, exporting `:core`, on every Xcode build. It has no account,
network service, or cross-platform drawing surface.

This is a source integration, **not full Android feature parity**. Apple
compilation, simulator tests and physical Pencil validation must pass before
calling this build verified. The feature-by-feature gaps are tracked below.

## Shared implementation

- Android and iOS use the same Compose color picker: wheel, HSV sliders, RGB/HSV
  values, hex entry, color harmony and recent-color controls. Android injects its
  persisted palette shelf through a composable slot.
- Size/opacity precision controls and the blend-mode picker live in `shared-ui`.
- Built-in brush defaults, pressure curves and pencil tilt response come from
  `core`. Swift consumes an immutable `NativeBrush` for each stroke; no pointer
  sample enters Compose state.
- Brush JSON validation/encoding now lives in `core` and is used by Android's
  library and iOS brush preferences. iOS currently exposes the three basic
  brushes; sharing a codec does not imply custom-tip rendering parity.
- Xcode calls `tools/build-ios-shared.sh` before Swift compilation. Changes to
  these common implementations reach both applications in their next build.

## Included native workflows

- Local gallery, named canvases from 16–4096 px per side, reopening and deletion.
- Native Pencil/finger drawing with coalesced samples, Pencil/Ink/Airbrush/Eraser,
  shared pressure/tilt rules, size, opacity and airbrush edge hardness.
- Two-finger pan/pinch, fit canvas, portrait/landscape layouts, iPad resizing,
  and keyboard shortcuts (Command-Z, Shift-Command-Z, Command-0).
- Up to 32 paint layers with stable identities, selection, names, ordering,
  visibility, opacity, 17 blend modes, layer lock, transparency lock,
  duplication and deletion. Metadata edits join bounded undo/redo history;
  continuous opacity/blend adjustments are grouped into one edit. Deleting a layer
  too large for undo is rejected without changing its pixels.
- Long-press finger eyedropper; it cancels provisional finger paint before sampling.
- File-based image import, downsampled and tiled on the project queue, fitted and
  centered into a new paint layer. The operation must fit the undo/tile budgets.
- Automatic local saves and flattened PNG sharing with white or transparent paper.
- Apple Pencil double-tap switches between the current drawing tool and eraser.
- Local persistence of the selected brush's size, opacity, pressure and tilt tuning,
  and the finger-drawing preference.

Finger drawing defaults to enabled so the app works immediately on iPhone.
Turn off **Draw with finger** in brush settings when drawing with Pencil on iPad.
A second finger cancels an unfinished finger mark and starts navigation. Pencil
input takes priority over finger drawing. Supported Pencil hardware supplies force;
fingers, pressureless pencils and estimated force samples use a constant fallback.
Pencil altitude is converted to the shared tilt convention (zero is upright).
Hover, predicted samples, late force corrections, squeeze and barrel roll remain
unimplemented. Core Graphics marks do not reproduce Jetpack Ink textures, custom
stamps, graphite grain, speed taper, or stabilization. Airbrush is a native soft-dab
renderer rather than Android's spray renderer.

## Parity still required

| Area | Current Apple status |
| --- | --- |
| Canvas, layers, basic tools, colors, local saving, PNG | Implemented; Apple CI/device validation required |
| Shared color controls, brush package schema, presets/pressure/tilt | Integrated with Android's common implementations |
| Custom stamp/Jetpack brush library, authoring, imported tip textures | Codec shared; UI/rendering not integrated |
| Fill, smudge, quick shapes and pixel tools | Android only |
| Selection, transforms, mesh/perspective warp | Android only |
| Layer clipping, merge, image adjustment/warp and reference layers | Android only |
| Animation, video timelines/import/export and process recording | Android only |
| Android `.tipstroke` project exchange | Not implemented; iOS uses a separate versioned format |
| Gallery thumbnails, saved palettes, persistent recent colors | Not implemented on iOS |

Future features do **not** automatically appear on iOS merely because they were
added to `app/` or `drawing-android/`. Put portable rules in `core`, reusable controls
in `shared-ui`, and implement native input/render/storage adapters for both
platforms. See [Multiplatform migration](MULTIPLATFORM.md). The remaining Android
editor/screens have not yet been moved behind those adapters.

## Build with Codemagic

The repository-root `codemagic.yaml` defines two workflows:

1. **ios-simulator** needs no Apple credentials. It runs common Kotlin tests on Apple Silicon, builds the shared framework, and runs raster/persistence tests
   and a create/draw/undo/save/reopen UI flow on available iPhone and iPad
   simulators, then builds an unsigned device app. Artifacts include `.xcresult`
   bundles and `TipStroke-simulator.zip`. The unsigned device app cannot be
   installed on a normal iPhone; the simulator ZIP is for a Mac simulator.
2. **ios-release** is started manually and produces a signed App Store IPA after
   the same simulator checks. It does not upload or submit anything to Apple.

Connect this repository in Codemagic and select its YAML configuration. Run
**ios-simulator** first. For the signed workflow:

1. Register the explicit App ID `dev.tipstroke.ios` in your Apple Developer team.
   If that identifier is unavailable or you prefer another, change it in
   `tools/generate-ios-project.py` and the signing block in `codemagic.yaml`, then
   regenerate and commit the Xcode project.
2. Add your Apple distribution certificate (including its private key) and a
   matching App Store provisioning profile under Codemagic **Team settings →
   codemagic.yaml settings → Code signing identities**. Keep these in Codemagic;
   never commit credentials or profiles. The workflow applies these through
   `xcode-project use-profiles` and uses Codemagic's build number for the IPA.
3. Run **ios-release** and download its IPA. App Store signing is for App Store
   Connect/TestFlight distribution, not direct installation on arbitrary devices.
   Upload through your normal App Store Connect process. TestFlight publishing
   can be configured separately after the app record and API integration exist.

For direct registered-device distribution, change the release workflow's
`distribution_type` to `ad_hoc` and provide the matching profile containing those
devices. This is a signing choice, not an application-code change.

Codemagic pins Xcode 26.0 and Java 17; Kotlin 2.2.21 adds Xcode 26 support.
Avoid changing Xcode to `latest` without checking Kotlin/Native compatibility.
The integration follows [Kotlin direct integration](https://kotlinlang.org/docs/multiplatform/multiplatform-direct-integration.html)
and [Kotlin 2.2.21's Xcode support](https://kotlinlang.org/docs/whatsnew2220.html).
The configuration follows Codemagic's [native iOS build guide](https://docs.codemagic.io/yaml-quick-start/building-a-native-ios-app/)
and [signing instructions](https://docs.codemagic.io/yaml-code-signing/signing-ios/).
No Codemagic build or Apple submission is performed merely by adding these files.

## Local macOS development

Install JDK 17 and Xcode 26.0 (Kotlin 2.2.21), then open
`ios/TipStroke.xcodeproj`, choose **TipStroke**, and run
an iPhone or iPad simulator. For a physical device, select your Apple development
team in Signing & Capabilities. There is no CocoaPods or Swift Package resolution. Gradle downloads Kotlin/Compose
dependencies during builds; the installed application remains fully offline.
The `tipstroke.appleOnly=true` Gradle property excludes Android/desktop modules
and disables the shared UI Android target, so an Android SDK is not needed.

```sh
./tipstroke ios       # unsigned simulator build
./tipstroke ios-test  # unit/integration + UI tests on both device families
```

The checked-in project is generated deterministically using
`python3 tools/generate-ios-project.py`. Regenerate it after adding Swift files
or changing target settings. Commit the project and shared scheme together.
The build phase uses macOS `sips` to prepare the required 1024 px iOS icon from
TipStroke's existing Android launcher asset; generated icon pixels are ignored.

Linux can edit and regenerate the project but cannot build UIKit or run iOS
simulators. Swift syntax parsing on Linux is not an Apple SDK type check. A green
Codemagic run plus the physical-device checklist in `TESTING.md` is required before
calling this port device-validated or ready for distribution.
