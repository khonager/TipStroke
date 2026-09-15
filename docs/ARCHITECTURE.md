# Architecture

## Boundaries

`:core` is platform-neutral Kotlin. It owns `Document`, `CanvasSpec`, the sealed `Layer` contract, `RasterLayer`, non-destructive `ImageLayer` metadata, versioned `BrushPreset`, colors, transform/tile math, samples, renderer contracts, and bounded undo history. It contains no Android or Ink types.

`:drawing-android` owns the hot path. `DrawingSurface` routes raw `MotionEvent`s, native wet rendering shows the active stroke, `RasterCanvasView` composites the ordered runtime layer stack, and each paint layer's `TileStore` allocates/rasterizes only dirty 256×256 premultiplied ARGB tiles. Pencil and Ink use Jetpack Ink. Airbrush and eraser gestures apply each new segment directly to affected raster tiles inside a cancellable transaction, so the wet composite is the final composite without replaying the growing stroke on every sample. Image erase masks reuse the same sparse tile and bounded-history machinery in original image coordinates.

`:app` owns Android lifecycle and normal UI. Compose is used for controls, but no stylus sample enters Compose state.

The app shell owns three destinations: local gallery, editor, and settings. `DrawingLibrary` and `ProjectPersistence` live in `:drawing-android` because decoding/encoding tiles and imported assets requires Android bitmap/content-resolver types. Gesture choices remain platform-neutral values in `:core` and are persisted by the app with local preferences.

## Stroke pipeline

```text
MotionEvent
  ├─ capture pressure, tilt, orientation, button, pointer ID and time
  ├─ Jetpack Ink 1.0 InProgressStrokesView (immediate wet stroke)
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

The callback invalidates the raster view and removes Ink’s finished stroke in the same UI-thread run loop to avoid a gap or double-opacity frame. Ink `Stroke` objects are not stored as document truth.

## Image layers

An image layer keeps its persisted Android document URI and original pixel dimensions as authoritative source data. Center, scale, rotation, visibility, opacity, and a sparse alpha-removal mask are separate metadata. Direct canvas gestures update only transform metadata: drag moves, pinch resizes, and twist rotates. Eraser samples are inverse-transformed into original image coordinates and added to the mask. Rendering applies that mask while compositing the immutable decoded source; changing scale or erasing never writes into the source bitmap. Returning to 100% therefore returns to the original source resolution rather than enlarging an already-downsampled intermediate.

Image decoding runs on a dedicated background executor. Dimension probing occurs once at import. The current milestone caches a full decoded bitmap per imported image, so very large or numerous images may still encounter device memory limits; tiled image pyramids are a future optimization and do not require a project-format change.

## Replaceable seams

`StrokeRasterizer`, `LiveStrokeRenderer`, and `LayerCompositor` express responsibilities without platform types. A profiled future renderer can replace Android Canvas tiles without changing document APIs. The sealed `Layer` interface permits real future `VectorLayer`/`GroupLayer` implementations without pretending they exist today.

## Threading

The native input/wet-ink path runs directly on the UI thread. Current tile commit also runs there for deterministic handoff. Save/export briefly snapshots immutable copies of allocated tiles on the UI thread, then all JSON, image copying, PNG encoding, compositing, and destination I/O runs on a single background project executor. Atomic directory replacement ensures the prior complete revision survives a failed save.
