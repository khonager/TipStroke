# TipStroke

An Android-first, local-first raster drawing app with a native iPhone/iPad target and a lightweight Linux desktop companion. Android remains the most complete implementation; iOS adds its own native drawing surface and local library.

[![Get it on Obtainium](https://img.shields.io/badge/Get_it_on-Obtainium-5c2d91?logo=android&logoColor=white)](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/khonager/TipStroke)

The current milestone is a local drawing library with editable layered canvases. Stylus input draws on the selected paint layer; configurable finger gestures can navigate, smudge, pick color, undo, redo, or do nothing. Image layers retain their original source pixels while scale, opacity, and a non-destructive erase mask remain editable. Rectangular selections can constrain drawing or erasing on either layer type.

## Install

Tap **Get it on Obtainium** above on an Android device to add TipStroke from its [GitHub releases](https://github.com/khonager/TipStroke/releases). Obtainium will then install new signed APKs as releases are published. You can also download the latest APK directly from the releases page.

## Linux through Pantheon

Install the full app from [Pantheon](https://github.com/khonager/pantheon):

```sh
nix run github:khonager/pantheon#tipstroke           # stable branch
nix run github:khonager/pantheon#tipstroke-unstable  # development branch
```

The package includes an Android emulator and a resizable scrcpy window, plus an
application-menu entry when installed. It requires x86_64 Linux and KVM access.
It does not compile the app at launch. See [Linux packaging](docs/LINUX.md) for
channel updates, persistent data, and how this differs from the desktop preview.

## iPhone and iPad

The first native iOS port supports layered drawing, Apple Pencil and finger input,
undo/redo, local saves, and PNG sharing on iOS/iPadOS 16+. It is not yet at Android
feature parity and does not read Android project packages.

Use the **ios-simulator** workflow in [codemagic.yaml](codemagic.yaml) for iPhone
and iPad checks, or **ios-release** for a signed IPA after configuring Apple
signing. See [iOS setup and scope](docs/IOS.md). On a Mac, open
`ios/TipStroke.xcodeproj` or run `./tipstroke ios` and `./tipstroke ios-test`.
The port requires its first successful Xcode/Codemagic run and device validation;
it is not an App Store release.

## Build

Set `ANDROID_HOME`/`ANDROID_SDK_ROOT`, or create an untracked `local.properties` containing `sdk.dir=/path/to/Android/Sdk`.

The Android pixel kernel builds with NDK `28.2.13676358` and CMake `3.22.1`. Install them through the [Android SDK Manager](https://developer.android.com/studio/projects/install-ndk), or let Gradle install the pinned versions after accepting SDK licenses.

The short commands are intentionally similar to Flutter/npm workflows:

```sh
./tipstroke build    # APK → app/build/outputs/apk/debug/app-debug.apk
./tipstroke install  # connected Android phone or running emulator
./tipstroke laptop   # full Android app in the tablet emulator
./tipstroke desktop-preview # legacy preview (limited features)
./tipstroke desktop-package # package that legacy preview
./tipstroke emulator-setup  # Android tablet AVD, one time
./tipstroke emulator # production Android app in that AVD
./tipstroke test
./tipstroke check
./tipstroke brushes  # native brush regression sheets on a connected device
```

Android Studio is optional. To install manually, enable USB debugging on your phone, connect it, confirm it appears in `adb devices`, then run `./tipstroke install`.

Debug builds install as **TipStroke Debug** (`dev.tipstroke.app.debug`) alongside the signed release app. This avoids version-code and signing-key conflicts with releases and preserves their drawings. Each app has its own local library; export/import projects to transfer drawings between them.

`./tipstroke laptop` (also `desktop` or `emulator`) builds and runs the current Android app in a resizable desktop window backed by the tablet emulator, preserving its local drawings. The Nix shell supplies scrcpy 4.0+ for a flexible virtual display: resizing or tiling the window changes Android’s viewport, so its layout adapts without stretching the artwork. Run `./tipstroke emulator-setup` once first. This is the laptop path with the complete Android feature set while the shared Kotlin editor is being migrated. Use `TIPSTROKE_EMULATOR_WINDOW=tablet ./tipstroke laptop` for the old fixed-screen emulator window. Host GPU rendering is the default; emulator input may reduce a drawing pad to constant-pressure mouse input, so this does not establish professional tablet support.

The target is shared Kotlin Multiplatform product logic and controls with platform drawing surfaces. Android tablets remain the priority. See [the migration status](docs/MULTIPLATFORM.md) for what is shared and what remains unfinished.

`./tipstroke desktop-preview` opens the legacy Compose/Java2D preview. It has only basic tools and PNG support, without Android project/layer/brush parity; it is not the desktop product strategy.

`./tipstroke desktop-package` packages the legacy preview under `desktop/build/compose/binaries/main/app/`, including the required runtime. On NixOS, desktop and emulator commands automatically enter `nix develop` for host libraries. The flake remains optional on conventional Linux distributions.

The migration to shared Kotlin logic and Compose controls is underway; see [Multiplatform architecture and status](docs/MULTIPLATFORM.md). This does not yet give the native desktop or iOS apps Android feature parity.

### GitHub releases

Pull requests and pushes to `stable` are tested by GitHub Actions, with a debug APK attached to each successful CI run. Every push to `unstable` also tests, lints, and publishes a signed `TipStroke-dev.apk` to the rolling [dev prerelease](https://github.com/khonager/TipStroke/releases/tag/dev). Dev builds need no version edit or tag. They use the same signing key and package ID as stable builds, with an automatically increasing internal Android version code.

To publish a numbered stable release from `stable`:

1. Update `versionName` in `app/build.gradle.kts`.
2. Create a signed Android keystore and add these GitHub Actions repository secrets: `TIPSTROKE_SIGNING_KEY` (the keystore encoded with `base64 -w 0`), `TIPSTROKE_KEYSTORE_PASSWORD`, `TIPSTROKE_KEY_ALIAS`, and `TIPSTROKE_KEY_PASSWORD`.
3. Push a tag on `stable` matching the version name, such as `v0.1.0`.

The release workflow tests and lints the project, verifies the APK signature, and attaches `TipStroke-vX.Y.Z.apk` plus its SHA-256 checksum to a GitHub release. Keep the keystore and its passwords backed up: Android will not install an update signed with a different key over an existing installation.

Obtainium users who want dev builds need to include prereleases and filter APK
asset names to `^TipStroke-dev\.apk$` to exclude Linux packaging artifacts. For a rolling release with the same `dev` tag, enable **Use latest asset upload as release date** and **Use release date as version string** so replacing the APK is detected as an update.

## Layers and images

Open **Layers** in the top bar. Add paint layers with **+ Paint** or use **+ Image** to choose any raster format Android can decode on the device. Layer order, visibility, and opacity are editable. Select an image and tap **Move & resize on canvas**: drag to move, pinch to resize, and twist to rotate it. Choose **Eraser** to hide image pixels through a source-resolution mask; the imported file remains untouched. Imported images use a persisted document URI as the authoritative original and store transforms and masks separately, so scaling down and back to 100% does not resample or discard the original pixels.

Tap **Select**, choose **Lasso** or **Rectangle**, and draw with a finger or stylus to constrain edits on the current paint or image layer. On paint layers, choose **Move** and drag to reposition only the selected pixels; the move is undoable. **Adjust** beside the tip tools opens a compact Tip Studio with hardness, pressure-size, pressure-opacity, and speed-taper controls. TipStroke remembers size, opacity, and tuning separately for every tip and for the eraser.

Stylus side buttons are configurable in Settings. By default, the primary button switches between the current tip and eraser, and the secondary button undoes. TipStroke accepts standard Android stylus events plus the Page Up/Page Down key events reported by Xiaomi Smart Pen 2 on Pad 6; short presses are intended because the system may reserve Xiaomi long-press shortcuts.

Pencil and Ink use TipStroke-owned Jetpack Ink 1.1 custom families. Pencil combines pressure, tilt, barrel orientation, speed taper, and clustered procedural paper tooth; Ink uses a smooth pressure nib with pointed entry and exit tapers. Airbrush joins its pressure-sized samples into one native raster silhouette and blurs that mask once, matching the soft eraser technique without stacked translucent outlines. Its wet preview and sparse-tile commit share the same painter.

On Android 14+, **Tips → Jetpack Ink** offers Round, Stamp, Graphite, and Soft
comparison brushes alongside the existing custom tips. **Create tip** lets you
choose Custom tip or Jetpack Ink tip; Ink Studio includes a native drawing pad
and shape, texture, flow, pressure, and tilt controls. Large Ink marks can still
pause at pen-up during raster tile completion. See [Brush library](docs/BRUSH_LIBRARY.md)
for the controls and supported features. iOS integration follows separately.

## Gallery, export, and gestures

TipStroke starts in **Your drawings**. Create a named canvas up to 8192×8192, or reopen a local project with its paint tiles, image sources, transforms, layer ordering, and opacity intact. Projects autosave every 30 seconds, when the app backgrounds, and before returning to the gallery.

Long-press a drawing or stack and drag it to reorder the gallery. Drop a drawing in the center of another drawing to create a stack, or in the center of an existing stack to add it. Open a stack to reorder its drawings, rename it, move drawings back out, or unstack everything. Gallery organization is local metadata and never rewrites the drawing packages.

Use **Export** in the editor to save PNG, JPEG, or WebP at canvas size, 50%, or 25%. PNG and WebP can preserve a transparent background; JPEG always composites onto white.

Open **Settings** from the gallery to assign one-finger drag/hold and two-/three-finger taps. One-finger actions include canvas navigation, tile-local smudging, and color picking; tap actions include undo, redo, or disabled. Hold delay, smudge strength, and canvas rotation lock are configurable and stored locally.

See [architecture](docs/ARCHITECTURE.md), [input behavior](docs/INPUT.md), and the [reference-device checklist](docs/TESTING.md).

### Create and share tips (Android)

Open the **Tips** grid icon on the canvas to select tips, manage favorites, or create a
new tip from shape and grain sources. Import images or draw your own sources,
then tune spacing, scatter, rotation, flow, pressure, tilt, taper, and color dynamics
in Tip Studio's native drawing pad. Favorite icons show the actual stroke; hover
with a stylus or hold an icon to see its name. Slider endpoints preview minimum
and maximum settings. Save, duplicate, export, and share `.tipbrush`
files with their textures included.

Tip preview icons use the selected drawing color and update when it changes.
Light and dark colors use contrasting neutral preview backgrounds.
The favorites bar never scrolls: its capacity follows the available screen space.
The full library stays one tap away. Public publishing and community rankings are
not connected. See [Tip library](docs/BRUSH_LIBRARY.md) for supported controls and
format details.

On Android, choose **Image / GIF / video** in the gallery to make a canvas from
media. Images use their oriented pixel dimensions. GIFs and videos stay as one
local source file. Video plays through Android's native player at its original
speed. Scrub, choose **Add drawing here**, and set how long each drawing holds.
Drawing FPS sets the drawing grid without changing video speed or existing timings.
Export smooth video with held drawings, or sample the video at drawing FPS; both
keep the source duration. MP4 is silent. GIF and PNG-sequence export are also available.
Import supports 16 megapixels and 512 MiB per source (32 MiB for GIF). Projects
support 10,000 authored drawings; export supports 36,000 output frames.


For longer Android animations, inactive drawings now move to private disk storage
instead of keeping every edited frame in RAM. Use animation frames / **Add drawing
here** for time; the layer limit applies to the layer stack, not the number of
drawings. Three minutes at 12 drawing FPS is 2,160 drawings. Undo history outside
the active/onion-skin range is released when those drawings move out of memory.

Use **Resize → Reduce resolution** to scale the whole drawing, including animation
frames, to 50% or 25% per side. Layers remain separate and timing stays unchanged.
Half-size dimensions need roughly a quarter of the raster pixels and increase the
canvas's layer allowance (up to 64). This permanently removes pixel detail and
clears undo history; export a `.tipstroke` copy first to keep the original.
Image/video source files retain their quality. Tablet playback/export performance
still depends on the artwork and device; MP4 exports currently have no audio.

### Single-track video editing (Android)

Open **Animation → Add photo / video** on a blank canvas, or import a video from
the gallery and choose **Edit clips & keyframes**. Use **+ Media** to combine
photos, GIFs, and video clips on one track. Photos start at five seconds; change
that with **Trim / duration**. Timeline actions use icons; hover with a stylus or
mouse, or hold an icon, to see its action name.

Select a clip, scrub or use **Go to time**, then **Split here**. Split twice and
**Delete clip** to remove a middle section. **Move earlier/later** reorders
sections; **Duplicate clip** repeats one. Edits close gaps automatically. Video
trim handles refer to the original source, so trimmed footage can be restored
after saving and reopening.

Use **Keyframe here** at two different times to animate position, scale, rotation,
and opacity, with Linear, Smooth, or Hold interpolation. Tap a diamond to edit or
delete a keyframe. **Transition** sets a crossfade into the selected clip; zero
means a cut. **Undo edit / Redo edit** retain the last 50 timeline edits during
the session. Drawing overlays keep their positions on the overall timeline when
clips move; they do not follow clips.

Saves and `.tipstroke` packages include the sources, cuts, keyframes, and
transitions. MP4, GIF, and PNG-sequence export use the edited timeline. MP4 is
silent; audio editing, automatic image morphing, and iOS/desktop video editing
are not included. Edited previews decode on a worker and may drop frames on
slower devices without changing timeline speed or exported timing.
