# Architecture

## Boundaries

`:core` is platform-neutral Kotlin. It owns `Document`, `CanvasSpec`, the sealed `Layer` contract, `RasterLayer`, non-destructive `ImageLayer` metadata, versioned `BrushPreset`, colors, transform/tile math, samples, renderer contracts, and bounded undo history. It contains no Android or Ink types.

`:drawing-android` owns the hot path. `DrawingSurface` routes raw `MotionEvent`s, native wet rendering shows the active stroke, `RasterCanvasView` composites the ordered runtime layer stack, and each paint layer's `TileStore` allocates/rasterizes only dirty 256×256 premultiplied ARGB tiles. Pencil and Ink use TipStroke custom families from Jetpack Ink 1.1's experimental API; their wet and final renderers share the same texture store and family. Airbrush uses one variable-width native raster silhouette with a single blur for both its transient preview and tile commit. Eraser gestures apply each new segment directly to affected raster tiles inside a cancellable transaction so lower layers are revealed immediately. Image erase masks reuse the same sparse tile and bounded-history machinery in original image coordinates.

`:app` owns Android lifecycle and normal UI. Compose is used for controls, but no stylus sample enters Compose state.

`:desktop` is a deliberately smaller Linux/JVM companion. Compose Multiplatform owns its window and controls, while a direct AWT `JComponent` owns input and rasterization so pointer samples never enter Compose state. It reuses `:core` brush defaults, allocates the same 256×256 sparse tile size, repaints only dirty/visible regions, and bounds tile-local undo snapshots to 128 MiB. It can open and export PNGs, but it does not yet implement Android project packages, layers, selections, or Jetpack Ink. The desktop path never changes or abstracts the production Android hot path.

The optional tablet-sized Android Emulator remains a separate integration-test target. Use it when the complete Android UI, persistence, layers, or selection flows matter. Physical Android hardware remains required for authoritative latency, palm-rejection, pressure/tilt, and OEM stylus validation.

Selections are transient platform-neutral polygons. Rectangle selection is the four-point case and freehand lasso retains its sampled boundary. Moving selected raster content reads only intersecting source-tile snapshots, clears the polygon from those tiles, composites it into intersecting destination tiles, and records the combined before/after set as one bounded undo transaction. It never copies a full layer or canvas.

The app shell owns three destinations: local gallery, editor, and settings. `DrawingLibrary` and `ProjectPersistence` live in `:drawing-android` because decoding/encoding tiles and imported assets requires Android bitmap/content-resolver types. Gallery order and stacks are stored in a separate root `gallery.json`; stack operations never move or rewrite project directories. The Compose gallery supplies long-press drag hit-testing, edge auto-scroll, stack previews, and stack management. Gesture choices remain platform-neutral values in `:core` and are persisted by the app with local preferences.

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

Animation export renders one frame at a time through the same layer compositor. MP4 uses Android `MediaCodec` and `MediaMuxer`, GIF uses indexed frames, and a PNG sequence is a ZIP with a `timing.json` file. Rendering and encoding run on the project executor, outside the input path.

## Replaceable seams

`StrokeRasterizer`, `LiveStrokeRenderer`, and `LayerCompositor` express responsibilities without platform types. A profiled future renderer can replace Android Canvas tiles without changing document APIs. The sealed `Layer` interface permits real future `VectorLayer`/`GroupLayer` implementations without pretending they exist today.

## Threading

The native input/wet-ink path runs directly on the UI thread. Current tile commit also runs there for deterministic handoff. Save/export briefly snapshots immutable copies of allocated tiles on the UI thread, then all JSON, image copying, PNG encoding, compositing, and destination I/O runs on a single background project executor. Atomic directory replacement ensures the prior complete revision survives a failed save.
