# Architecture

## Boundaries

`:core` is platform-neutral Kotlin. It owns `Document`, `CanvasSpec`, the sealed `Layer` contract, the currently implemented `RasterLayer`, versioned `BrushPreset`, colors, transform/tile math, samples, renderer contracts, and bounded undo history. It contains no Android or Ink types.

`:drawing-android` owns the hot path. `DrawingSurface` routes raw `MotionEvent`s, `InProgressStrokesView` renders wet ink, `RasterCanvasView` composites permanent tiles, and `TileStore` allocates/rasterizes only dirty 256×256 premultiplied ARGB tiles.

`:app` owns Android lifecycle and normal UI. Compose is used for controls, but no stylus sample enters Compose state.

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

## Replaceable seams

`StrokeRasterizer`, `LiveStrokeRenderer`, and `LayerCompositor` express responsibilities without platform types. A profiled future renderer can replace Android Canvas tiles without changing document APIs. The sealed `Layer` interface permits real future `VectorLayer`/`GroupLayer` implementations without pretending they exist today.

## Threading

The native input/wet-ink path runs directly on the UI thread. Current tile commit also runs there for deterministic handoff; the next renderer milestone should prepare tile work off-thread while presenting the commit atomically. Disk serialization must never run on the input thread.
