# Xiaomi Pad 6 + Smart Pen manual checklist

## Linux laptop loop

Run `./tipstroke laptop-setup` once, then use `./tipstroke laptop` after changes. The latter starts the `TipStroke_Tablet_API_35` AVD if needed, assembles and installs the debug APK, and opens the app.

For fast functional checks, primary-drag with a mouse, trackpad, or pen tablet to paint; secondary/middle-drag to pan; scroll to pan; Ctrl/Meta+scroll to zoom; and use the emulator's Ctrl+drag gesture for two-finger navigation. Confirm selection, layer, gallery, persistence, and export flows here. A host pen tablet may be reduced to full-pressure mouse input by the emulator. Always return to the physical-device checklist below for pressure/tilt response, hover/buttons, palm rejection, stroke latency, and wet/final handoff.

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
