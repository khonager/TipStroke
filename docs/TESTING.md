# Xiaomi Pad 6 + Smart Pen manual checklist

Install with `adb install -r app/build/outputs/apk/debug/app-debug.apk`, enable the debug overlay, and test in both landscape and portrait.

## Pen

1. Relaunch, immediately draw the first Ink stroke, and check that it starts under the nib without a warm-up hitch.
2. Draw slow diagonals, circles, tight curves, quick flicks, and intersecting strokes. Check continuity and jitter.
3. Vary pressure from feather-light to firm. Check width response and smooth taper.
4. Lift the pen while watching the final segment. Check for gap, flash, doubled opacity, width jump, or color shift.
5. Repeat with Pencil and Airbrush. Note that the current Airbrush wet preview becomes softer at commit.
6. Toggle Eraser and use the hardware eraser tool if reported. Verify pixels become transparent (white v0 canvas shows through) and undo restores them.
7. Press each stylus button while drawing and confirm the debug/input path remains stable; mappings are intentionally not assigned yet.

## Touch and camera

1. Drag with one finger and confirm it neither paints nor moves the canvas.
2. With two fingers, pan, pinch, and rotate simultaneously; verify sharp tiles at high zoom.
3. Put a palm down during a pen stroke. Confirm it does not paint, move the camera, or cancel/corrupt the stroke.
4. Two-finger tap repeatedly to undo; three-finger tap to redo.
5. Rotate the device in both directions and use **Fit** to recover the canvas.

## Stress

1. Fill marks across all four canvas quadrants and watch allocated/dirty tile counts remain local to strokes.
2. Draw and erase rapidly for five minutes while watching frame rate, memory, and thermal behavior.
3. Background/foreground the app, lock/unlock the tablet, rotate during idle, then continue drawing.
4. Trigger interrupted gestures and app-switch during contact to exercise cancellation.

Record a 120/240 fps external video of nib and pixels for input-to-photon/handoff review; unit tests cannot measure drawing feel.
