# Android brush creation, selection, and sharing

Open **Brushes** directly from the canvas dock. The library has All brushes,
My brushes, and Favorites sections, stroke previews, search, import, and
**Create brush**. Brush selection is independent of the adjustment controls.

## Original brushes

Create brush starts a new shape-and-grain brush, not a copy of Pencil, Ink, or
Airbrush. Its native raster renderer lays down a sequence of stamps. The three
legacy brushes remain available; Customize retains their existing editor and
**Design with shape & grain** converts a copy to the new renderer.

Brush Studio has six sections:

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
a 256 × 256 source. Sources are embedded in the shared brush, with no path/URI
references or external downloads.

The native drawing pad accepts finger/stylus input and lets users clear or generate
a pressure/tilt sample. Changes redraw the existing test strokes with the current
settings. It retains at most 16 strokes of 2,048 samples and a 1,600 × 600 preview
bitmap. Pointer samples remain in native views and do not become Compose state.
Save and use atomically saves the draft and selects its dynamics; Cancel discards
only the draft. Editing a saved brush does not change already-painted artwork.

This is an extensible stamp renderer, not full Procreate parity. Wet pigment
mixing, dual-brush blending, arbitrary shader/code execution, and Procreate
`.brush`/`.brushset` compatibility are not implemented. Legacy stabilization is
still reserved; the custom editor does not expose an ineffective smoothing slider.

Reference: [Procreate Brush Studio settings](https://help.procreate.com/procreate/handbook/brushes/brush-studio-settings)
uses shape, grain, and stroke-path controls; the native implementation and source
assets here are TipStroke's own.

## Favorites that fit

The canvas dock contains a dedicated Brushes button, favorite brush cells, a Tools
menu, and Adjust. Tools contains Select, Eraser, Smudge, eligible pixel tools, and
Brush Studio; the adjustment action retains the phone size/opacity toggle.
There is no scrolling container in the dock.

Favorite capacity is computed from the dock's measured available width or height,
48 dp cells, 2 dp gaps, padding, and three reserved actions. Small split windows
can have zero favorite slots; the full library is always reachable. The library
disables additional pins when the available slots are filled. Favorites persist
in order; **Move to front** in Favorites controls priority. When a window shrinks
or rotates, only the first fitting favorites appear. Extra pins remain saved and
return with more room. Selecting a nonfavorite does not displace a favorite.

## Native rendering and replay

`BrushPreset.stamp` supplies the new brush definition, with platform-independent,
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
brush renderer is not changed, and its codec refuses custom brush commands.

## Format and storage

`.tipbrush` JSON uses `format: "tipstroke-brush"`, package `version: 2`, and brush
`schemaVersion: 3` for custom brushes. The optional `stamp` object contains the
parameters and source images as bounded base64 coverage bytes with dimensions.
Existing package v1 / brush schema 2 files still import as legacy presets. Unknown
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
brushes, with no fabricated popularity or publisher verification.
