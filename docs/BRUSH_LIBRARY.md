# Android tip creation, selection, and sharing

Open **Tips** directly from the canvas dock. The library has All tips,
My tips, and Favorites sections, stroke previews, search, import, and
**Create tip**. Tip selection is independent of the adjustment controls.

## Original tips

Create tip starts a new shape-and-grain tip, not a copy of Pencil, Ink, or
Airbrush. Its native raster renderer lays down a sequence of stamps. The three
legacy tips remain available; Customize retains their existing editor and
**Design with shape & grain** converts a copy to the new renderer.

Tip Studio has six sections:

- **About:** name, creator, description/sharing terms, size, and opacity.
- **Shape:** round, square, oval, bristles, or an imported/drawn image; edge hardness
  for procedural shapes; aspect ratio, rotation, direction following, rotation
  randomness, and multiple stamps per step.
- **Grain:** none, noise, paper, lines, or an imported/drawn image; depth, scale, and
  placement fixed to canvas coordinates or moving with each stamp.
- **Stroke path:** real stamp spacing, position scatter, flow, start taper, and
  fade-out distance. Flow accumulates where stamps overlap.
- **Dynamics:** pressure size/opacity curves, speed taper, tilt size boost, and
  size/opacity randomness.
- **Color:** per-stamp hue and saturation randomness based on the canvas color.

Image sources can be imported, painted, erased, inverted, and rotated. The source
pad can edit an existing imported or drawn source. White represents coverage and
black represents no paint. Transparent input images use their alpha channel;
opaque images use luminance. Import checks encoded size (16 MiB) and dimensions
(up to 16,384 on either axis), downsamples before allocation, and letterboxes into
a 256 × 256 source. Sources are embedded in the shared tip, with no path/URI
references or external downloads.

The native drawing pad accepts finger/stylus input and lets users clear or generate
a pressure/tilt sample. Changes redraw the existing test strokes with the current
settings. It retains at most 16 strokes of 2,048 samples and a 1,600 × 600 preview
bitmap. Pointer samples remain in native views and do not become Compose state.
Save and use atomically saves the draft and selects its dynamics; Cancel discards
only the draft. Editing a saved tip does not change already-painted artwork.

This is an extensible stamp renderer, not full Procreate parity. Wet pigment
mixing, dual-tip blending, arbitrary shader/code execution, and Procreate
`.brush`/`.brushset` compatibility are not implemented. Legacy stabilization is
still reserved; the custom editor does not expose an ineffective smoothing slider.

Reference: [Procreate Brush Studio settings](https://help.procreate.com/procreate/handbook/brushes/brush-studio-settings)
uses shape, grain, and stroke-path controls; the native implementation and source
assets here are TipStroke's own.

## Favorites that fit

The canvas dock contains a dedicated Tips grid icon, favorite stroke thumbnails, a Tools
menu, and Adjust. Tools contains Select, Eraser, Smudge, eligible pixel tools, and
Tip Studio; the adjustment action retains the phone size/opacity toggle.
There is no scrolling container in the dock.

Favorite capacity is computed from the dock's measured available width or height,
48 dp cells, 2 dp gaps, padding, and three reserved actions. Small split windows
can have zero favorite slots; the full library is always reachable. The library
disables additional pins when the available slots are filled. Favorites persist
in order; **Move to front** in Favorites controls priority. When a window shrinks
or rotates, only the first fitting favorites appear. Extra pins remain saved and
return with more room. Selecting a nonfavorite does not displace a favorite.

## Visual controls and endpoint previews

Tip actions use icons with accessible names. Hover with a supported stylus or mouse
for 650 ms to see the name next to the vertical dock, or above horizontal controls.
Leaving cancels the pending label; touch users can hold an icon to show it for two
seconds. Popup labels stay within the window. Library entries retain readable tip
names, and section tabs and setting labels remain text.

Favorites, library entries, and both ends of creator/adjustment sliders render a
representative stroke through `BrushStrokePreviewView` and the native raster
painter. Custom image shape and grain sources use the same path as painted strokes.
The sample varies pressure, speed, and tilt, including tilt for custom stamp tips.
A fixed 400 × 160 document-space sample makes texture and dynamics independent of
screen density. Each view caches only its output-sized bitmap and invalidates it
when its preset, color, opacity, rendering mode, or dimensions change.

Slider endpoints are pure copies of the current definition at that control's
minimum/maximum. They never modify the draft. The size slider uses the actual size;
other thumbnails use a common display size to make texture and dynamics legible.
A shared blue sample makes hue/saturation changes visible. Equal-looking endpoints
are possible: rotation of a round shape or grain controls with grain disabled do
not change the result. Hover, hold, or tap an endpoint to read its value. The native
test pad remains available for evaluating longer strokes and actual pen input.

Use the image-plus icon in **Shape** or **Grain** to import a source, or the drawing
icon to paint/edit one. Invert and rotate appear when an image source exists.
The checkmark applies the source; the close icon cancels that source edit.

## Native rendering and replay

`BrushPreset.stamp` supplies the new tip definition, with platform-independent,
immutable coverage sources in `:core`. `StampPath` samples arc length with a
one-document-pixel minimum step, carrying spacing and deterministic randomness
across event batches. Each update renders only new stamps into intersecting 256 px
tiles. No full layer copies or per-sample IO are introduced. Undo is one bounded
sparse transaction, and cancellation restores the before snapshots.

The stamp renderer composites a shape mask and a repeating grain mask in document
coordinates, with bounded size, tilt, scatter, and count. Extremely large corrupt
input jumps are bounded to 8,192 path steps per segment. Blank faded stamps do not
allocate tiles. Live pixels become the final pixels at pen-up. Custom paint is
recorded as exact sparse patches using the existing replay format; the frozen v1
tip renderer is not changed, and its codec refuses custom tip commands.

## Format and storage

`.tipbrush` JSON uses `format: "tipstroke-brush"`, package `version: 2`, and tip
`schemaVersion: 3` for custom tips. The optional `stamp` object contains the
parameters and source images as bounded base64 coverage bytes with dimensions.
Existing package v1 / tip schema 2 files still import as legacy presets. Unknown
versions, invalid ranges/enums, missing image sources, and oversized/invalid source
data fail without modifying the library. Pressure curves end at 1 to match the
canvas controls.

Packages are capped at 512 KiB; names at 60 characters, author at 80, description
at 1,000. `files/brush-library.json` stores at most 200 presets and 32 MiB, using
AtomicFile replacement. Parsing and file IO run off the input thread. A failed
load preserves the file and disables mutations. Shared attachments use a dedicated
FileProvider cache directory bounded to 20 recent files. Imports always receive a
new local ID, never overwrite presets, and include both custom sources.

The artwork document schema, sparse raster tile format, and frozen replay v1
algorithms are unchanged. iOS and desktop do not gain this editor in this change.
Public publishing, rankings, and a network-backed community store remain outside
the current no-network policy. The library includes actual bundled and local
tips, with no fabricated popularity or publisher verification.


Tip thumbnails in the favorites dock, library, and slider endpoints inherit the
current canvas drawing color, including changes from the color picker or eyedropper.
Selection changes highlight the cell without replacing the stroke color. Native
preview caches invalidate on color changes; contrasting neutral backdrops keep
both light and dark tips visible. Live native stroke previews use the same color.
