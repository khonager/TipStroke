# iPhone and iPad

TipStroke has a native iOS/iPadOS 16+ target in `ios/TipStroke.xcodeproj`, with the
`TipStroke` scheme and both device families enabled. UIKit/Core Graphics own input
and sparse raster painting. Xcode builds and links `TipStrokeShared.framework`
from `:shared-ui`, exporting `:core`. There is no account or network service.

**Full Android parity is still incomplete.** The implemented source workflows and
remaining differences are listed below. Linux JVM tests and Swift syntax parsing
are not an Apple SDK build, simulator run or physical Pencil validation.

## Shared implementation

- The Compose color picker, saved palette shelf, precision sliders and blend-mode
  picker are consumed by Android and iOS. Palette storage is platform-specific;
  colors, validation and palette controls are common code.
- Built-in presets, pressure/tilt curves, stamp spacing/scatter/dynamics, shape and
  grain coverage, quick-shape recognition, animation exposures and playback timing
  come from `core`. Android's stamp renderer now uses the same `BrushTip` coverage
  generator as UIKit.
- Brush package/library/pack validation is shared. iOS can import Android custom
  brushes, create/tune tips, save copies, delete tips and share individual packages.
  The common settings/library panels are available to platform hosts; Android's
  existing Tip Studio/library screen has not been replaced by those panels.
- Native `NativeBrush`, `NativeStampPath`, `NativeShapeTracker` and `NativeTimeline`
  adapters expose common rules to Swift. Samples may enter a core algorithm but
  never Compose state. Core Graphics and Jetpack Ink remain different renderers.

## Included native workflows

- Local gallery with thumbnails, creation, rename, duplicate, reopening and delete.
  Canvas dimensions are 16–4096 px per side.
- Pencil/finger drawing, Pencil/Ink/Airbrush/Eraser, pressure and tilt, size/opacity,
  stabilization and speed taper. Native shape-and-grain stamping uses shared tip
  coverage, arc-length placement and dynamics. Custom Jetpack definitions use an
  approximate native stamp adaptation, not Jetpack's renderer.
- Shape/grain sources can be imported from images, captured from a drawn layer,
  inverted and rotated. Transparent sources use alpha; opaque sources use brightness.
  Source images are bounded to 256×256 coverage, stored in portable brush packages.
- Two-finger pan/pinch, fit, portrait/landscape layouts, iPad resizing, Pencil
  double-tap eraser switching, and Command-Z/Shift-Command-Z/Command-0.
- Tools menu: fill, smudge, pixel pencil/line/dither, line/rectangle/ellipse/triangle/
  arrow, filled shapes, and Pencil hold-to-recognize quick shapes. Fill uses a
  worker snapshot with a bounded scanline flood; smudge exchanges premultiplied
  color and alpha instead of duplicating pigment.
- Lasso/rectangle/ellipse selections, select all, invert, clear, move, copy, flip,
  scale and rotate. Edits clip to the selection and undo as one transaction.
  Crop/extend canvas applies to all animation frames and participates in undo.
- Up to 32 layers, names/order/visibility/opacity, 17 blend modes, locks, duplicate,
  delete, clipping sources, reference-stroke constraints and static-layer merge-down.
  Reordering clears clipping links that would point upward or create cycles.
  Reference strokes constrain future paint to the reference's coverage; they do
  not hide that layer or exclude it from exports.
- Eyedropper, shared saved palettes, persistent recent colors and a quick-color row.
  Drawing colors are sampled from a bounded thumbnail after saves and on opening
  the color picker. Imported images
  are downsampled/tiled off the input thread into a paint layer.
- Animation timeline: blank/duplicate/delete/reorder frames, per-frame holds,
  FPS, loop/ping-pong/once, adjacent onion skins, frame-aware undo, save/reopen,
  GIF and silent MP4 export. Timing uses Android's shared timeline rules. Current
  Apple limits are 240 frames and 512 resident tiles across all frames/layers;
  Android's disk-backed long-timeline implementation is not yet ported.
- Paper color/opacity, atomic local saves and white/custom-paper or transparent PNG
  sharing. Thumbnail and encoding work runs away from the input thread.

Finger drawing defaults on. Disable **Draw with finger** in brush settings for
Pencil-only input. A second finger cancels provisional finger paint and starts
navigation. Pencil takes priority. Pressureless hardware, fingers and estimated
force use the constant fallback. No predicted or late-corrected samples are baked
into permanent tiles. Hover, squeeze and barrel roll remain unimplemented.

## Remaining parity work

| Area | Remaining difference |
| --- | --- |
| Brush quality and input | Native graphite/airbrush/Jetpack texture parity, wet/final and Pencil hardware validation, complete per-tip preferences/favorites/categories |
| Fill and gestures | Android color-drop/reveal UI, concurrent editing during fill, configurable gesture actions and camera rotation |
| Selection/image editing | Non-destructive source-backed image layers/masks, Apply/Cancel transform sessions, freeform/perspective/mesh warp and local deformation; current imported images are raster paint layers |
| Animation | Shared background cels, live motion recording, disk-backed long timelines, resolution reduction and PNG-sequence export |
| Video | Source-backed GIF/video canvases, media playback/scrubbing, clip editing, keyframes, crossfades and coverage navigation |
| Process recording | Replay persistence, preview/scrub and process exports |
| Project exchange | Lossless Android `.tipstroke` import/export; current iOS documents use their own versioned format |
| Gallery and editor shell | Stacks/drag organization, multi-export, Android's adaptive toolbar layout and matching color-drop interaction |

Adding a feature only to `app/` or `drawing-android/` still does not port it to iOS.
Keep portable rules in `core`, reusable controls in `shared-ui`, and provide both
native adapters. Do not silently flatten unsupported Android project data.
See [Multiplatform migration](MULTIPLATFORM.md).

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
