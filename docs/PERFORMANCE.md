# Performance

The latency budget belongs to stylus sampling and wet rendering. `MotionEvent`s go directly to the native drawing surface—Jetpack Ink for Pencil/Ink and sparse tile transactions for Airbrush/eraser. Compose sees only occasional diagnostics/control state.

Airbrush and eraser samples use incremental sparse-tile transactions because replaying an increasingly long blurred path for every input sample becomes quadratic. Each update rasterizes only the new segment into intersecting tiles and schedules invalidation for that document region. Tile compositing also rejects tiles outside the current canvas clip.

Current safeguards:

- Ink is eagerly initialized before the first stroke.
- Unbuffered stylus dispatch and motion prediction are requested.
- Canvas pixels use sparse 256×256 tiles; only intersecting tiles allocate and redraw.
- Undo snapshots cover affected tiles only and have a 96 MiB configurable budget.
- Camera navigation is a matrix; artwork is not transformed or resampled.
- No storage, networking, database, DI framework, or serialization exists in the hot path.

The debug overlay reports approximate FPS/event rate, tool, pressure, tilt, zoom, allocated tiles, last dirty tile count, and undo bytes. It is off by default.

## Benchmark next

On the Xiaomi Pad 6, record input-to-photon behavior, first-stroke time, frame pacing at 60/120/144 Hz, fast-stroke continuity, tile commit time, GC/allocation traces, and handoff frames. Move raster preparation off the UI thread only with an atomic commit design proven not to introduce a handoff flash.
