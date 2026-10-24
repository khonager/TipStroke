# Architecture

## Boundaries

`:core` is platform-neutral Kotlin. It owns `Document`, `CanvasSpec`, the sealed `Layer` contract, `RasterLayer`, non-destructive `ImageLayer` metadata, versioned `BrushPreset`, colors, transform/tile math, samples, renderer contracts, and bounded undo history. It contains no Android or Ink types.

`:drawing-android` owns the hot path. `DrawingSurface` routes raw `MotionEvent`s, native wet rendering shows the active stroke, `RasterCanvasView` composites the ordered runtime layer stack, and each paint layer's `TileStore` allocates/rasterizes only dirty 256×256 premultiplied ARGB tiles. Pencil and Ink use TipStroke custom families from Jetpack Ink 1.1's experimental API; their wet and final renderers share the same texture store and family. Airbrush uses one variable-width native raster silhouette with a single blur for both its transient preview and tile commit. Eraser gestures apply each new segment directly to affected raster tiles inside a cancellable transaction so lower layers are revealed immediately. Image erase masks reuse the same sparse tile and bounded-history machinery in original image coordinates.

`:app` owns Android lifecycle and normal UI. Compose is used for controls, but no stylus sample enters Compose state.

`:desktop` is a deliberately smaller Linux/JVM companion. Compose Multiplatform owns its window and controls, while a direct AWT `JComponent` owns input and rasterization so pointer samples never enter Compose state. It reuses `:core` brush defaults, allocates the same 256×256 sparse tile size, repaints only dirty/visible regions, and bounds tile-local undo snapshots to 128 MiB. It can open and export PNGs, but it does not yet implement Android project packages, layers, selections, or Jetpack Ink. The desktop path never changes or abstracts the production Android hot path.

The optional tablet-sized Android Emulator remains a separate integration-test target. Use it when the complete Android UI, persistence, layers, or selection flows matter. Physical Android hardware remains required for authoritative latency, palm-rejection, pressure/tilt, and OEM stylus validation.

Selections are transient platform-neutral polygons. Rectangle selection is the four-point case and freehand lasso retains its sampled boundary. Moving selected raster content reads only intersecting source-tile snapshots, clears the polygon from those tiles, composites it into intersecting destination tiles, and records the combined before/after set as one bounded undo transaction. It never copies a full layer or canvas.

The app shell owns three destinations: local gallery, editor, and settings. `DrawingLibrary` and `ProjectPersistence` live in `:drawing-android` because decoding/encoding tiles and imported assets requires Android bitmap/content-resolver types. Gallery order and stacks are stored in a separate root `gallery.json`; stack operations never move or rewrite project directories. The Compose gallery supplies long-press drag hit-testing, edge auto-scroll, stack previews, and stack management. Gesture choices remain platform-neutral values in `:core` and are persisted by the app with local preferences.

The editor's Compose layer panel supports long-press dragging. Releasing over another row places the layer above or below that row; releasing specifically over its artwork preview groups the two layers or adds the dragged layer to the target's group. Group IDs organize contiguous layers in the flat back-to-front painter stack. They do not change blending, allocate tiles, or add a separate compositing pass. The panel shows group boundaries and an Ungroup action. Leaving a group dissolves it when fewer than two members remain. The native compositor still reads the ordered layers directly, and drag hit-testing never enters the drawing input path.

## Stroke pipeline

```text
MotionEvent
  ├─ capture pressure, tilt, orientation, button, pointer ID and time
  ├─ Jetpack Ink 1.1 InProgressStrokesView (immediate wet stroke)
  └─ domain StrokeSample list in document coordinates
          ↓ pen up / Ink completion callback
      calculate stroke bounds
          ↓
      intersect sparse 256 px tiles
          ↓
      snapshot only affected tiles for undo
          ↓
      rasterize only those tiles
          ↓
      invalidate permanent raster view
          ↓ same UI-thread callback
      remove finished wet Ink stroke
```

The callback rasterizes with the same brush family/texture store, invalidates the raster view, and removes Ink’s finished stroke in the same UI-thread run loop to avoid a gap or double-opacity frame. Ink `Stroke` objects are not stored as document truth. Experimental Ink types remain behind `TipStrokeInkBrushes`; `:core` only sees versioned TipStroke presets and samples.

`BrushVisualHarness` creates deterministic pressure/tilt reference strokes from the production families. Its connected-device instrumentation test renders them through `CanvasStrokeRenderer`, asserts non-empty/contrasting final pixels, and exports review PNGs through Android Test Storage. This tests the native alpha implementation that Robolectric cannot load.

## Image layers

An image layer keeps its persisted Android document URI and original pixel dimensions as authoritative source data. Center, scale, rotation, visibility, opacity, and a sparse alpha-removal mask are separate metadata. Direct canvas gestures update only transform metadata: drag moves, pinch resizes, and twist rotates. Eraser samples are inverse-transformed into original image coordinates and added to the mask. Rendering applies that mask while compositing the immutable decoded source; changing scale or erasing never writes into the source bitmap. Returning to 100% therefore returns to the original source resolution rather than enlarging an already-downsampled intermediate.

Image decoding runs on a dedicated background executor. Dimension probing occurs once at import. The current milestone caches a full decoded bitmap per imported image, so very large or numerous images may still encounter device memory limits; tiled image pyramids are a future optimization and do not require a project-format change.

## Animation

Animation is optional per drawing. A timeline frame is a time slot with a stable frame ID and an exposure count; it is not a layer. The ordered layer stack keeps stable layer IDs across time. Each raster layer can be shared across every frame or have a sparse `TileStore` cel keyed by frame ID. Each image layer keeps one original asset and mask, with per-frame transform metadata when animated. Switching frames installs the active cel in `LayerStack`; the native raster view composites shared layers, neighboring onion cels, and the active cel without routing samples through Compose.

Duplicate Frame copies only allocated cel tiles so edits to the new frame are independent. Hold changes exposure metadata without copying artwork. The selected paint cel or selection can be copied or moved to a new layer in the current frame. A shared layer can be converted to an animated cel and back from the timeline menu. The timeline normally occupies one compact strip; its editing controls open only on demand.

Image motion recording samples native touch transforms and writes interpolated transforms into the chosen frame range. A shared image is converted to an animated layer when recording begins. Live drawing recording starts a new raster layer, runs the timeline at the document FPS, adds frames while time passes, and continues across strokes until stopped. Only the new drawing layer's allocated sparse tiles carry forward into the next frame. Each completed native stroke progressively appears in cels according to its captured timestamps. Jetpack Ink remains the wet renderer; sparse raster cels are the permanent artwork. Image motion retains `FIT_RANGE` and `REAL_TIME` timing. Ordinary playback uses the timeline exposure counts and loop, ping-pong, or once mode.

Animation export renders one frame at a time through the same layer compositor. MP4 uses Android `MediaCodec` and `MediaMuxer`, GIF uses indexed frames, and a PNG sequence is a ZIP with a `timing.json` file. Rendering and encoding run on the project executor, outside the input path. Export destinations are truncated before writing. GIF LZW code widths follow decoder dictionary timing, including the terminal code. MP4 input images transfer ownership through queueInputBuffer, an explicit end timestamp preserves the final frame duration (including single-frame clips), and muxer finalization errors propagate instead of being reported as successful exports.

## Replaceable seams

`StrokeRasterizer`, `LiveStrokeRenderer`, and `LayerCompositor` express responsibilities without platform types. A profiled future renderer can replace Android Canvas tiles without changing document APIs. The sealed `Layer` interface permits real future `VectorLayer`/`GroupLayer` implementations without pretending they exist today.

## Threading

The native input/wet-ink path runs directly on the UI thread. Current tile commit also runs there for deterministic handoff. Save/export briefly snapshots immutable copies of allocated tiles on the UI thread, then all JSON, image copying, PNG encoding, compositing, and destination I/O runs on a single background project executor. Atomic directory replacement ensures the prior complete revision survives a failed save.

## Editor lifecycle and local diagnostics

The activity saves the current screen and stable document identity in its instance-state bundle. A restored editor always loads that document; it must never substitute an empty canvas for a missing or unreadable project. A new editor resolves project existence on the serial project executor, after any saves queued by an earlier activity. It creates and commits an initial empty project only when no project directory exists, then loads it before enabling native input. Once opened, its navigation state is marked as existing.

This fixes a longstanding data-loss path introduced with the original gallery/layer support (`4ac6eb1`): the `existing=false` creation flag survived activity recreation, so the native-view factory configured a blank canvas even after the drawing had been saved. Subsequent autosaves could replace the saved artwork. This path predates both drawing-process recording and gallery transfer. Without a diagnostic report from an affected device, the exact event that caused its activity to be recreated cannot be determined.

Backgrounding captures and queues the current artwork even if a periodic save is still running. It must not discard the request or defer snapshot capture until a main-thread completion callback, since a replacement activity may already be loading by then. Save completion is counted across outstanding requests. Failures reach both the editor message and local diagnostics; failed/opening native surfaces reject saves. Android can still kill a process before an asynchronous save commits: only the last completed revision is guaranteed, not edits still in memory.

`TipStrokeApplication` installs an uncaught-exception handler that writes the latest crash before delegating to Android's previous handler. Routine lifecycle, memory-trim, and project open/save events use a separate bounded background queue, with two 128 KiB log files and a separate 64 KiB latest-crash file under app-private `diagnostics/`. Crash-path writes are synchronous because the process is terminating; drawing input never writes diagnostic files. On Android 11+, startup also records the last five available Android process-exit reasons, which can identify native crashes, ANRs, or memory reclamation that never invoke a Java exception handler. Android 10 still records Java crashes and routine events. Recording is best effort if storage or memory is exhausted.

Gallery **Settings → Troubleshooting → Crash and debug log** shows the retained report with Refresh, Copy log, and Clear log actions. Nothing is uploaded automatically. Clearing removes local reports; Android's own exit history may appear again at the next launch.

## Drawing-process replay (first version)

Drawing process is separate from animation. New Android canvases automatically record native raster transactions. Existing raster-only drawings without a recording start with their current tiles as a baseline; earlier history cannot be recovered. `ProcessRecorder` receives committed commands from `TileStore`, retains complete immutable brush settings and sample data, and compresses actions on a dedicated serial encoding worker. It does not produce video during drawing. Pencil preserves the actual incremental overlay commands and segment boundaries; Airbrush/pixel tools preserve completed commands; erasing preserves incremental segments. Ink preserves finalized native input batches, noise seed, static-brush phase marker, brush color/size/epsilon and a pinned native dependency identifier. Undo/redo, smudge, moved selections, and other unsupported raster transactions use exact changed-tile PNG patches. Layer order, visibility, opacity, and background changes are metadata actions. New nonempty raster layers receive a baseline once.

`ProcessReplay` reconstructs independent sparse tiles using frozen v1 painters, brush math, families, and textures. The live document remains authoritative raster artwork. Replay uses a bounded resident tile cache with lossless temporary disk backing; it no longer fails merely because the drawing spans more than 512 tiles. Preview rendering has its own serial worker and ignores superseded results. The preview has a fixed-size viewport; loading feedback overlays it and is hidden during playback so controls do not shift or flash between frames. Playback waits for each completed frame before advancing; seeking stays available during rendering. Failed actions reset replay so retry cannot silently skip them. Slow or failed preview steps are included in local diagnostics. Playback/scrubbing happens in a modal **Export → Drawing progress** view, outside the input path; backward seeking rebuilds from the start in this version. Export shares the existing GIF/MP4 encoder, renders one frame at a time, and uses 12 actions/second with a maximum 1280 px edge. The preview uses at most 960 px. Whole completed actions appear at once; this is not real-time pen-motion playback.

Animation activation and canvas resizing stop process recording, preserving a contiguous prefix and displaying the reason in Drawing progress. They do not prevent editing or saving artwork. Budget/error stops do not resume automatically. The legacy combined image/animation/resize stop can resume when opening a static, same-sized document: an explicit recovery checkpoint captures the current artwork, preserving the earlier prefix and marking the missing interval as a jump. A corrupt/unknown recording header is isolated from artwork loading and its original file is preserved on save. Unknown brush or native renderer versions fail replay rather than substituting current brushes.

Project saves request a recording snapshot barrier at the same moment as the artwork snapshot on the UI thread, then await encoding on the save worker. This preserves the same history prefix even when the user continues drawing while a save is queued. No input-thread compression or I/O is introduced.

Imported images are recorded once as source-resolution PNG assets on the encoding worker. Layer metadata retains image dimensions and center/scale/rotation; image erasing and undo use exact source-mask tile patches. Replay composites these with paint layers in document order, including visibility and opacity. Native image drag/pinch/twist defers process metadata capture and thumbnail updates until the last finger is released (or cancellation keeps its final transform). Intermediate positions are not replay steps. Transforming an image never records a canvas-sized bitmap or repeatedly re-encodes the source. Source decoding is included in the bounded recording queue estimate.

Layer and background opacity sliders update the live canvas throughout a drag, but process metadata is captured once when the slider is released. Intermediate opacity values are not replay steps.

### Animated fill

`AnimatedFill` reads selected-layer pixels and builds an isolated sparse tile
preview using four-connected propagation ordered by Euclidean distance from the
drop. Each queued pixel inherits at least the arrival radius of its predecessor,
so the circular front cannot appear beyond a closed boundary. A fixed 24/255 tolerance
compares alpha and premultiplied RGB against the seed. Selection boundaries are
hard limits. Edge cleanup visits eight neighbors and extends at most two softened
pixels without adding them to the propagation queue. Solid outline pixels stop
cleanup. Transparent seed regions underpaint translucent ink; recoloring estimates
the old region's contribution from a nearby stronger boundary reference that fits
the edge's premultiplied RGBA mixture. It replaces both color and alpha contribution,
including translucent fringes and diagonal corners. Commit replaces only the
preview's nontransparent footprint instead of compositing old color underneath it.
Solid outlines and unrelated neighboring colors stay unchanged.
Preparation runs on a dedicated worker. Source tiles are copied lazily, only when
reached, via short main-thread tile-copy requests; no full-layer snapshot is made.
A 32 MiB FIFO cache evicts and reloads source tiles instead of aborting a spill.
A revision check before commit rejects inconsistent reads and retries against the
edited layer. The result waits for any wet stroke to finish before inserting its
single tile-snapshot history transaction. Layer/frame replacement invalidates
pending results. Cancellation is checked between bounded worker batches.

Preparation also records each pixel's connectivity-aware arrival radius. These
are stored as sparse unsigned 16-bit values, then normalized into sparse ALPHA_8
masks. The finite document bounds result allocation; there is no 128-tile fill cutoff. `FillReveal` thresholds those masks
against an elapsed-time clock using `GestureSettings.fillAnimationMillis`
(250–5,000 ms, default 2,500), compositing immutable
before-history tiles with current raster tiles at the original layer opacity.
The mask and original pixels are independent of Pencil's preview. A tile modified
after fill commit reveals immediately; later strokes and erasures remain real
raster edits, with ordinary history order. No Ink strokes are involved in fill.

Stopping or detaching discards only the visual effect once committed; undo removes
artwork through ordinary history. Autosave/export and process recording use the
complete committed pixels, never a partially revealed frame. Source snapshots,
arrival data, and preview pixels are released after preparation/commit; reveal
masks are released at animation completion or cancellation.

Fill duration is a local app preference persisted by `GesturePreferences`, not part
of the project format. Color-drag callbacks optionally warm one `FillRequest`
after a stable hover. A request may pause on its worker or hold a completed result;
its main-thread continuation resumes or accepts it only after release. A nearby
release must match both the captured revision and the prepared connected region,
so crossing a thin closed line cannot apply the wrong cached fill. Cancellation
resumes any paused continuation only to release its resources. Native drop feedback
is transient view decoration, separate from artwork, history, and Pencil previews.

During released fill preparation, the existing worker accumulates a bounded coverage grid and publishes immutable low-resolution snapshots to the native view at most every 80 ms. The faint loading silhouette grows from real reached pixels, with a small outward bloom until the first snapshot. Request identity and document revision gate delivery; completion/cancellation clears the preview. It remains outside tiles and history and does not delay the full-resolution reveal.

## Multi-layer editing and move previews

Group selection expands to stable member layer IDs. A multi-layer erase captures its targets at pointer-down, transforms samples and the selection into each image’s source coordinates, and edits only newly touched tiles. Completed target transactions are transferred into one compound transaction in the primary layer’s bounded history; cancelled gestures restore all live targets. Image sources remain immutable.

During a selection move, `RasterCanvasView` draws each selected raster layer outside the original selection and draws the selected portion again with the live translation. Selected images use translated render transforms. The selection path and layer IDs are captured once; pointer samples update only native preview coordinates. No per-sample tile snapshots, thumbnail rendering, palette sampling, or Compose state are involved. Partial-opacity raster layers use a visible-region compositing pass to apply layer opacity once. On release, sparse tile movement commits once. Each destination tile looks up at most four translated source tiles instead of scanning the entire source selection.

### Transient selection composition and reference coverage

`SelectionRegion` composes polygon/ellipse boundaries using platform-free boolean
operations; Android converts them to native `Path` operations for clipping and
editing. Region containment evaluates the same composition for fill/input tests.
Image erasing transforms the complete region tree into image coordinates.

`StrokeStyle.referenceStrokes` carries a platform-free coverage contract.
Android `ReferenceStrokes` lazily captures alpha bytes only for reached 256 px
source tiles, shared by the targets of a multi-layer erase. No whole-layer copy is
made at stroke start. Rendering isolates the stroke, applies source alpha, then
composites onto the destination. Same-layer paint uses source-atop to preserve
existing alpha. Reference painting uses cancellable native sparse-tile updates
and the existing bounded undo transactions. Pixel Line retains its endpoint preview.
The source layer ID is session state; document schemas are unchanged. Process
recordings encode tile patches for reference/compound-selection strokes so replay
never silently drops their constraints when using the older stroke codec.

### Layer clipping

Runtime layers and saved/loaded layer records carry an optional `clipSourceId`.
Clipping is a compositing relationship: destination raster tiles and image assets
remain untouched. Native Canvas isolates the destination and renders source
coverage into a DST_IN group, including transparent gaps between sparse tiles.
Only the current clip bounds are isolated; there is no per-stroke full-layer copy.
Source coverage follows nested clipping and active animation cels while ignoring
source visibility and layer opacity. Cycles and dangling IDs are sanitized on load.
The canvas, layer previews, sampled composite colors, still/animation exports and
process replay apply this same relationship. Source deletion releases dependents.
