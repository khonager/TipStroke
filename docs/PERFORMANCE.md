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
- Raster tiles use nearest-neighbor presentation on canvases up to 128×128 px and at 8× zoom or above. Pixel tools paint exact integer cells into the existing sparse tiles; wet marks are drawn by the native raster view and only intersecting tiles are committed.
- Layer thumbnails fit their visible content. Exact alpha bounds are cached per sparse tile, and only dirty tiles are rescanned after paint changes; preview recomposition does not scan an entire layer or allocate a canvas-sized bitmap.
- No storage, networking, database, DI framework, or serialization exists in the hot path.
- Animation cels remain separate sparse tile stores. Holds are metadata, onion skins draw neighboring cels without duplicating them, and export renders one frame at a time on the project executor. Moving a lasso no longer allocates blank destination tiles across the lasso's full bounding box.

Animation currently decodes all saved raster cels when a project opens and duplicates allocated cel tiles for an independent Duplicate Frame action. Live drawing recording carries only the new drawing layer's allocated tiles into each advancing frame; long sessions with a dense drawing can still consume substantial memory. Lazy cel loading and tighter animation memory limits are the next performance step; they must preserve native input latency and bounded undo history.

The debug overlay reports approximate FPS/event rate, tool, pressure, tilt, zoom, allocated tiles, last dirty tile count, undo bytes, process memory, estimated document bytes, device-available memory, and an advisory count of fully painted raster layers remaining at the current canvas resolution. Android's managed-heap class is not an absolute PSS limit: process PSS also includes code, shared pages, EGL buffers, and other graphics allocations. The advisory budget therefore preserves the observed non-document process baseline and adds 75% of the app heap class as a conservative growing-document allowance, while also respecting Android's current system low-memory threshold. It never prevents layer creation: empty and partially painted layers remain sparse, so the displayed capacity is deliberately a conservative comparison unit rather than a hard layer limit. Process memory is sampled at most once every two seconds to keep it off the input hot path. The overlay is off by default.

## Benchmark next

On the Xiaomi Pad 6, record input-to-photon behavior, first-stroke time, frame pacing at 60/120/144 Hz, fast-stroke continuity, tile commit time, GC/allocation traces, and handoff frames. Move raster preparation off the UI thread only with an atomic commit design proven not to introduce a handoff flash.

## Process recording budgets

No PNG/gzip encoding, disk I/O, frame rendering, or video encoding is added to MotionEvent handling. Recording retains completed command references or existing immutable tile-undo snapshots and compresses them on a dedicated serial encoding worker. Additional incremental Pencil/eraser command capture is limited to 100,000 samples per gesture, falling back to changed tiles when exceeded. The pending encoding queue has a 128 MiB estimated-memory ceiling (null deletion patches count as coordinates, not full bitmaps); overflow stops recording without dropping an action and continuing misleadingly. The compressed archive is bounded to 32 MiB/30,000 actions and stops at its limit; no unlimited undo history is retained. Raster-heavy operations can reach that limit faster than ordinary strokes. Source artwork plus an existing undo budget are separate from these additional recording budgets.

Replay retains at most 128 full-resolution tiles (32 MiB, including temporary overlays) in memory. An LRU spills exact PNG tiles into disposable app-cache files, bounded to 512 MiB and deleted when replay closes or resets. This replaces the old 128 MiB total-tile cutoff; the project archive remains bounded to 32 MiB. Preview has its own worker, so project saving/export and recording compression cannot stall it. The latest seek supersedes queued requests; cancellation is checked between actions, commands and tiles, and a partially applied action resets replay rather than skipping content. The player advances only after receiving a frame and displays completed-frame progress, loading state, and errors; exported frames are released as they are encoded. Backward seeks replay from the beginning, so long recordings may seek slowly. Durable append-only disk journaling, disposable seek checkpoints, reduced-resolution tile reconstruction, and recording reset/pause controls are follow-ups. Device input-latency/thermal testing is still required; JVM correctness tests do not establish pen latency.

An imported image adds one source-resolution PNG asset to the same 32 MiB recording archive. Transforms add only metadata, regardless of enlargement. The encoding worker decodes the source once for capture; its estimated bitmap memory is charged to the recording queue. Replay decodes visible image sources during compositing and releases each after drawing; it does not allocate enlarged image bitmaps.

Fill propagation runs on a dedicated worker in cancellable batches of up to 4 ms
or 16,384 region pixels. Tile copies needed by the worker are scheduled one at a
time on the main thread; the worker never reads a mutable live bitmap. Sparse
visited bitsets, a distance-ordered frontier, and lazily captured source tiles
avoid full-canvas/layer copies. Source pixels use a 32 MiB FIFO cache; evicted tiles
are reloaded on demand rather than rejecting a large connected region. Fill output
is bounded by the finite document (up to 8192 × 8192), not a fixed 128-tile preview
cutoff. A spill may therefore allocate a full document's raster tiles, just like
painting that area. Large results and their before/after history still require
memory proportional to the affected area.

Arrival data uses two bytes per reached pixel in sparse tile arrays, half the old
ARGB arrival storage, and is converted to one-byte ALPHA_8 masks. Arrays are released
after conversion. Two-pixel edge cleanup has its own sparse byte visitation maps;
only softened boundaries perform the more expensive local color-mixture fitting.

After preparation, commit snapshots only touched tiles under the existing undo
budget. The visual reveal uses an elapsed 2,500 ms clock and native canvas mask
compositing; no propagation or per-pixel raster writes occur in animation callbacks.
Only visible tiles are drawn, affected tile bounds are invalidated, and later edits
bypass the effect on their touched tiles. Mask work is independent of zoom and fill
area; actual frame pacing still requires device testing. Preparation latency is
separate from animation duration and can grow with region complexity.
