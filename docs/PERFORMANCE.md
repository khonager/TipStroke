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

Animation opens only the selected frame as decoded raster tiles. Other saved cels retain immutable compressed PNG bytes and decode on a serial worker before selection, thumbnail generation, or onion-skin display. Unedited decoded cels outside the selected/onion range can be evicted back to their compressed backing. Save and export preserve unloaded cels directly; export decodes one tile at a time. PNG bytes are still read during project loading, and edited cels remain resident, so this is not a total project-memory cap. Duplicate Frame and live drawing carry-forward share unchanged tile bitmaps with copy-on-write detachment at every raster mutation. Undo snapshots remain independent and bounded. Diagnostics count shared resident animation bitmaps once.

The debug overlay reports approximate FPS/event rate, tool, pressure, tilt, zoom, allocated tiles, last dirty tile count, undo bytes, process memory, estimated document bytes, device-available memory, and an advisory count of fully painted raster layers remaining at the current canvas resolution. Android's managed-heap class is not an absolute PSS limit: process PSS also includes code, shared pages, EGL buffers, and other graphics allocations. The advisory budget therefore preserves the observed non-document process baseline and adds 75% of the app heap class as a conservative growing-document allowance, while also respecting Android's current system low-memory threshold. It never prevents layer creation: empty and partially painted layers remain sparse, so the displayed capacity is deliberately a conservative comparison unit rather than a hard layer limit. Process memory is sampled at most once every two seconds to keep it off the input hot path. The overlay is off by default.

## Benchmark next

On the Xiaomi Pad 6, record input-to-photon behavior, first-stroke time, frame pacing at 60/120/144 Hz, fast-stroke continuity, tile commit time, GC/allocation traces, and handoff frames. Move raster preparation off the UI thread only with an atomic commit design proven not to introduce a handoff flash.

## Process recording budgets

No PNG/gzip encoding, disk I/O, frame rendering, or video encoding is added to MotionEvent handling. Recording retains completed command references or existing immutable tile-undo snapshots and compresses them on a dedicated serial encoding worker. Additional incremental Pencil/eraser command capture is limited to 100,000 samples per gesture, falling back to changed tiles when exceeded. The pending encoding queue has a 128 MiB estimated-memory ceiling (null deletion patches count as coordinates, not full bitmaps); overflow stops recording without dropping an action and continuing misleadingly. The compressed archive is bounded to 32 MiB/30,000 actions and stops at its limit; no unlimited undo history is retained. Raster-heavy operations can reach that limit faster than ordinary strokes. Source artwork plus an existing undo budget are separate from these additional recording budgets.

Replay retains at most 128 full-resolution tiles (32 MiB, including temporary overlays) in memory. An LRU spills exact PNG tiles into disposable app-cache files, bounded to 512 MiB and deleted when replay closes or resets. This replaces the old 128 MiB total-tile cutoff; the project archive remains bounded to 32 MiB. Preview has its own worker, so project saving/export and recording compression cannot stall it. The latest seek supersedes queued requests; cancellation is checked between actions, commands and tiles, and a partially applied action resets replay rather than skipping content. The player advances only after receiving a frame and displays completed-frame progress, loading state, and errors; exported frames are released as they are encoded. Backward seeks replay from the beginning, so long recordings may seek slowly. Durable append-only disk journaling, disposable seek checkpoints, reduced-resolution tile reconstruction, and recording reset/pause controls are follow-ups. Device input-latency/thermal testing is still required; JVM correctness tests do not establish pen latency.

An imported image adds one source-resolution PNG asset to the same 32 MiB recording archive. Transforms add only metadata, regardless of enlargement. The encoding worker decodes the source once for capture; its estimated bitmap memory is charged to the recording queue. Replay decodes visible image sources during compositing and releases each after drawing; it does not allocate enlarged image bitmaps.

Fill propagation runs on a dedicated background-priority worker in cancellable batches of up to 4 ms
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
budget. The visual reveal uses the configured 250–5,000 ms elapsed-time clock
(default 2,500 ms) and native canvas mask
compositing; no propagation or per-pixel raster writes occur in animation callbacks.
Only visible tiles are drawn, affected tile bounds are invalidated, and later edits
bypass the effect on their touched tiles. Mask work is independent of zoom and fill
area; actual frame pacing still requires device testing. Preparation latency is
separate from animation duration and can grow with region complexity.

Color-drag preparation waits for 125 ms within a 12-screen-pixel neighborhood.
Only one speculative attempt is allowed per drag. It runs for at most about
150 ms plus the current bounded batch, then yields the executor and retains its
partial result without polling or doing more CPU work. Release resumes it;
movement away, cancellation, or detachment disposes it. Ready results still check
layer identity/revision and connected-region membership before reuse. The native
drop bloom invalidates a small screen-space area at about 30 Hz until the first preparation snapshot. The existing flood accumulates coverage in a grid of at most 256 × 256 cells; immutable loading silhouettes publish no more often than every 80 ms, with at most one queued delivery. After that, worker snapshots alone trigger preview redraws. No second flood or full-resolution layer copy is needed. Ordinary stylus hover does not trigger fill computation.

Photo editing changes a fixed 17×17 vertex mesh, independent stretch and placement metadata; it does not resample or copy a full-resolution photo per pointer sample. Gesture history accounts for both meshes (about 18.2 KiB conservatively accounted per edit, including point objects) in the existing bounded history. Source masks remain sparse 256 px tiles and are mapped on the photo's mesh triangles. Full-resolution exports run on the existing project worker. Validate warp rendering and mask-heavy photos on the tablet before increasing mesh density; current local warps have a fixed spatial resolution.

## iOS first-port budgets

The UIKit renderer allocates sparse 256×256 RGBA artwork tiles and limits their
count to 512 (128 MiB) across 32 layers. Stroke undo/redo retains at most 32 MiB
of conservative before/after tile costs; an individual stroke cannot exceed that
budget and is rolled back if it would. Display caches composite only visible tiles
in separate native CALayers and refreshes only reached tiles during input. These
caches, the in-progress transaction, save snapshots, PNG decoding and export
buffers are additional memory, not included in the 128 MiB artwork limit.

Save snapshots share immutable tile images and copy only dictionary metadata;
subsequent painting replaces reached tiles. A single serial worker encodes PNGs
and writes revision files. The editor coalesces pending saves to one latest snapshot.
A full bitmap is allocated only for explicit flattened export, capped by the
4096 px canvas limit. No full canvas/layer copy occurs per stroke. The first port
still needs device profiling for latency, memory pressure and long-session behavior.

Animation progress recording attaches to distinct sparse cel stores and captures edits on the encoding worker. Switching to an already recorded cel changes metadata rather than copying its pixels. New/replaced cels and reopening animated projects capture allocated tiles as baselines; these obey the existing queue/archive budgets. Deleted stores release recorder references. Playback-only frame changes are omitted.

Animation decoding never installs an incomplete frame as the editable target. The timeline shows loading while selection waits; the previous frame remains visible. Decode failures leave the current artwork intact. Thumbnail storage is limited to 16 previews. Resizing an animated canvas prepares all its frames before applying the resize.

Selected-pixel rotate/resize/flip previews change only a native drawing matrix. Apply snapshots only occupied selected source tiles and intersecting destination tiles, resamples once, removes empty output tiles, and retains the operation in the existing bounded undo history. Unselected pixels and shared tiles in other frames are preserved. Nearest-neighbor sampling is available for pixel art.

Playback allows only one outstanding frame selection while decoding is slow; later timer ticks do not enqueue more frame selections. Pausing cancels the pending selection so a completed decode cannot advance the visible frame afterward. Concurrent thumbnail, onion-skin, and resize requests pin their required clean frames until their completion callbacks finish.

### Live eraser updates

Normal raster erasing uses analytic segment coverage in only the newly reached pixel
rectangles. One reusable integer pixel buffer serves the gesture; sparse byte coverage
arrays are allocated only on first tile contact. There are no per-update blur bitmap
allocations, alpha extraction, or whole-tile pixel scans on this path. Coverage is
applied to the existing touch-down snapshots and released on finish/cancel. Pressure
interpolates along segments; event batching does not change the result. Constrained
selection/reference erasing and pixel tools retain the clipped mask renderer.

Native pixel tests cover long fast segments, changing pressure, sample density, event
batching, tile boundaries, cancellation, undo/redo, and replay. Device profiling is
still required to measure end-to-end stylus latency; host test timings are not a
substitute for input-to-display measurements.

On the connected Xiaomi 23043RP34G (Android 14), a warmed horizontal sweep
measured median live-update times of 1.47/8.21/29.44 ms at 48/128/256 px. The
retained mask path with a full-canvas selection measured 10.97/22.58/89.18 ms.
This comparison includes the selection clip in the baseline; it is a rendering
microbenchmark, not an input-to-display latency measurement. The largest brush
still exceeds a 60 Hz frame budget. Reproduce with
`./gradlew :drawing-android:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.tipstroke.drawing.android.LiveEraserInstrumentedTest`.
The test writes per-device timings to `eraser-qa/update-times.txt` in connected
test additional output and verifies sample-join continuity on real Android.
