# Architecture

## Boundaries

`:core` is platform-neutral Kotlin. It owns `Document`, `CanvasSpec`, the sealed `Layer` contract, `RasterLayer`, non-destructive `ImageLayer` metadata, versioned `BrushPreset`, colors, transform/tile math, samples, renderer contracts, and bounded undo history. It contains no Android or Ink types.

`:drawing-android` owns the hot path. `DrawingSurface` routes raw `MotionEvent`s, native wet rendering shows the active stroke, `RasterCanvasView` composites the ordered runtime layer stack, and each paint layer's `TileStore` allocates/rasterizes only dirty 256×256 premultiplied ARGB tiles. Pencil and Ink use TipStroke custom families from Jetpack Ink 1.1's experimental API; their wet and final renderers share the same texture store and family. Airbrush uses one variable-width native raster silhouette with a single blur for both its transient preview and tile commit. Eraser gestures apply each new segment directly to affected raster tiles inside a cancellable transaction so lower layers are revealed immediately. Image erase masks reuse the same sparse tile and bounded-history machinery in original image coordinates.

`:app` owns Android lifecycle and normal UI. Compose is used for controls, but no stylus sample enters Compose state.

The Linux development target runs the same APK in a tablet-sized Android Emulator. Mouse, trackpad, and emulator-touch adaptation stay in `DrawingSurface`, so laptop tests exercise the production Android Canvas, Jetpack Ink, sparse tiles, persistence, and Compose shell rather than a parallel desktop renderer. Physical Android hardware remains required for authoritative latency, palm-rejection, and OEM stylus validation.

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

## Replaceable seams

`StrokeRasterizer`, `LiveStrokeRenderer`, and `LayerCompositor` express responsibilities without platform types. A profiled future renderer can replace Android Canvas tiles without changing document APIs. The sealed `Layer` interface permits real future `VectorLayer`/`GroupLayer` implementations without pretending they exist today.

## Threading

The native input/wet-ink path runs directly on the UI thread. Current tile commit also runs there for deterministic handoff. Save/export briefly snapshots immutable copies of allocated tiles on the UI thread, then all JSON, image copying, PNG encoding, compositing, and destination I/O runs on a single background project executor. Atomic directory replacement ensures the prior complete revision survives a failed save.
