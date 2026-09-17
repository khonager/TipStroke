# Performance

The latency budget belongs to stylus sampling and wet rendering. `MotionEvent`s go directly to the native drawing surface—Jetpack Ink for Pencil and Ink, a native raster preview for Airbrush, and sparse tile transactions for erasing. Compose sees only occasional diagnostics/control state.

The old Airbrush approaches either replayed individually blurred segments, producing darker input-sample bands, or stacked native Ink coats, producing concentric outlines. The soft Airbrush now rebuilds only its current in-memory preview silhouette as samples arrive, applies one native blur to that union, and touches no permanent pixels before pen-up. Commit draws the same silhouette only into intersecting sparse tiles. Erasing uses incremental sparse-tile transactions: each update rasterizes only its new segment and schedules invalidation for that document region. Tile compositing rejects tiles outside the current canvas clip.

Current safeguards:

- Ink is eagerly initialized before the first stroke.
- The procedural Pencil texture is generated once per drawing surface; custom Ink families are cached in a 32-entry LRU keyed by effective tuning.
- Airbrush holds only the current sample list and unified preview path while drawing; it neither copies full layers nor mutates or redraws permanent tiles per sample.
- Unbuffered stylus dispatch and motion prediction are requested.
- Canvas pixels use sparse 256×256 tiles; only intersecting tiles allocate and redraw.
- Undo snapshots cover affected tiles only and have a 96 MiB configurable budget.
- Camera navigation is a matrix; artwork is not transformed or resampled.
- No storage, networking, database, DI framework, or serialization exists in the hot path.

The debug overlay reports approximate FPS/event rate, tool, pressure, tilt, zoom, allocated tiles, last dirty tile count, undo bytes, process memory, estimated document bytes, device-available memory, and an advisory count of fully painted raster layers remaining at the current canvas resolution. Android's managed-heap class is not an absolute PSS limit: process PSS also includes code, shared pages, EGL buffers, and other graphics allocations. The advisory budget therefore preserves the observed non-document process baseline and adds 75% of the app heap class as a conservative growing-document allowance, while also respecting Android's current system low-memory threshold. It never prevents layer creation: empty and partially painted layers remain sparse, so the displayed capacity is deliberately a conservative comparison unit rather than a hard layer limit. Process memory is sampled at most once every two seconds to keep it off the input hot path. The overlay is off by default.

## Benchmark next

On the Xiaomi Pad 6, record input-to-photon behavior, first-stroke time, frame pacing at 60/120/144 Hz, fast-stroke continuity, tile commit time, GC/allocation traces, and handoff frames. Move raster preparation off the UI thread only with an atomic commit design proven not to introduce a handoff flash.
