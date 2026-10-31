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
- Resize canvas mode places native drag handles at all four corners and edge midpoints. Dragging a handle changes the corresponding document edges in whole pixels, with each side clamped to 16–8192 px. The preview shows added paper and shades cropped pixels; Cancel leaves the document untouched, and Apply commits the previewed bounds.
- Two or more fingers continue to pan, zoom, and rotate the camera simultaneously; rotation can be locked. Scaling and rotation are anchored at the gesture centroid, so the document point between the fingers remains beneath that point while pinching. After a moved multi-finger gesture loses a pointer, navigation rebases to the remaining finger so a configured one-finger drag can continue without a jump.
- Select mode temporarily routes a one-pointer finger or stylus drag to a freehand lasso, rectangle or ellipse in document space. The selection constrains subsequent paint-layer drawing and erasing. Move translates selected pixels on every selected paint layer and whole selected image layers together. Choosing Move without a marquee selects the whole canvas. Artwork follows the pointer live; releasing commits the move as one undo step on the primary layer. Cancelling discards the preview. Duplicate copies the selected pixels from each selected paint layer into a new sparse layer while preserving the originals. On an image layer the selection constrains edits to the image's original-resolution erase mask; whole-image movement remains the image transform mode. Select All and Clear live in the tool’s Actions menu; choosing a brush returns to drawing while retaining the selection.
- Selecting a layer keeps the layer list compact. Its ⋮ button opens a scrollable settings overlay; Done or tapping outside closes the overlay while keeping that layer selected.
- In the Layers panel, hold a layer row to drag it. Rows stay in place until release. Over the name/control side, a blue line marks the nearest legal gap and a label says which layer or group it is above/below. The thumbnail column is reserved for grouping: a preview highlights and the label says Group with/Add to group; gaps in that column do nothing. The panel scrolls when a dragged layer nears its top or bottom edge, gradually speeding up toward the edge. A right swipe still adds a layer to the multi-layer selection.
- A completed lasso owns a copy of its sampled points, so clearing the temporary gesture buffer cannot invalidate later hit testing or movement. Moving a large selection visits only tiles that contain selected pixels and their destination tiles.
- Animation recording starts from the compact timeline. Image motion uses the existing finger drag/pinch/twist transform route and captures transform samples through finger-up; it requires at least two frames and offers Fit Range or Real Time timing. Record drawing live starts playback at the selected frame, advances at the chosen FPS, and adds frames as needed. Recorded drawing carries forward into each new frame, and each stroke progressively appears according to its sampled time. The native pen/touch route stays active for as many strokes as the artist draws. Stop recording keeps all captured frames and cels. Selecting another frame or pausing playback also ends the session.
- Canvas navigation supports up to 256× view scale so 16 px pixel-art canvases can fit the screen and still zoom further without a jump when pinching or using a wheel.
- Pixel, Line, and Shade join Pencil, Ink, Airbrush, and Eraser in the tool list on canvases up to 128×128 px or at 8× zoom and above. Small canvases open with the 1 px Pixel tool. Pixel tools remain in the list while one is selected after zooming back out; selecting any regular brush switches directly back to it. Pixel tool size can be adjusted from 1 to 32 px. Line previews a straight line from the first contact to the current contact, and Shade uses an anchored checkerboard dither. Eraser uses the same integer grid when a pixel tool is active.
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

On compact phone windows, the editor uses a condensed top command strip and a one-row bottom tool dock. Regular and pixel tools share its horizontally scrollable tool strip; the selected tool scrolls into view, while the size/opacity toggle stays fixed at the edge. Size and opacity expand above the dock only when requested. Tablet portrait uses a width-limited scrolling rail so it does not collide with the adjustment controls; landscape rails scroll vertically when needed. The chrome stays visible while a stroke remains in the unobstructed canvas area, hides only as the active pointer approaches the top or bottom controls, and returns on lift, cancellation, or the transition to two-finger navigation. The explicit **Hide controls** action remains persistent until the small top-edge handle or Android Back restores it.

The compact color dock always exposes the current drawing's most-used colors. In landscape it sits in the top-right toolbar immediately after Layers; in portrait it remains above the bottom adjustment controls. Tapping the large current-color circle opens the color chooser; canvas hold remains the eyedropper gesture for sampling existing artwork. The color chooser's **Original image colors** switch makes the eyedropper and visible-artwork palette sample image layers at their original opacity, even when those layers are faded on the canvas; paint layers still use their displayed opacity. Adjust opens the compact Brush Studio for edge hardness, pressure-to-size, pressure-to-opacity, and optional speed taper. Tuning, size, and opacity are stored locally for each built-in brush; the eraser has its own remembered size, opacity, hardness, and dynamics.

## Planned extension points

Gesture routing can later add swipe actions, a quick menu, fullscreen toggle, and additional user mappings without adding work to the sample path.

Remote/air actions are intentionally outside the generic button path. Samsung's remote S Pen SDK is model-specific, and Xiaomi reserves long presses for system note/screenshot shortcuts; TipStroke targets short in-range/contact button presses through Android input APIs first.

Image scaling no longer has fixed 4× slider or 16× gesture ceilings. The slider expands its range after reaching the upper end; Half size/Double size allow repeated scaling, and pinch gestures preserve scales above the old ceiling. Scale values must remain positive and finite. Imported source pixels are preserved regardless of display size.

### Color-drop fill

Drag the current color circle onto the canvas to fill; tapping it still opens the
color picker. Both portrait and landscape circles support the gesture. The drop
uses the native inverse canvas transform, including zoom, rotation, and pan.
Fill targets the selected visible paint layer and respects the current selection.
Other layers are not boundary references. Image layers must be painted on a raster
layer instead. Color alpha is respected; brush size and brush opacity do not apply.

A circular wave reveals the prepared fill over the duration chosen in Settings →
Fill animation → Duration (0.25–5 seconds, default 0.5 seconds). Shorter is faster;
the duration stays independent of region size, canvas resolution, or zoom. Preparation happens on a worker. Pausing the dragged color over the canvas for
125 ms starts one bounded preparation attempt; it pauses after approximately
150 ms of work until release. Movement away or cancellation discards speculative
work. A release in the same connected region reuses it, after revision and region
checks. Merely hovering never changes artwork or history.

Every actual drop starts a small outward bloom, followed by a faint loading silhouette of the region the worker has actually reached. It expands as the search progresses and follows discovered boundaries, with no spinning indicator or second flood search. The silhouette is a low-resolution hint; the final fill retains full-resolution edges.
It disappears when the region reveal starts. Quick drops and large regions may
still need preparation time, but no longer leave the drop visually unacknowledged. Cleanup reaches up to two softened pixels in all eight directions, including
diagonal corners. Recoloring replaces the old color contribution in premultiplied
RGBA, so translucent edges retain their coverage without an old-color rim. Solid
outlines stay intact. This overlap never propagates through the boundary. Open lines still
allow the wave to escape into the connected background, making leaks visible.
A large spill is not discarded when it crosses the old 32 MiB preview limit.

Drawing, erasing, pan, zoom, rotation, and controls remain available. The fill
commits before its visual reveal, so subsequent strokes go above it and undo in
normal order (newest stroke, then fill). Editing an unrevealed tile shows that tile
immediately so the new mark or erasure is never hidden. Pencil previews are
independent of the fill effect. Edits during preparation trigger a recomputation
against completed strokes rather than overwriting newer work.

Stop animation (or Back) cancels pending preparation, or immediately shows an
already committed fill. Use Undo to remove a committed fill; newer strokes undo
first. Undo during preparation cancels that pending fill without undoing earlier
artwork. Saving/exporting during the reveal includes the complete fill and later
strokes. A drop outside the canvas, a transparent color, or an identical seed color
changes nothing.

Drawing-progress recording stores an image transform only when the last finger releases it (or a cancelled gesture retains its final position). Drag/pinch/twist intermediate positions remain visible live but do not create replay steps.

Layer dragging uses pointer positions in the stationary list container and a small floating name label. Rows and thumbnails stay fixed while targeting (except during edge scrolling), so a target cannot slide away beneath the pointer. The insertion guide follows the pointer rather than the grabbed row edge. Cancelling a drag leaves the order unchanged.

Tap a group heading to select every member; swipe individual rows right to add or remove layers from the selection. Erasing applies each native segment to all selected paint layers and selected image masks, respecting the marquee in each target’s coordinates. Cancelling restores every target. A completed multi-layer erase is one bounded undo operation on the primary layer.

### Layer panel drag lifecycle and space

After a long press, the stationary layer-list container consumes movement in the
initial pointer pass, before the scrolling list can steal the gesture. Ordinary
swipes still scroll/select. Release and cancellation remove the floating label;
its placement uses a captured finite offset so cleared coordinates cannot reach
pixel rounding. Detached layout coordinates are ignored. The release handler is
refreshed with a lambda as the layer list changes, so adding/loading layers or a
previous reorder cannot leave release using an older captured drop resolver.

The layer overlay uses available safe height (up to 420 dp wide), with an explicit
Done action. Background color and opacity open from a scrolling list item. Layer
ordering buttons, duplicate and delete live in the manually opened settings dialog,
leaving the list free of a pinned settings footer.

### Responsive gallery and editor

Gallery headings and transfer actions scroll with the drawing grid. Thumbnails
use a shared height derived from the safe window height (100–220 dp), preserving
the artwork aspect ratio inside that frame. Short windows use smaller grid cells;
the heading/actions wrap with available width. Only the main gallery bar stays
fixed, leaving the grid usable in phone landscape and resized desktop windows.

Phone landscape windows with at least 640 dp of safe width place navigation and
brush controls side by side at the top. Size/opacity open separately beneath the
dock; portrait and narrower windows keep the bottom brush dock. Floating docks
are capped in width. Editor controls and overlays respect safe system insets;
color and layer panels use available space without reserving a permanent side rail.

### Layer drag cues and canvas background color

The orange row outline follows the actual primary layer selection during a drag.
The source row stays visible but dimmed. A blue insertion guide with a dot appears
only on the name/control side; grouping highlights only the target preview.
The header announces the pending action, and a compact floating name label leaves
the thumbnail targets visible. Crossing gaps in the thumbnail column never
creates an insertion guide or reorders on release.

Canvas background color opens the same Wheel, Sliders, and Values editor as brush
color. Changes appear live; Values includes hex, RGB, and HSV fields. Background
opacity stays in this editor. The editor hides brush-specific image sampling,
palette, and history controls.

### Selection and Reference strokes

Select offers lasso, rectangle and ellipse, with Replace, Add, Subtract and
Intersect modes. Invert selects the rest of the canvas. Clear removes the shape;
Choosing a drawing tool returns to painting while keeping its boundary active. Cancelled selection
gestures restore the previous boundary. Duplicate copies selected paint to new
layers; Cut to layer moves it; Delete pixels removes it with undo support.
Move and the existing selected-layer editing actions also respect combined shapes.

Choose a paint layer under Select → Reference strokes, or choose Use as Reference
strokes in its layer settings. Its painted coverage constrains brush and eraser
strokes on the active paint layer. Keep that layer active to shade in place with
its transparency preserved, or select/add another paint layer to shade separately.
Soft source edges give proportional coverage on another layer. Geometric selections
further restrict painting. Source visibility and layer opacity do not change its
stored coverage. The source updates between strokes; reached source tiles are
frozen for the duration of a stroke. Turn off releases the reference independently
of Clear selection. Deleting the source releases it as well.

Reference strokes is transient editing state, not a saved mask or a permanent
layer relationship. Sources and destinations are paint layers; image erasing,
Color Drop and finger smudging require Reference strokes to be off. Color Drop
shows an explanation instead of filling outside the reference. Feather/expand,
selection rotation and scaling are not included in this selection toolset.

### Select tool controls and layer clipping

Select lives beside Pencil, Ink and Eraser in the tool rail/dock. Selecting it
replaces size/opacity controls with compact Shape, Mode, Actions and Reference
menus in the same area. Choosing a drawing tool restores brush controls without
clearing an existing selection or Reference strokes. There is no permanent
selection banner over the canvas. Move is in Shape; copy/cut/delete, all, invert
and clear are in Actions.

In layer settings, **Clip to layer** chooses another paint or image layer as live
coverage for already-painted content. The destination keeps all of its original
pixels, including newly drawn pixels outside the source. **None — show all pixels**
reveals them again. Soft source alpha and nested clipping are honored; source
visibility and its layer-opacity slider do not alter the coverage. Source image
transforms and erased areas do. Clip relationships use stable IDs, survive
reordering, and reject self-links/cycles. Deleting the source releases dependents.
Clip changes support undo/redo on the destination layer. This differs from
Reference strokes, which constrains pixels at painting time.

### Quick shapes

Rectangle and ellipse selections are unconstrained by default. While dragging,
place an additional finger on the canvas to constrain the marquee to a square or
circle, anchored at the drag's starting point in document space. Lifting that
finger restores the unconstrained proportions. The original pointer owns the
selection; lifting it commits even if the modifier finger is still down.

To straighten a hand-drawn shape, keep the drawing pointer in contact and still
for 650 ms at the end of the stroke. Recognized lines, circles, ellipses,
triangles, rectangles, and squares snap in the live preview, with a short haptic
confirmation. Near-circular and near-square outlines become exact circles and
squares. Rectangles may be rotated; ellipse fitting uses document axes. Draw an
arrow in one continuous stroke: tail → tip → first wing → tip → second wing.
Hold at its end to regularize the shaft and make the two wings symmetric.
The easier arrow shortcut is to draw a line, hold until it snaps, then tap the
canvas with an extra finger while keeping the pen down. This adds a symmetric
arrowhead which remains after the finger lifts.

Unrecognized strokes and tiny marks stay freehand. After snapping, keep the pen
down and drag: lines and arrows keep their starting point anchored while their
endpoint follows the pen; circles, ellipses, rectangles, squares and triangles
scale uniformly and rotate about their fitted bounding-box center. Circles stay
circular and squares stay square. The adjustment starts from the original fit,
without a jump or cumulative drift. Lift to commit the final shape as one undo
step. Cancellation discards the entire provisional shape. Editing is available
before lift; committed artwork remains raster pixels.

Quick shapes retain the active brush, color, selection,
and raster history semantics. Open shapes retain sampled pressure and tilt;
closed shapes use average contact pressure/tilt and no speed taper so a loop has
no pen-down/lift-off seam. Pencil, Ink, Airbrush, single-layer Eraser, Pixel,
and Shade use their existing raster painters. The dedicated pixel Line tool
already constrains its stroke and does not run recognition. Multi-layer erase
retains its existing freehand gesture. Finger drawing and mouse drawing also
support hold-to-shape; adding a second finger during finger drawing still cancels
the mark for navigation. An extra finger never ends an active stylus stroke.

## Photo editing

The Layers **＋ Layer** button immediately creates a paint layer; **Import** is a separate action. Imported photos open in photo editing mode, also reachable through an image layer's **Edit photo on canvas** action. **Done** returns the pen to drawing/erasing.

Photo edits use the native touch/stylus surface. Move supports one-pointer translation and two-pointer scale/rotation. Stretch uses horizontal/vertical drags for independent image-axis scaling. Push moves a local area; Bulge enlarges features and creates spherical bulges; Pinch shrinks features. Adjust **Area size** to control the affected radius (relative to image height); the circle appears while dragging. Two fingers always move/scale/rotate the image regardless of the selected photo tool.

Optional **Snap** aligns image bounds to canvas/visible photo edges and centers within 10 screen dp, and rotation to 15-degree increments or another visible photo's angle within 3 degrees. Unsnapped gesture coordinates are retained so magnets release smoothly. Snapping applies in Move and two-finger navigation. Each completed gesture is one bounded undo entry; canceled gestures restore their initial transform. Reset shape clears stretching/warping with undo, preserving placement and rotation.

For composites, import the source photos as separate layers, lower opacity to align landmarks, reshape with Push/Bulge/Pinch, finish editing, and erase unwanted image regions. Draw the reference on a paint layer above. This is manual compositing; it does not generate missing anatomy or blend two subjects automatically.

Layer reorder hit testing chooses the nearest measured boundary in the stationary list. The blue line and release callback share the same anchor layer and above/below direction; removing the source adjusts the destination index exactly once. Outside layers can only reorder before or after a whole group, while members can reorder inside their own group. The guide includes group-heading heights. Moving horizontally into the thumbnail column selects grouping, with no insertion fallback for the source preview, same-group previews, or gaps. Releasing outside the list or cancelling commits nothing.
The blue line is drawn at that destination slot using measured item positions and heights. Releasing commits the placement shown by the preview.

## iPhone and iPad input

The native iOS port consumes coalesced UIKit touches directly. One finger draws
by default; brush settings can disable finger drawing for Pencil use. A second
finger cancels provisional finger pixels before native pan/pinch navigation.
Pencil input takes priority over finger painting. Reported final Pencil force
controls width; estimated/unavailable force uses a constant fallback. Pencil
USB-C and fingers therefore remain usable without pressure hardware.

The first port includes Pencil, Ink and transparent Eraser; size/opacity, native
color picking, undo/redo buttons, fit, and Pencil double-tap eraser switching.
It does not yet implement tilt, hover, predicted touches, late force correction,
Pencil Pro squeeze/roll, rotation gestures or Android gesture preferences. Resizing
or rotating the window refits the document without altering its pixels. All tools
stay within the safe area; layer and brush controls open as system sheets.

While drawing, the status bar is hidden; swiping from the edge reveals system bars temporarily. The navigation-bar preference remains independent. Leaving the editor restores system bars. Phone landscape places the timeline at the bottom edge and the main controls close to the top, retaining cutout insets.

Image Move → Snap → Options saves angle spacing (5°, 15°, 30°, 45°, or 90°) and snap strength. Edges, centers, and corners align with the canvas and visible images. Strength controls the capture distance in screen space and the angular tolerance; movement accumulates against the unsnapped transform so dragging away releases the snap without a sticky offset. Snapping remains optional.

Smudge is selectable alongside brushes and the eraser. It works with a stylus, primary mouse button, or a finger when touch drawing is enabled, independently of the finger-smudge gesture setting. It edits the selected raster layer, uses tool size/opacity and Settings → Smudge strength, and creates one undo step per gesture. A second finger hands touch smudging to navigation; extra fingers do not take over a pen stroke. Interrupted gestures finalize their existing changes as one undoable action.

## Selected-pixel transforms

Select pixels with Lasso, Rectangle, or Ellipse, then open the selection tool menu → **Rotate, resize or flip**. The panel offers width/height percentages (1–800%), linked proportions, rotation, horizontal/vertical flips, and optional smoothing. The transform pivots around the selection bounds. The native canvas previews the matrix against the original pixels; **Apply** resamples once, while **Cancel**, Back, or closing the panel leaves artwork unchanged. Pixels outside the canvas are cropped on Apply and restored by undo. Selected raster layers transform together as one undo step; image layers continue to use their own non-destructive photo controls. Boolean selection holes remain excluded.

## Brush selection and favorites

Brushes is a dedicated canvas-dock action, separate from Adjust. Its library lists
all brushes with previews, creation/import, and pinning. The dock uses fixed cells
and never scrolls; the measured available axis determines how many favorites fit
after reserving Brushes, Tools, and Adjust. Saved pin order persists across layout
changes; extra pins are hidden until space returns, and additional pinning is
disabled at the current capacity. Tools contains selection, erasing, smudging,
eligible pixel tools, and Brush Studio. Adjust retains the compact phone's
size/opacity toggle; custom-brush studio adjustment opens its saved definition.

Custom brush input and the studio's drawing/source pads use native MotionEvents.
The new renderer bypasses Jetpack Ink and incrementally stamps shape/grain masks
into sparse tiles. Shape-hold snapping is disabled for custom stamp strokes so it
does not replace scattered or textured marks with fitted geometry. The native
studio pad supports stylus pressure/tilt and full-pressure finger drawing.
