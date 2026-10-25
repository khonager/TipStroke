# iPhone and iPad

TipStroke now has a native iOS target in `ios/TipStroke.xcodeproj`, with the shared
`TipStroke` scheme. It targets iOS/iPadOS 16 or newer and both device families.
The app uses UIKit and Core Graphics with no third-party packages, account,
network service, or cross-platform drawing surface.

This is the first iOS drawing implementation, not Android feature parity. The
Android app and its Gradle modules remain independent.

## Included

- Local gallery, named canvases from 16–4096 px per side, reopening and deletion.
- Native Pencil/finger drawing, coalesced samples, Pencil/Ink/Eraser tools,
  brush size, opacity and the system color picker.
- Two-finger pan/pinch, fit canvas, portrait/landscape layouts, and iPad resizing.
- Up to 32 paint layers with stable identities, selection, names, ordering,
  visibility, opacity and deletion.
- Transparent erasing, cancellable strokes, bounded stroke undo/redo.
- Automatic local saves and flattened PNG sharing through the system share sheet.
- Apple Pencil double-tap switches between the current drawing tool and eraser.

Finger drawing defaults to enabled so the app works immediately on iPhone.
Turn off **Draw with finger** in brush settings when drawing with Pencil on iPad.
A second finger cancels an unfinished finger mark and starts navigation. Pencil
input takes priority over finger drawing. Supported Pencil hardware supplies force;
fingers, pressureless pencils and estimated force samples use a constant fallback.
Tilt, hover, predicted samples, late force corrections, squeeze and barrel roll
are not implemented yet. Native brush marks do not reproduce Jetpack Ink textures.

Animation, process recording, selections, fills, smudge, photo import/warping,
Android project-package transfer and advanced brush tuning remain Android-only.
Layer metadata edits are saved but not in stroke undo history; deleting a layer
clears that history after confirmation. The iOS gallery currently lists names and
sizes rather than artwork thumbnails. PNG export composites onto white paper.

## Build with Codemagic

The repository-root `codemagic.yaml` defines two workflows:

1. **ios-simulator** needs no Apple credentials. It runs raster/persistence tests
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

The configuration follows Codemagic's [native iOS build guide](https://docs.codemagic.io/yaml-quick-start/building-a-native-ios-app/)
and [signing instructions](https://docs.codemagic.io/yaml-code-signing/signing-ios/).
No Codemagic build or Apple submission is performed merely by adding these files.

## Local macOS development

Open `ios/TipStroke.xcodeproj` in Xcode 16 or newer, choose **TipStroke**, and run
an iPhone or iPad simulator. For a physical device, select your Apple development
team in Signing & Capabilities. There is no CocoaPods or Swift Package resolution.

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
