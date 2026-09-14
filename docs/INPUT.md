# Input

## Routing

- `TOOL_TYPE_STYLUS` draws and captures pressure, tilt, orientation, timestamps, button state, pointer ID, and cancellation.
- `TOOL_TYPE_ERASER` uses the same brush engine with clear blending.
- Fingers never paint. Two fingers pan, zoom, and rotate the camera simultaneously.
- Two-finger tap invokes undo; three-finger tap invokes redo.
- Mouse data has a domain representation but mouse painting is not enabled in this Android milestone.

Stylus has priority. Once a stylus stroke is active, touch cannot navigate or corrupt it. `ACTION_CANCEL` cancels wet ink and drops pending samples. `requestUnbufferedDispatch` and Jetpack motion prediction are used for lower latency.

The camera matrix transforms screen events to document coordinates once at input. Navigation changes the view matrix only and never resamples document pixels.

## Planned extension points

The captured button state can later map stylus buttons to actions. Gesture routing can later add hold-to-eyedropper, swipe actions, quick menu, fullscreen toggle, and user mappings without adding work to the sample path.
