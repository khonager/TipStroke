# Input

## Routing

- `TOOL_TYPE_STYLUS` draws and captures pressure, tilt, orientation, timestamps, button state, pointer ID, and cancellation.
- `TOOL_TYPE_ERASER` uses the same brush engine with clear blending.
- Finger behavior comes from local `GestureSettings`. Defaults are one-finger drag to navigate, one-finger hold to pick color, two-finger tap to undo, and three-finger tap to redo.
- One-finger drag can instead smudge the selected paint layer. Smudge snapshots only affected tiles and groups the whole finger gesture into one undo transaction.
- Image transform mode temporarily takes priority over configured finger actions. One-finger drag moves the selected image; two-finger translation, pinch, and twist move, resize, and rotate it. When one finger of a two-finger transform lifts, the gesture rebases to the remaining finger so continued movement is smooth and does not jump. Leaving transform mode restores the configured actions.
- Two or more fingers continue to pan, zoom, and rotate the camera simultaneously; rotation can be locked.
- Mouse data has a domain representation but mouse painting is not enabled in this Android milestone.

Stylus has priority. Once a stylus stroke is active, touch cannot navigate or corrupt it. `ACTION_CANCEL` cancels the active wet renderer and drops pending samples. `requestUnbufferedDispatch` and Jetpack motion prediction are used for lower latency.

The camera matrix transforms screen events to document coordinates once at input. Navigation changes the view matrix only and never resamples document pixels.

Color picking opens a native magnifying loupe after the configured hold delay. The loupe follows the finger, shows enlarged composite pixels, outlines the pending color, and suppresses navigation while active. The active brush color changes only when the finger lifts; cancellation or adding another finger dismisses the loupe without changing color. A stylus always retains priority and always draws regardless of finger mappings.

## Brush controls

Brush size and opacity use long drag tracks with persistent numeric readouts. Adjacent minus/plus buttons apply 1 px and 1 percentage-point adjustments for precise tuning without requiring pixel-perfect slider motion.

The compact color dock always exposes the current drawing's most-used colors. Tapping the large current-color circle opens a centered hue/saturation wheel with brightness control for selecting any brush color; canvas hold remains the eyedropper gesture for sampling existing artwork.

## Planned extension points

The captured button state can later map stylus buttons to actions. Gesture routing can later add swipe actions, a quick menu, fullscreen toggle, and additional user mappings without adding work to the sample path.
