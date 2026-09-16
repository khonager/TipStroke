# Performance

The latency budget belongs to stylus sampling and wet rendering. `MotionEvent`s go directly to the native drawing surface—Jetpack Ink for Pencil, Ink, and Airbrush, and sparse tile transactions for erasing. Compose sees only occasional diagnostics/control state.

The old Airbrush replayed blurred circle segments into tiles while drawing. That avoided full-stroke replay but still issued many Canvas blur operations and produced overlapping edge bands. The particle Airbrush now stays in Ink's native wet path and commits its finished textured mesh once into intersecting tiles. Erasing alone uses incremental sparse-tile transactions: each update rasterizes only its new segment and schedules invalidation for that document region. Tile compositing rejects tiles outside the current canvas clip.

Current safeguards:

- Ink is eagerly initialized before the first stroke.
- Procedural brush textures are generated once per drawing surface; custom families are cached in a 32-entry LRU keyed by effective tuning.
- Stationary Airbrush emission is capped at 60 particles per second, and tessellation tolerances stay below one screen pixel at the 1200% zoom limit without generating invisible subpixel geometry.
- Unbuffered stylus dispatch and motion prediction are requested.
- Canvas pixels use sparse 256×256 tiles; only intersecting tiles allocate and redraw.
- Undo snapshots cover affected tiles only and have a 96 MiB configurable budget.
- Camera navigation is a matrix; artwork is not transformed or resampled.
- No storage, networking, database, DI framework, or serialization exists in the hot path.

The debug overlay reports approximate FPS/event rate, tool, pressure, tilt, zoom, allocated tiles, last dirty tile count, and undo bytes. It is off by default.

## Benchmark next

On the Xiaomi Pad 6, record input-to-photon behavior, first-stroke time, frame pacing at 60/120/144 Hz, fast-stroke continuity, tile commit time, GC/allocation traces, and handoff frames. Move raster preparation off the UI thread only with an atomic commit design proven not to introduce a handoff flash.
