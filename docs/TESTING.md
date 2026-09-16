# Xiaomi Pad 6 + Smart Pen manual checklist

Install with `adb install -r app/build/outputs/apk/debug/app-debug.apk`, enable the debug overlay, and test in both landscape and portrait.

## Pen

1. Relaunch, immediately draw the first Ink stroke, and check that it starts under the nib without a warm-up hitch.
2. Draw slow diagonals, circles, tight curves, quick flicks, and intersecting strokes. Check continuity and jitter.
3. Vary pressure from feather-light to firm. Check width response and smooth taper.
4. With Pencil, repeat while changing stylus tilt and barrel orientation. A low angle should produce a broader, flatter graphite patch aligned with the stylus, with visible paper grain and no clipped tile seams.
5. Lift the pen while watching the final segment. Check for gap, flash, doubled opacity, width jump, texture jump, or color shift.
6. With Airbrush, draw fast and slow passes, pause briefly in place, and cross the same area. It should show discrete pigment particles without circular stamp outlines; slow/held passes should build density.
7. Repeat Pencil and Airbrush at 100%, 1200%, and after camera rotation. The final mark must stay in document coordinates, preserve canvas-pixel resolution, and show no tile-boundary clipping.
8. Erase across marks on multiple layers. Lower layers should be revealed during the gesture, with no visual change at pen-up; canceling a stroke should restore it.
9. Resize an image with two fingers, lift either finger, pause, then continue dragging with the remaining finger. The image must stay in place at the handoff and resume smoothly after the drag threshold.
10. Toggle Eraser and use the hardware eraser tool if reported. Verify pixels become transparent (white v0 canvas shows through) and undo restores them.
11. Press each stylus button while drawing and confirm the debug/input path remains stable; mappings are intentionally not assigned yet.

## Selection and brush memory

1. Draw across several tiles, make a rectangle selection, choose **Move**, and drag from inside the selection. Only selected pixels should move on lift; undo and redo must restore both locations.
2. Repeat with a curved freehand lasso and verify pixels inside the polygon move while nearby pixels outside it remain unchanged.
3. Select an imported image, make a lasso selection, and erase across its edge. Erasing must remain inside the lasso; save, reopen, resize to 100%, and verify the original-resolution mask remains aligned.
4. Give Pencil, Ink, Airbrush, and Eraser visibly different size/opacity values. Switch among them and relaunch the editor; each tool must restore its own values.
5. In Brush Studio, change Airbrush hardness and toggle pressure/speed behavior. Confirm the next stroke changes while previous raster marks remain identical.

## Touch and camera

1. Drag with one finger and confirm it neither paints nor moves the canvas.
2. With two fingers, pan, pinch, and rotate simultaneously; verify sharp tiles at high zoom.
3. Put a palm down during a pen stroke. Confirm it does not paint, move the camera, or cancel/corrupt the stroke.
4. Two-finger tap repeatedly to undo; three-finger tap to redo.
5. Rotate the device in both directions and use **Fit** to recover the canvas.

## Stress

1. Fill marks across all four canvas quadrants and watch allocated/dirty tile counts remain local to strokes.
2. Draw and erase rapidly for five minutes while watching frame rate, memory, and thermal behavior.
3. Make several long Airbrush passes at maximum size and 1200% zoom. Confirm event rate remains responsive and memory does not grow continuously as tuning values are changed.
4. Background/foreground the app, lock/unlock the tablet, rotate during idle, then continue drawing.
5. Trigger interrupted gestures and app-switch during contact to exercise cancellation.

Record a 120/240 fps external video of nib and pixels for input-to-photon/handoff review; unit tests cannot measure drawing feel.
