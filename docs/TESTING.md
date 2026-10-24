# Xiaomi Pad 6 + Smart Pen manual checklist

## Linux laptop loop

Run `./tipstroke laptop` after changes. It opens TipStroke as a regular Linux desktop application; it does not start Android or require an SDK. Primary-drag with a mouse, trackpad, or pen tablet to paint; secondary/middle/Space-drag to pan; scroll to pan; and Ctrl/Meta+scroll to zoom. Check all four tools, undo/redo, fit, PNG open/export, canvas-edge clipping, and drawing across 256 px tile boundaries.

The desktop renderer is intentionally smaller than Android: it uses constant-pressure JVM pointer input and does not currently load TipStroke project packages, layers, selections, or Jetpack Ink. Run `./tipstroke emulator-setup` once and `./tipstroke emulator` when a test needs the complete Android UI without a physical device. Always return to the physical-device checklist below for pressure/tilt response, hover/buttons, palm rejection, stroke latency, and wet/final handoff.

Install with `adb install -r app/build/outputs/apk/debug/app-debug.apk`, enable the debug overlay, and test in both landscape and portrait.

Before subjective pen testing, run `./tipstroke brushes` with a device attached. It renders the exact production Pencil and Airbrush paths, fails on empty/near-invisible output, and writes review PNGs under `drawing-android/build/outputs/connected_android_test_additional_output/`. Inspect those images whenever a brush renderer or Ink version changes; ordinary JVM tests cannot load Ink's native implementation.

## Pen

1. Relaunch, immediately draw the first Ink stroke, and check that it starts under the nib without a warm-up hitch.
2. Draw slow diagonals, circles, tight curves, quick flicks, and intersecting strokes. Check continuity and jitter.
3. Vary pressure from feather-light to firm. Check width response and smooth taper.
4. With Pencil, repeat while changing stylus tilt and barrel orientation. A low angle should produce a broader, flatter graphite patch aligned with the stylus, with visible paper grain, subtly irregular connected edges, and no clipped tile seams. At 100% opacity, covered graphite pixels must fully replace the color below while paper-tooth gaps remain untouched.
5. Lift the pen while watching the final segment. Check for gap, flash, doubled opacity, width jump, texture jump, or color shift.
6. With Airbrush, draw fast and slow passes and cross the same area. It should read as one blurred stroke with no parallel/concentric silhouettes, input-segment banding, particles, rings, or circular stamp outlines. At 100% opacity its center must fully replace the color below while only the feathered edge remains translucent. Raise hardness and confirm that the feathered region contracts toward a solid disk; lower overall opacity for lighter buildup.
7. Repeat Pencil and Airbrush at 100%, 1200%, and after camera rotation. The final mark must stay in document coordinates, preserve canvas-pixel resolution, and show no tile-boundary clipping.
8. Erase across marks on multiple layers. Lower layers should be revealed during the gesture, with no visual change at pen-up; canceling a stroke should restore it.
9. Resize an image with two fingers, lift either finger, pause, then continue dragging with the remaining finger. The image must stay in place at the handoff and resume smoothly after the drag threshold.
10. Toggle Eraser and use the hardware eraser tool if reported. Verify pixels become transparent (white v0 canvas shows through) and undo restores them.
11. In Settings, leave the primary/lower button on **Switch brush / eraser** and the secondary/upper button on **Undo**. Test each with the pen hovering, touching, and moving. Each physical press must fire exactly once; holding a button must not oscillate tools or repeatedly undo. The selected tool in the rail must follow hardware switching.
12. Remap both buttons to redo/disabled and relaunch TipStroke. Confirm mappings persist. On Xiaomi Smart Pen 2, use short presses; long presses may remain reserved by HyperOS for the note and screenshot shortcuts.
13. Repeat the button tests on an S Pen device if available. In-range/contact presses should use Android's standard stylus events; Bluetooth remote/air actions are not part of this test.

## Selection and brush memory

1. Draw across several tiles, make a rectangle selection, choose **Move**, and drag from inside the selection. Only selected pixels should move on lift; undo and redo must restore both locations.
2. Repeat with a curved freehand lasso and verify pixels inside the polygon move while nearby pixels outside it remain unchanged.
3. Select an imported image, make a lasso selection, and erase across its edge. Erasing must remain inside the lasso; save, reopen, resize to 100%, and verify the original-resolution mask remains aligned.
4. Give Pencil, Ink, Airbrush, and Eraser visibly different size/opacity values. Switch among them and relaunch the editor; each tool must restore its own values.
5. In Brush Studio, change Airbrush hardness and toggle pressure/speed behavior. Confirm the next stroke changes while previous raster marks remain identical. Turn off Pencil pressure-opacity and pressure-size independently and confirm drawing remains stable without a crash.
6. Drag the size and opacity controls and use their step buttons at 100%, 25%, and 1200% zoom. A transient stamp should remain centered while adjusting, show the selected color/opacity and supported edge softness, scale with zoom exactly like a stroke, disappear after release, and never create pixels or an undo entry. Repeat with Eraser and confirm its neutral preview remains visible.

## Animation and selection checks

1. Enable animation on an existing drawing, choosing both **background** and **first frame** in separate runs. Add, duplicate, reorder, and delete frames. Set a hold, then verify playback keeps that frame visible for the requested number of ticks.
2. Put a layer in the shared background, switch frames, and confirm its content stays visible. Return it to animated content and confirm it can vary by frame without copying the other layers.
3. Enable onion skins, draw on neighboring frames, and confirm their ghosts follow the current frame. Start playback and confirm ghosts disappear during playback.
4. Draw a lasso around sparse pixels, choose **Move**, and drag them. Repeat with **Move selection to new layer**; verify source pixels are removed, the new layer contains only selected pixels, and the app does not crash.
5. Import an image, arm motion recording across three or more frames, and drag it up and down with a finger. Scrub the resulting frames, then play them. Verify distinct positions, including the final position, and repeat with **fit range** and **real time** timing.
6. Start **Record drawing live** with one frame. Confirm the canvas plays and new frames appear at the chosen FPS while drawing. Draw a long stroke across frame boundaries, then several more strokes without restarting recording. Stop, scrub, and play to verify each stroke appears in the frames corresponding to its drawing time. Test normal loop, ping pong, and play once.
7. Save and reopen the project. Confirm frame order, holds, shared background, image positions, and playback settings survive. Export MP4, GIF, and PNG sequence; open each output and check frame order, duration, and background composition. Also export a still PNG from the selected frame.

## Touch and camera

1. Drag with one finger and confirm it neither paints nor moves the canvas.
2. With two fingers, pan, pinch, and rotate simultaneously; verify sharp tiles at high zoom.
3. Put a palm down during a pen stroke. Confirm it does not paint, move the camera, or cancel/corrupt the stroke.
4. Two-finger tap repeatedly to undo; three-finger tap to redo.
5. Rotate the device in both directions and use **Fit** to recover the canvas.

## Gallery organization

1. Long-press and drag drawings and stacks by their edges to reorder them; scroll by holding near the top or bottom edge.
2. Drop one drawing in the center of another to create a stack, then drop another drawing onto that stack.
3. Open the stack, reorder its drawings, rename it, and move a drawing out. A stack with one remaining drawing should dissolve automatically.
4. Relaunch TipStroke and verify top-level order, stack name, membership, and internal order persist.
5. Delete a stacked drawing and verify its project is removed while the gallery index remains valid. Unstack and confirm all project contents and thumbnails are unchanged.

## Stress

1. Fill marks across all four canvas quadrants and watch allocated/dirty tile counts remain local to strokes.
2. Draw and erase rapidly for five minutes while watching frame rate, memory, and thermal behavior.
3. Make several long Airbrush passes at maximum size and 1200% zoom. Confirm event rate remains responsive and memory does not grow continuously as tuning values are changed.
4. Background/foreground the app, lock/unlock the tablet, rotate during idle, then continue drawing.
5. Trigger interrupted gestures and app-switch during contact to exercise cancellation.

Record a 120/240 fps external video of nib and pixels for input-to-photon/handoff review; unit tests cannot measure drawing feel.

## Screen lock, recreation, and diagnostics

Automated coverage: `EditorPersistenceTest` verifies painted pixels and a positioned image across pending saves and a replacement editor with a stale “new drawing” flag, failed-load overwrite prevention, missing-document handling, and interrupted directory replacement recovery. `MainActivityRestorationTest` exercises actual activity recreation and a fresh activity restored from a saved bundle, preserving image layers, scale, paper color, and document identity. `LocalDiagnosticsTest` checks crash persistence/delegation, rotation limits, clearing, and failure to write diagnostics.

1. Create a new drawing, draw several strokes, import an image, and move/resize/rotate it. Lock and unlock within 30 seconds (before periodic autosave). Verify both paint and image placement, then return to the gallery and reopen.
2. Repeat with Developer options **Don't keep activities** enabled, backgrounding the app and returning. Also trigger a non-handled configuration change such as system light/dark mode while editing. Disable the developer option afterward.
3. With the app backgrounded and a completed save visible in diagnostics, use `adb shell am kill dev.tipstroke.app` and resume from Recents. The same document should reopen. Force-stop is a separate user action and does not guarantee task restoration.
4. Repeat while a larger drawing is saving, adding an edit before locking. After reopening, check the last edit and inspect queued/completed save events.
5. Open **Settings → Troubleshooting → Crash and debug log**. Verify process start/version, activity events, and save/load results. Copy the report and paste it into a text editor; clear it and refresh.
6. On a disposable debug installation, exercise an uncaught exception with a debugger. Relaunch and verify the exception, cause, and stack trace remain in settings. On Android 11+, inspect system exit reasons too; a system memory kill or native crash need not have a Java stack trace. Do not deliberately crash while working on valuable unsaved artwork.

## Drawing-progress recording

Run `./gradlew :drawing-android:testDebugUnitTest --tests '*ProcessRecordingTest'` for exact raster replay across tile boundaries, Pencil segment replay, eraser transactions, undo branches, save/reopen/continue, seeking, layer changes, corrupt-file isolation, the storage cap, GIF output, complete settings round-trip, and frozen v1 fixtures. Native Ink and MP4 require `./gradlew :drawing-android:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.tipstroke.drawing.android.ProcessRecordingInstrumentedTest` on Android. The native test checks exact final Ink pixels, compact sample storage, and an MP4 that the platform decoder can open.

Manual first-version test:

1. Create a drawing; use Pencil, Ink, Airbrush, and pixel tools with different settings, crossing tile boundaries. Erase, undo/redo, move a selection, and add/hide/reorder layers.
2. Open **Export → Drawing progress**. Play, pause, restart, and scrub. Compare the end with the canvas. Return to drawing and verify the artwork/undo stack did not change.
3. Save, leave, reopen, add more marks, and replay again. Earlier strokes must retain their original brush settings. Export MP4 and GIF and open both outside TipStroke.
4. Open an old raster-only project: replay starts from its current artwork, not its lost past. Imported-image and animation projects show the unsupported-operation stop reason.
5. Import a 32×32 image during recording, enlarge it past 16×, move/rotate it, erase and undo its mask, then paint on another layer. Replay and export must reach the current artwork, including after reopening. A legacy image-stopped recording must resume with an explicit current-artwork checkpoint and a gap notice. Canvas resizing still stops recording with a reason and preserves the earlier prefix. Stress large selections/undo to exercise the queue/archive limits.
6. Test rapid stylus input and long sessions on the physical tablet with recording enabled; watch frame pacing, memory, and heat. Native Ink exactness must be rechecked whenever its dependency changes.

`AnimatedFillTest` covers circular wave shape and speed, transparent and flattened
antialiased edge overlap, preservation of solid outlines, containment, a one-pixel leak, sparse tile boundaries,
selection clipping, isolated preview cancellation, no-op colors, and a single
undo/redo transaction. `ColorDropTest` verifies tap-versus-drag behavior and window
coordinates when releasing outside the circle. On a device, drag the current color circle in portrait and
landscape; repeat after pan/zoom/rotation, undo a leaking fill, close the line,
refill, undo, and reopen the saved drawing. Tapping the circle must still open the
picker. Verify translucent colors and reduced layer opacity match the final fill.

`FillRevealTest` verifies the 2,500 ms timeline across areas and zoom levels, native
mask rendering, drawing/erasing during reveal, and undo order. `DrawingSurfaceFillTest`
checks pan and drawing input during an actual worker-prepared reveal, stale-result
recomputation after edits, and cancellation without a delayed commit. On-device,
repeat with Pencil and eraser while panning/pinching, change layers, stop the effect,
and save midway; the saved image must contain the full fill and subsequent strokes.

Fill edge regressions cover two-pixel diagonal corners, repeated recoloring next
to solid outlines, translucent old-color fringes with exact alpha preservation,
and an open background spanning 153 tiles (past the former 32 MiB rejection).
Worker cache coverage reads beyond 128 source tiles and then reloads an evicted
tile. Spill commit/undo checks verify the background changes while solid ink stays
intact. Repeat on a large device canvas with open and closed contours.

Fill-speed tests cover preference defaults, persistence and clamping, configured
250–5,000 ms reveal clocks, immediate drop feedback, hover preparation without a
commit, immediate reuse of a ready result, large-hover yielding/resumption,
cancelled or invalidated hover work, and a nearby drop across a closed boundary.
On-device, compare a quick drag against pausing briefly over the destination,
change the duration in Settings, and check pan/drawing responsiveness on a large
fill. Preparation can still take time; the duration setting controls the reveal.

Export regressions: `AnimationExporterTest` decodes all GIF pixels with the host JVM ImageIO decoder, exercising LZW dictionary growth/reset over several noisy odd-sized frames. `ProcessImageGestureTest` checks that many drag moves create exactly one release action. Device `ProcessRecordingInstrumentedTest` also decodes short, single-frame, odd-sized MP4s and overwrites an existing export; `ProcessRecordingPlayerTest` checks stable control bounds while awaiting playback frames.

## Layer interaction regression checks

- Select a layer: its settings stay closed. Open ⋮, close with Done or outside dismissal, and confirm the same layer remains selected.
- Long-press a row and move in both axes, including edge scrolling. The floating row must retain the original finger grab point and rows must reveal the pending insertion slot. Release over a name to reorder, over a preview to group, or cancel to keep the original order.
- Select a group by its heading; add/remove individual layers with right swipes. Erase across selected paint and image layers, then cancel or undo/redo. Unselected layers and original image assets must remain unchanged.
- Move a marquee across several selected layers. Artwork must follow before release. Cancel must leave pixels untouched; release and undo/redo must affect all targets together. Move with no marquee selects the full canvas.

`LayerDropTargetTest` covers insertion/group boundaries. `DrawingSurfaceMultiLayerTest` covers live native move rendering, cancellation, mixed paint/image erasing, unselected-layer isolation, and compound undo/redo.

`LayerDragLifecycleTest` drives a real Compose long press, movement, release and
cancellation with a composed floating row. It verifies reorder happens only on
release and covers invalid drag-coordinate rounding. This runs under Robolectric;
confirm physical tablet behavior separately, using Troubleshooting crash logs if
any device-specific failure remains.

`GalleryLandscapeTest` verifies a 800 × 360 dp window with twelve extreme portrait
drawings: thumbnail heights remain bounded, the grid keeps most of the viewport,
and scrolling reaches the last drawing while the heading scrolls away. Phone
landscape screenshot cases now set matching Android display qualifiers as well as
view dimensions, including gallery and layers. Review phone portrait, tablet
portrait/landscape and resized-window screenshots alongside these cases.

`LayerDragLifecycleTest` also opens the canvas background editor, switches among
its three color modes, and verifies a hex value updates the background. Inspect
the layer drag outline on a device for visual confirmation: the selected layer
should retain orange, while a potential insertion uses a blue line.

Selection regression coverage: `SelectionRegionTest` checks boolean containment,
translation and reverse-drag ellipses. Native-graphics `ReferenceStrokesTest` checks
cross-tile soft coverage, source preservation, same-layer alpha preservation,
live cancellation, undo/redo, empty sources and selection-constrained erasing.

`LayerClippingTest` exercises sparse/soft clipping, non-destructive removal,
source edits, undo/redo, cycle rejection, source deletion, project save/load,
transparent PNG export and process replay. Selection screenshot fixtures cover
portrait and phone controls in the former slider area; no Android device was
required for these native-graphics Robolectric checks.

Quick-shape regressions: `QuickShapeTest` exercises fitted geometry, rotated
rectangles, arrows, degenerate strokes, and selection constraints in all drag
quadrants. `DrawingSurfaceShapeTest` exercises native hold timing, preview,
commit/undo, cancellation, and modifier-pointer ownership. On a stylus device,
check 650 ms holds with Pencil, Ink, Airbrush and Eraser, including selections
under a rotated/zoomed camera, and check that palm/finger release never commits
the pen stroke. Check that a moving stroke remains freehand and short pauses do
not snap; inspect pressure/tilt appearance before and after recognition.
The JVM shape tests cover Airbrush preview/commit and live-tile eraser rollback
with each regular brush preset. Jetpack Ink's wet-preview replacement for Ink
and Pencil requires a device/emulator: the Android Ink native library cannot be
loaded by this project's desktop Robolectric runtime.
