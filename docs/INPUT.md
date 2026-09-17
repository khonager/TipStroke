# Input

## Routing

- `TOOL_TYPE_STYLUS` draws and captures pressure, tilt, orientation, timestamps, button state, pointer ID, and cancellation.
- `TOOL_TYPE_ERASER` uses the same brush engine with clear blending.
- The primary and secondary stylus buttons are edge-triggered and configurable. Defaults are **Switch brush / eraser** for the primary button (normally nearest the tip) and **Undo** for the secondary button. Mappings are stored locally and can also be set to redo or disabled.
- Button input accepts Android's standard stylus `MotionEvent` bits, legacy primary/secondary/tertiary mappings from stylus-class devices, and Android 14's dedicated stylus `KeyEvent` codes. Xiaomi Pad 6 compatibility additionally recognizes the Smart Pen 2's observed `KEYCODE_PAGE_UP`/`KEYCODE_PAGE_DOWN` events while the editor is active. Duplicate motion/key delivery within one physical press is suppressed.
- Finger behavior comes from local `GestureSettings`. Defaults are one-finger drag to navigate, one-finger hold to pick color, two-finger tap to undo, and three-finger tap to redo.
- One-finger drag can instead smudge the selected paint layer. Smudge snapshots only affected tiles and groups the whole finger gesture into one undo transaction.
- Image transform mode temporarily takes priority over configured finger actions. One-finger drag moves the selected image; two-finger translation, pinch, and twist move, resize, and rotate it. When one finger of a two-finger transform lifts, the gesture rebases to the remaining finger so continued movement is smooth and does not jump. Leaving transform mode restores the configured actions.
- Two or more fingers continue to pan, zoom, and rotate the camera simultaneously; rotation can be locked.
- Select mode temporarily routes a one-pointer finger or stylus drag to either a freehand lasso or rectangular document-space selection. The selection constrains subsequent paint-layer drawing and erasing. Move translates only the selected pixels on the active paint layer, snapshots only affected source/destination tiles, and creates one undo step. On an image layer the selection constrains edits to the image's original-resolution erase mask; whole-image movement remains the image transform mode. Select All, Clear, and Done are contextual controls; clearing the selection restores unconstrained editing.
- Mouse data has a domain representation but mouse painting is not enabled in this Android milestone.

Stylus has priority. Once a stylus stroke is active, touch cannot navigate or corrupt it. `ACTION_CANCEL` cancels the active wet renderer and drops pending samples. `requestUnbufferedDispatch` and Jetpack motion prediction are used for lower latency.

The camera matrix transforms screen events to document coordinates once at input. Navigation changes the view matrix only and never resamples document pixels.

Color picking opens a native magnifying loupe after the configured hold delay. The loupe follows the finger, shows enlarged composite pixels, outlines the pending color, and suppresses navigation while active. The active brush color changes only when the finger lifts; cancellation or adding another finger dismisses the loupe without changing color. A stylus always retains priority and always draws regardless of finger mappings.

## Brush controls

Brush size and opacity use drag tracks with persistent numeric readouts. In landscape they are stacked as narrow vertical controls: plus sits at the maximum end and minus at the minimum end. Portrait keeps the wider horizontal controls. The buttons apply 1 px and 1 percentage-point adjustments for precise tuning without requiring pixel-perfect slider motion.

The compact color dock always exposes the current drawing's most-used colors. In landscape it sits in the top-right toolbar immediately after Layers; in portrait it remains above the bottom adjustment controls. Tapping the large current-color circle opens the color chooser; canvas hold remains the eyedropper gesture for sampling existing artwork. Adjust opens the compact Brush Studio for edge hardness, pressure-to-size, pressure-to-opacity, and optional speed taper. Tuning, size, and opacity are stored locally for each built-in brush; the eraser has its own remembered size, opacity, hardness, and dynamics.

## Planned extension points

Gesture routing can later add swipe actions, a quick menu, fullscreen toggle, and additional user mappings without adding work to the sample path.

Remote/air actions are intentionally outside the generic button path. Samsung's remote S Pen SDK is model-specific, and Xiaomi reserves long presses for system note/screenshot shortcuts; TipStroke targets short in-range/contact button presses through Android input APIs first.
