# Input

## Routing

- `TOOL_TYPE_STYLUS` draws and captures pressure, tilt, orientation, timestamps, button state, pointer ID, and cancellation.
- `TOOL_TYPE_ERASER` uses the same brush engine with clear blending.
- Stylus hover shows the current brush footprint in document pixels, including Pencil tilt and barrel orientation when the device reports them. **Brush preview while hovering** in Settings can disable it.
- The primary and secondary stylus buttons are edge-triggered and configurable. Defaults are **Switch brush / eraser** for the primary button (normally nearest the tip) and **Undo** for the secondary button. Mappings are stored locally and can also be set to redo or disabled.
- Button input accepts Android's standard stylus `MotionEvent` bits, legacy primary/secondary/tertiary mappings from stylus-class devices, and Android 14's dedicated stylus `KeyEvent` codes. Xiaomi Pad 6 compatibility additionally recognizes the Smart Pen 2's observed `KEYCODE_PAGE_UP`/`KEYCODE_PAGE_DOWN` events while the editor is active. Duplicate motion/key delivery within one physical press is suppressed.
- Finger behavior comes from local `GestureSettings`. Defaults are one-finger drag to navigate, one-finger hold to pick color, two-finger tap to undo, and three-finger tap to redo. One-finger drag can instead be set to **Draw**, which treats a finger or passive capacitive pen as a full-pressure drawing pointer. Android reports both as touch, so they intentionally share this mode. Adding a second finger cancels the provisional touch mark and immediately hands the gesture to two-finger navigation.
- One-finger drag can instead smudge the selected paint layer. Smudge exchanges premultiplied color and alpha between pickup and destination: fully painted areas blend without losing coverage, while transparent edges transport existing pigment instead of creating more. It snapshots only affected tiles and groups the whole finger gesture into one undo transaction.
- Image transform mode temporarily takes priority over configured finger actions. One-finger drag moves the selected image; two-finger translation, pinch, and twist move, resize, and rotate it. When one finger of a two-finger transform lifts, the gesture rebases to the remaining finger so continued movement is smooth and does not jump. Leaving transform mode restores the configured actions.
- Two or more fingers continue to pan, zoom, and rotate the camera simultaneously; rotation can be locked. Scaling and rotation are anchored at the gesture centroid, so the document point between the fingers remains beneath that point while pinching. After a moved multi-finger gesture loses a pointer, navigation rebases to the remaining finger so a configured one-finger drag can continue without a jump.
- Select mode temporarily routes a one-pointer finger or stylus drag to either a freehand lasso or rectangular document-space selection. The selection constrains subsequent paint-layer drawing and erasing. Move translates only the selected pixels on the active paint layer, snapshots only affected source/destination tiles, and creates one undo step. Duplicate copies the selected pixels from each selected paint layer into a new sparse layer while preserving the originals. On an image layer the selection constrains edits to the image's original-resolution erase mask; whole-image movement remains the image transform mode. Select All, Clear, and Done are contextual controls; clearing the selection restores unconstrained editing.
- Primary mouse drag paints through the same native stroke pipeline at full pressure. Secondary or middle drag pans the canvas. Wheel or two-finger trackpad scroll pans; holding Ctrl/Meta zooms around the pointer, and Shift converts a vertical wheel to horizontal pan.
- The Android Emulator presents an ordinary host click as a touchscreen contact. On emulator builds, a single such contact is treated as a full-pressure mouse stroke; adding a second simulated contact cancels the provisional stroke and switches to normal multi-touch navigation.
- Pen tablets use full pressure, tilt, orientation, eraser, and side-button data whenever Android reports `TOOL_TYPE_STYLUS`/`TOOL_TYPE_ERASER`. A host or emulator that reduces the tablet to mouse events can still draw, but only at full pressure.

## Linux desktop

- Primary mouse or tablet-pointer drag draws. The desktop JVM receives these as pointer events and currently uses constant pressure.
- Middle drag, secondary drag, or Space + primary drag pans.
- Wheel and two-finger trackpad scroll pan. Shift + wheel pans horizontally. Ctrl/Meta + wheel zooms around the pointer.
- Ctrl/Meta+Z undoes, Ctrl/Meta+Shift+Z or Ctrl/Meta+Y redoes, and Ctrl/Meta+0 fits the canvas.
- Linux tablet pressure, tilt, eraser-end identification, and pad buttons require a future native input bridge; AWT does not expose those fields consistently. Their authoritative behavior remains in Android's `MotionEvent` route.

Stylus has priority. Once a stylus stroke is active, touch cannot navigate or corrupt it. `ACTION_CANCEL` cancels the active wet renderer and drops pending samples. `requestUnbufferedDispatch` and Jetpack motion prediction are used for lower latency.

The camera matrix transforms screen events to document coordinates once at input. Navigation changes the view matrix only and never resamples document pixels.

Color picking opens a native magnifying loupe after the configured hold delay. The loupe follows the finger, shows enlarged composite pixels, outlines the pending color, and suppresses navigation while active. The active brush color changes only when the finger lifts; cancellation or adding another finger dismisses the loupe without changing color. A stylus always retains priority and always draws regardless of finger mappings.

## Brush controls

Brush size and opacity use drag tracks with persistent numeric readouts. While either value is adjusted, the canvas center shows a live stamp with the current brush color, edge softness, and opacity; its diameter is expressed in document pixels and passes through the active canvas transform, so zooming changes its on-screen size exactly like a drawn stroke. Eraser adjustments use a neutral light stamp so the preview remains visible without changing artwork. In landscape the controls are stacked as narrow vertical controls: plus sits at the maximum end and minus at the minimum end. Portrait keeps the wider horizontal controls. The buttons apply 1 px and 1 percentage-point adjustments for precise tuning without requiring pixel-perfect slider motion.

Brush Studio keeps a compact live tip preview pinned above its scrolling controls for Pencil, Ink, Airbrush, and Eraser. The preview uses the same native raster painter as permanent Pencil and Airbrush pixels instead of approximating either brush with Compose line segments. It updates immediately for pressure dynamics, speed taper, opacity, and edge hardness; the Eraser uses a dark sample strip so its softness stays visible. Ink has separate light-pressure size and opacity floors, soft/balanced/firm response curves, and speed-taper strength. Airbrush exposes the equivalent spray-size and flow controls alongside spray-edge hardness. These values are stored independently for each built-in brush. The Pencil preview additionally moves from 0° to the observed 69° maximum and marks its configured shading transition. Pencil tilt calibration remains hover-based on devices that report stylus hover: hold the pen above the calibration square at the intended shading angle without touching the screen. In shading-switch mode the measured angle can become the point where side shading starts; in gradual mode it can become the full-shading angle.

Drawing mode hides the Android navigation bar by default and uses transient-bar swipe behavior, preventing an ordinary canvas stroke at the bottom edge from immediately navigating away. The focused drawing mode setting can keep the navigation bar visible; gallery and settings screens always restore it.

On compact phone windows, the editor uses a condensed top command strip and a horizontally scrollable bottom brush dock. Size and opacity expand above the dock only when requested. The chrome stays visible while a stroke remains in the unobstructed canvas area, hides only as the active pointer approaches the top or bottom controls, and returns on lift, cancellation, or the transition to two-finger navigation. The explicit **Hide controls** action remains persistent until the small top-edge handle or Android Back restores it. Tablet-sized windows retain the full edge-cluster layout.

The compact color dock always exposes the current drawing's most-used colors. In landscape it sits in the top-right toolbar immediately after Layers; in portrait it remains above the bottom adjustment controls. Tapping the large current-color circle opens the color chooser; canvas hold remains the eyedropper gesture for sampling existing artwork. Adjust opens the compact Brush Studio for edge hardness, pressure-to-size, pressure-to-opacity, and optional speed taper. Tuning, size, and opacity are stored locally for each built-in brush; the eraser has its own remembered size, opacity, hardness, and dynamics.

## Planned extension points

Gesture routing can later add swipe actions, a quick menu, fullscreen toggle, and additional user mappings without adding work to the sample path.

Remote/air actions are intentionally outside the generic button path. Samsung's remote S Pen SDK is model-specific, and Xiaomi reserves long presses for system note/screenshot shortcuts; TipStroke targets short in-range/contact button presses through Android input APIs first.
