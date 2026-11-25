# iPhone and iPad

TipStroke has a native iOS/iPadOS 16+ target in `ios/TipStroke.xcodeproj`, with the
`TipStroke` scheme and both device families enabled. UIKit/Core Graphics own input
and sparse raster painting. Xcode builds and links `TipStrokeShared.framework`
from `:shared-ui`, exporting `:core`. There is no account or network service.

UIKit connects a single window scene on launch. Its delegate opens the gallery,
and the editor saves the current drawing when that scene deactivates.

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
  The tip library saves per-tip tuning, named categories and favorites. Favorite
  tips are available in the editor’s quick shelf.
  The common settings/library panels are available to platform hosts; Android's
  existing Tip Studio/library screen has not been replaced by those panels.
- Native `NativeBrush`, `NativeStampPath`, `NativeShapeTracker` and `NativeTimeline`
  adapters expose common rules to Swift. Samples may enter a core algorithm but
  never Compose state. Core Graphics and Jetpack Ink remain different renderers.

## Included native workflows

- Local gallery with thumbnails, creation, rename, duplicate, reopening and delete.
  Canvas dimensions are 16–8192 px per side.
- Pencil/finger drawing, Pencil/Ink/Airbrush/Eraser, pressure and tilt, size/opacity,
  stabilization and speed taper. Native shape-and-grain stamping uses shared tip
  coverage, arc-length placement and dynamics. Custom Jetpack definitions use an
  approximate native stamp adaptation, not Jetpack's renderer.
- Shape/grain sources can be imported from images, captured from a drawn layer,
  inverted and rotated. Transparent sources use alpha; opaque sources use brightness.
  Source images are bounded to 256×256 coverage, stored in portable brush packages.
- Two-finger pan/pinch/rotate, rotation lock, fit, portrait/landscape layouts, iPad resizing, Pencil
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
  the color picker. Photo layers retain the imported original, transform and mask.
- Animation supports per-frame holds, loop/ping-pong/once playback, onion skins,
  shared backgrounds, motion recording and frame-aware undo. Inactive drawings
  move to disk. The timeline supports up to 10,000 frames within storage limits.
  Export GIF, MP4 or a zipped PNG sequence with exposure timing.
- Paper color/opacity, atomic local saves and white/custom-paper or transparent PNG
  sharing. Thumbnail and encoding work runs away from the input thread.

**Drawing tools → More tools → Finger gestures** configures one-finger drag and hold, the hold delay,
and two- or three-finger tap actions. The editor’s **Draw / Pan** button switches
one-finger input and remembers the choice. In Pan, drag anywhere in the viewport,
including at Fit, and draw with Pencil. Fit recenters the sheet. Pinch can zoom
out below Fit or enlarge a small pixel-art canvas up to 256×. A second finger cancels provisional finger paint and starts
navigation. Pencil takes priority. Pressureless hardware, fingers and estimated
force use the constant fallback. No predicted or late-corrected samples are baked
into permanent tiles. On supported iPads, Pencil hover outlines the brush footprint. Pencil Pro barrel
roll rotates the stamp tip. Squeeze can open drawing tools, show colors or toggle
the eraser according to the system Pencil preference.

The editor shares the Android color theme for brush, palette and blend panels. More canvas actions opens resize, animation, preview and export. The color button shows the current paint. The editor dock shows size and opacity alongside pinned tip samples. Drag either
track to preview the colored footprint at the current zoom. Use the visible minus and plus buttons for single-unit adjustments. Landscape places
the two vertical tracks side by side. The default editor uses dark solid panels and a coral accent. Size can use document pixels or screen
points for regular brushes and erasers. The eraser keeps its own tuning. Tips
opens the full collection. The picker, library, dock and brush settings show
pressure-sweep samples rendered into small, isolated native raster documents.
Brush settings keep the current sample visible above the scrolling controls, with
minimum and maximum specimens beside the parameter tracks. Eraser samples cut
through a gray strip so you can see how much each tip removes.
Drawing tools → More tools → Use translucent controls or Use solid controls switches the editor dock
backgrounds between translucent material and an opaque system color.

## New drawing sizes

The gallery presents the shared Compose paper-stack browser via
`AppleNewDrawingBridge`. It supports scrolling or tapping sizes, locally saved
custom presets, a default size, and width-limited favorite shortcuts persisted in
`NSUserDefaults`. Android uses the same browser with SharedPreferences adapters.
Portable presets and preference serialization live in `:core`; native canvas
input and rendering are unchanged. Both platforms support up to 8192 px per side. The maximum one-layer square
preset uses the iOS resident tile budget or Android’s app heap budget.

## Android workflows on iPhone and iPad

| Workflow | iOS behavior |
| --- | --- |
| Floating canvas preview | Moving tools → Canvas preview opens an overview of the whole drawing. Drag to reposition, pinch to resize, drag to either edge to tuck, and tap to expand. Hide it from Moving tools. |
| Brush input | Core Graphics uses shared contact, grain and stamp rules for graphite, airbrush and imported tips. Jetpack Ink brushes use native approximations. Wet-stroke appearance, latency and Pencil sensor behavior need physical-device comparison. |
| Selection and images | Preview and apply or cancel transforms, edit source-backed photos and masks, and use freeform, perspective, mesh and local deformation. PNG export samples the original within a bounded decode budget. |
| Animation and video | Work with disk-backed cels, change resolution, record motion, scrub GIF and video sources, edit clips and crossfades, and navigate drawing coverage. Photo motion uses keyframes. |
| Process recording | Preview, scrub and export saved replay. Older v1 Android brush commands are decoded into local replay tiles, including selection, erasing and pixel tools. Jetpack Ink inputs use the native Apple brush approximation. Portable raster companions retain Android-rendered pixels and take precedence when present. |
| Project exchange | Import and export Android project archives. Native metadata preserves iOS-specific locks and frame masks through an Android save. The local gallery also keeps its versioned native format. |
| Gallery and editor | Named stacks, batch sharing, adaptive controls and quick-color drag-to-fill. Both platforms separate Drawing tools from Moving tools and support holding and sliding to select a tool. Both hosts use the shared visual tip picker, independent favorites and ordered dock pins, a tappable pin overflow folder, searchable groups, and an independent eraser tip. Both offer a canvas Deselect shortcut while a selection exists. |

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
   the same simulator checks, then uploads the build to App Store Connect for TestFlight.

In Codemagic, open TipStroke and select **Start new build**. Choose the branch
containing the `codemagic.yaml` you want to run, such as `unstable`, and select
**iOS — iPhone and iPad checks** (`ios-simulator`). The YAML supplies Xcode and
Java. This workflow needs no signing identities or environment variables in
Codemagic. After a successful build, download `TipStroke-simulator.zip` from
the build's artifacts.

If **Test on iPhone and iPad** fails, check **Simulator test summary** at the
end of the step for each device's result. Both device families run even if the
first fails. Use the failing device's output or its `.xcresult` bundle to find
the first test, build, or simulator error.

For the signed workflow:

1. Register the explicit App ID `de.khonager.tipstroke` in Apple Developer and
   select it when creating the TipStroke app record in App Store Connect.
2. In Codemagic → Team settings → Team integrations → Developer Portal → Manage keys,
   find the existing App Store Connect integration for your Apple team. Set
   `ios-release.integrations.app_store_connect` in `codemagic.yaml` to its exact
   name. If you are connecting the team for the first time, add a team API key
   with upload access using its issuer ID, key ID and private `.p8` file.
3. In Codemagic → Team settings → codemagic.yaml settings → Code signing identities,
   reuse a valid Apple Distribution certificate with its private key. Fetch or upload
   an App Store Connect provisioning profile for `de.khonager.tipstroke` that
   includes this certificate.
4. Start **iOS — signed App Store IPA** on the branch with this configuration.
   Codemagic uploads the signed IPA. After Apple processes it, open TipStroke →
   TestFlight in App Store Connect, answer any export compliance prompt, and add
   the build and testers to an internal testing group.

For direct registered-device distribution, change the release workflow's
`distribution_type` to `ad_hoc` and provide the matching profile containing those
devices. This is a signing choice, not an application-code change.

Codemagic pins Xcode 27.0 and Java 17. Xcode 27 supplies the iOS 27 SDK
required for App Store Connect uploads starting April 2027. TipStroke targets
iOS 16 and later. GitHub Actions still uses Xcode 26.0 for its Apple checks.
The framework uses [Kotlin direct integration](https://kotlinlang.org/docs/multiplatform/multiplatform-direct-integration.html).
The configuration follows Codemagic's [native iOS build guide](https://docs.codemagic.io/yaml-quick-start/building-a-native-ios-app/)
and [signing instructions](https://docs.codemagic.io/yaml-code-signing/signing-ios/).
No Codemagic build or Apple submission is performed merely by adding these files.

## Local macOS development

Install JDK 17 and Xcode 27.0 to match Codemagic, then open
`ios/TipStroke.xcodeproj`, choose **TipStroke**, and run
an iPhone or iPad simulator. For a physical device, select your Apple development
team in Signing & Capabilities. Xcode resolves the pinned ZIPFoundation package for project exchange. Gradle downloads Kotlin/Compose
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

Tap an iOS layer to expand its name field, visibility switch and settings button
in the row. Full settings include opacity, blend mode, reference and clipping.
On iOS the compact gallery heading contains New and Select drawings. Select multiple
drawings and use Share to export their PNGs together. Drag into the center of a
drawing or stack to group it, or near an edge to reorder. Swipe a stack to rename
or unstack it. Swipe a drawing inside a stack to move it back to the gallery.
The editor shows tool and control names on pointer dwell.

## Creative tools on iOS

- **Drawing tools → More tools → Paint tools**: live Lasso fill and Color push alongside Brush,
  Fill and Smudge. Lasso fill uses native display-link previews; Color push uses
  a bounded local inverse warp, with size as diameter and opacity as strength.
- **Tip library → Calligraphy** uses the shared angled nib and pressure curve with native stamp rendering.
- **Drawing tools → Brush aids**: temporary Paint inside, hold-and-bend Elastic line,
  Mirror wheel (Off/2/4/6/8/12, movable center), and magnetic one/two-point rulers.
  Paint inside preserves existing soft alpha and overrides reference constraints.
  Rulers take priority over Elastic line. Numeric vanishing-point entry supports
  off-canvas points. Placement prompts offer a visible Cancel button.
- **Moving tools → Selection actions → Shadow pull**: select, arm, drag, release. A
  current-color shadow is composited behind paint on the same layer. Preview can
  be cancelled; opaque paint on that layer naturally hides the shadow.
- **Drawing tools → Blur & bloom**: Gaussian, Motion, Perspective and Bloom with relevant
  radius/angle/center/threshold/intensity controls. Worker-owned tile halos reuse
  core filter math. Preview does not mutate the source; Apply commits exactly
  that preview as one undo transaction.
- **Drawing tools → Colorize mask**: creates saved editable hints above line art and
  generated output beneath it. Reopen hints, use magenta for transparent regions,
  tune gap/threshold, Preview, then Apply. Shared codec/solver, still drawings,
  1024-pixel analysis limit and sparse native-resolution output. Schema 4 retains
  recipe links; generation and hints visibility undo together. Missing inputs
  preserve existing generated paint and report an error when regenerating.
- **Drawing tools → More tools → Manga tips**: six shared dot/hatching presets, native canvas-anchored
  grain, nearest-neighbor pattern sampling and inline usage guidance.

These additions preserve native UIKit sample routing, 256 px sparse tiles, the
512-resident-tile cap and 32 MiB undo budget. Large effects/colorize outputs can
require a smaller selected area/drawing. The new `CreativeToolsTests` cover raster
transactions and persistence; they are checked in for the existing Codemagic
simulator workflow. Linux validation covers shared JVM tests and Swift syntax
parsing only, not UIKit type checking or simulator/device behavior.

Tap **Drawing tools** to toggle the eraser. Hold the icon, slide to a tool, and
release to select it. Pausing over an icon shows its name. Release outside the grid
to cancel. **More tools** opens the editor menu. Turn off **Hold for drawing tools**
in Brush settings to open the drawing palette with a tap. Tap or hold **Moving
tools** for selection, selection recall, transform, selection actions, and Fit canvas.

Drag the iOS brush-color button onto a canvas region to fill it. The worker keeps
the editor responsive and discards a stale result if the destination has changed.
A radial reveal uses visible tile layers and respects Reduce Motion.

Resize canvas opens an eight-handle crop and extension preview. Apply translates sparse raster tiles and photo poses across cels by the chosen left and top offsets. Cancel discards the preview.

Layers show artwork thumbnails. Swipe right to change multi-selection, drag onto a thumbnail to join its group, or drop beside a name to reorder above or below its group.

Pixel pencil, Pixel line and Dither appear in the tip picker and library at any zoom. The Pixel tips folder appears in the dock at 8× zoom and hides below it. Small sheets open with the 1 px Pixel tool. Eraser follows the selected grid tool.

Export image supports PNG, JPEG and WebP with a file name and 100%, 50% or 25% scale. JPEG and WebP offer quality controls; PNG and WebP can omit the paper. WebP encoding uses the bundled libwebp library.
