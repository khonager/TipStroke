# Performance

The latency budget belongs to stylus sampling and wet rendering. `MotionEvent`s go directly to the native drawing surface—Jetpack Ink for Pencil and Ink, a native raster preview for Airbrush, and sparse tile transactions for erasing. Compose sees only occasional diagnostics/control state.

The old Airbrush approaches either replayed individually blurred segments, producing darker input-sample bands, or stacked native Ink coats, producing concentric outlines. The soft Airbrush now rebuilds only its current in-memory preview silhouette as samples arrive, applies one native blur to that union, and touches no permanent pixels before pen-up. Commit draws the same silhouette only into intersecting sparse tiles. Erasing uses incremental sparse-tile transactions: each display update rasterizes its accumulated new segments and schedules invalidation for that document region. Tile compositing rejects tiles outside the current canvas clip.

Current safeguards:

- Ink is eagerly initialized before the first stroke.
- The procedural Pencil texture is generated once per drawing surface; custom Ink families are cached in a 32-entry LRU keyed by effective tuning.
- Airbrush holds the current sample list and a bounded viewport coverage buffer while drawing; it neither copies full layers nor mutates or redraws permanent tiles per sample.
- Unbuffered stylus dispatch and motion prediction are requested.
- Canvas pixels use sparse 256×256 tiles; only intersecting tiles allocate and redraw.
- Undo snapshots cover affected tiles only. The default budget reserves a full raster layer and 32 MiB of working space within 75% of the app heap, clamped to 8–96 MiB; a single oversized edit can exceed that budget to retain its undo.
- Camera navigation is a matrix; artwork is not transformed or resampled.
- Raster tiles use nearest-neighbor presentation on canvases up to 128×128 px and at 8× zoom or above. Pixel tools paint exact integer cells into the existing sparse tiles; wet marks are drawn by the native raster view and only intersecting tiles are committed.
- Layer thumbnails fit their visible content. Exact alpha bounds are cached per sparse tile, and only dirty tiles are rescanned after paint changes; preview recomposition does not scan an entire layer or allocate a canvas-sized bitmap.
- No storage, networking, database, DI framework, or serialization exists in the hot path.
- Animation cels remain separate sparse tile stores. Holds are metadata, onion skins draw neighboring cels without duplicating them, and export renders one frame at a time on the project executor. Moving a lasso no longer allocates blank destination tiles across the lasso's full bounding box.

Animation opens only the selected frame as decoded raster tiles. Other saved
cels retain immutable disk-backed PNG handles, independent of the replaceable
project directory. A serial decoding worker prepares selection/onion/thumbnail
frames. Inactive edited cels are compressed to private working files on a separate
serial worker, one cel at a time. A copy-on-write lease pins source pixels; a
revision check rejects stale compression if the user edits that cel meanwhile.
Eviction rechecks the current/neighbor/loading pins before releasing pixels.
Cold cel undo history is cleared only after backing is available; the current and
onion range retain their bounded histories. Live drawing recording suspends
trimming until recording finishes.

Save/export snapshots share immutable backing handles; autosave hard-links PNGs
when possible and copies files otherwise. Export decodes one tile at a time.
Neither all compressed PNG bytes nor all edited raster cels need stay in RAM.
Storage failures retain original pixels, report an error, and stop further spill
attempts for that open runtime. This is a working-set improvement, not an absolute
process-memory cap: active layers, onion skins, recent edits waiting for the worker,
histories, image sources and codec buffers still cost memory. Disk use grows with
authored artwork. Weak backing leases protect in-flight snapshots; dead revisions
are swept periodically on worker writes and stale working files on first use in
the next process. Working files use private files storage, not OS-purgeable cache.
Duplicate Frame and drawing carry-forward share unchanged bitmaps with COW
mutation. Diagnostics count shared resident bitmaps once and file handles as zero
pixel payload bytes.

The debug overlay reports approximate FPS/event rate, tool, pressure, tilt, zoom, allocated tiles, last dirty tile count, undo bytes, process memory, estimated document bytes, device-available memory, and an advisory count of fully painted raster layers remaining at the current canvas resolution. Android's managed-heap class is not an absolute PSS limit: process PSS also includes code, shared pages, EGL buffers, and other graphics allocations. The advisory budget therefore preserves the observed non-document process baseline and adds 75% of the app heap class as a conservative growing-document allowance, while also respecting Android's current system low-memory threshold. This live headroom estimate is advisory and separate from the canvas layer admission limit. Memory is also sampled with the overlay off: elevated pressure appears in Layers and entering critical pressure shows a dismissible warning. Process memory is sampled at most once every two seconds to keep it off the input hot path. The overlay is off by default.

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
rectangles. One reusable integer pixel buffer serves the gesture; sparse coverage and nearest-
centerline distance arrays are allocated only on first tile contact (one byte plus
one float per pixel). The distance field lets a continuing pressure gradient replace
its provisional endpoint without retaining circular cap imprints. There are no per-update blur bitmap
allocations, alpha extraction, or whole-tile pixel scans on this path. Coverage is
applied to the existing touch-down snapshots and released on finish/cancel. Pressure
interpolates along segments; event batching does not change the result. Constrained
selection/reference erasing and pixel tools retain the clipped mask renderer.

Native pixel tests cover long fast segments, changing pressure, sample density, event
batching, tile boundaries, cancellation, undo/redo, and replay. Device profiling is
still required to measure end-to-end stylus latency; host test timings are not a
substitute for input-to-display measurements.

Before the nearest-centerline pressure correction, on the connected Xiaomi
23043RP34G (Android 14), a warmed horizontal sweep
measured median live-update times of 1.47/8.21/29.44 ms at 48/128/256 px. The
retained mask path with a full-canvas selection measured 10.97/22.58/89.18 ms.
This comparison includes the selection clip in the baseline; it is a rendering
microbenchmark, not an input-to-display latency measurement. The largest brush
still exceeds a 60 Hz frame budget. Reproduce with
`./gradlew :drawing-android:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.tipstroke.drawing.android.LiveEraserInstrumentedTest`.
The test writes per-device timings to `eraser-qa/update-times.txt` in connected
test additional output and verifies sample-join continuity on real Android.

The pressure-band correction has host native-graphics regression coverage for
falling opacity, simultaneous width/opacity changes, and sparse versus dense
samples. Its new device pressure-gradient test also writes a review PNG; rerun
the device benchmark before treating the earlier timings as current.

## Custom shape-and-grain brushes

Custom brush sources are bounded immutable 256 × 256 coverage maps. Image import
checks encoded size/dimensions and downsamples off the input thread. A live stroke
prepares its two source shaders once, retains arc-length sampling state, and records
newly emitted stamps into native GPU batches on Android 14+ ordinary painting;
older Android and constrained paths draw them into intersecting sparse tiles. Transparent faded stamps
allocate no tiles. Stamp count, scatter, size, source dimensions, and path-step
limits bound work; no full-canvas copies or sample-time file access are added.
The studio test pad is native and separately bounded to 16 × 2,048 samples and a
1,600 × 600 preview bitmap. Real-device profiling is still required for worst-case
large-tip, dense multi-stamp brushes.

## Imported media

Video preview is hardware decoded to a native TextureView; it no longer seeks via
MediaMetadataRetriever for each playback tick. Raster paint redraws at authored
interval boundaries, not video FPS. Source-clock UI updates are limited to 10 Hz.
Scrub UI requests are limited to roughly 16 Hz plus the final position, and player
seeks retain only the latest pending target. No video thumbnails are requested.
A video eyedropper gesture captures at most one 1280 px bitmap.

GIF preview retains one worker decoder, one in-flight request, one replacement
request, and one 1280 px bitmap. Playback can skip preview samples to preserve
source speed. Source decoder allocations are separate from these bitmap bounds.
Scrubbing creates no drawing slots or raster tiles. Only authored drawings have
metadata and sparse cels. Saved cels outside the active/next/onion range unload; edited inactive cels
spill asynchronously to disk. Active paintings, pending spills and codecs still
have a memory cost. Native pointer samples never decode media or write files.

Export still decodes/composites/encodes individual samples on a worker. Smooth
60 FPS output is more expensive than 12 FPS output; both keep the same duration.
Export holds one rendered bitmap at a time, but source codecs and the encoder have
their own buffers. There is a 36,000-output-frame limit. Measure native playback
frame drops, seek latency, memory, thermal behavior, and export throughput on
physical devices with representative long-GOP, rotated, and variable-rate clips.

Tip selection and slider endpoint thumbnails share the native raster preview view.
They render a fixed document-space sample into an output-sized cached bitmap,
recycled on parameter/size changes and detach. Endpoint definitions are immutable
copies, with no updates to the selected tip or canvas while comparing limits.
Hover labels observe UI hover events only; the drawing/input pipeline is unchanged.


## Android layer allowance

New drawing and Layers show `used/maximum Layers`. The allowance uses 75% of the
device heap class divided by the tile-rounded full-canvas RGBA size, reserves two
full layers for edits/undo, and clamps the result to 1–64. It is a conservative
admission policy, not preallocation or a guarantee against all memory pressure.
Photos count as layers; source caches, history, animation cels and recording also
use memory, so the live warning can appear before the layer allowance is reached.
A smaller canvas permits more layers. Resizing recalculates the allowance.

Add Layer and Import are disabled at the limit. Native entry points also reject
layer creation, duplication, selection copy/cut, cel copy/move, and live drawing
recording before mutating artwork. Existing documents above the allowance still
open and remain editable; new layers require deleting layers or reducing canvas
size. Permanent artwork remains sparse 256 px tiles with bounded undo.


## Reducing drawing resolution

Resize → Reduce resolution offers 50% or 25% on both axes (dimensions round up).
It resamples all raster layers/cels and scales every image transform and media
placement, without flattening layers or changing timing. Half dimensions use
approximately one quarter of the raster pixels; quarter dimensions use one
sixteenth, subject to 256 px tile rounding. The layer admission formula is
recomputed at the new size. Original image/video assets and source-coordinate
image masks remain unchanged, so their memory/storage costs do not shrink.

The operation pins original stores with COW leases, runs on the project worker,
and installs results only after every cel is ready. It uses one output tile plus
a source window at most 1025×1025 px for quarter reduction; cold source tiles decode
only into that window. Output PNGs go straight to working storage; only active
raster layers decode for commit. No full-project decode or canvas-sized bitmap is
needed. Failures during preparation preserve original pixels and dimensions.
The editor blocks drawing during preparation, clears undo/selection on success,
and stops process-recording continuation at the old resolution. Reduce is a
permanent resample: export a project copy first to preserve full-resolution detail.

## Edited video tracks

No source video is expanded into raster cels. Clip edits and the last 50 undo
revisions retain metadata and immutable encoded assets. Preview decoding is
worker-only at a maximum 1280 px edge, with one active request and one replacement;
crossfades hold at most two source decoders. Photo previews are reused until
source/preview-size changes. No filmstrip thumbnails are decoded during scrubbing.
Edited video playback currently seeks through MediaMetadataRetriever and can
drop preview frames, particularly for long-GOP footage. It keeps the wall-clock
speed; export samples every planned output frame. Sequential hardware decoding
and GPU composition remain performance follow-ups. Validate on physical tablets
before claiming production frame pacing, stylus latency, or thermal behavior.

## Dense stamps, eraser batches, and screen-sized tips

The `easy finger` reproduction is a 180 px round stamp at 0.02 spacing (one mark
every 3.6 document pixels), flow 0.5834822 and no grain. Its previous path evaluated
a composed shape/grain shader over every stamp bounding square, even with grain
disabled, and allocated a color filter for every stamp on every tile. The no-grain
path now blits the shape bitmap and reuses the tint filter. A stable size/angle/tint
also caches a transformed, tinted tip (at most 1024 × 1024 ARGB pixels), released
with the stroke. It preserves stamp count and flow; this is not event dropping or a change to the imported preset.

The direct eraser reads original/result pixels once per intersecting tile rectangle
per input batch, writes the union of changed rectangles once, and rejects more
distant centerline coverage before evaluating pressure/falloff. Two reusable
256 × 256 integer buffers serve the whole gesture. The sparse per-tile byte-alpha
and float-distance fields, cancellation, and pressure-band correction remain.

Airbrush input no longer copies the complete sample list on every MotionEvent.
Input and drawing share the UI thread; completion freezes the samples. Its preview
coverage buffer grows in powers of two to at most 1024 × 1024 ARGB pixels (4 MiB),
plus a temporary extracted alpha mask with the scaled blur halo. It is released on
completion, cancellation or view detachment. It contains coverage only, never a copy
of artwork. The full-resolution pressure painter sizes its scratch halo from actual
softness, and downsamples only broad soft coverage so its source workspace stays
at most 1028 × 1028 pixels. Sharp coverage remains at document resolution.

These changes reduce redundant work and bound soft-mask workspace; they do not
establish constant latency at arbitrary brush size/resolution. Stamps and eraser
still process reached full-resolution tiles; long airbrush strokes still replay
samples, and pen-up still commits on the UI thread. High-resolution screen-sized
strokes can reach many tiles. Physical-device input-to-display profiling is required
before claiming a frame-rate guarantee. Use `LiveEraserInstrumentedTest` and
`BrushPerformanceInstrumentedTest` for render-only timings; neither measures display
latency. Future worker commits must preserve ordered edits and an atomic wet/dry
handoff rather than mutate live bitmaps concurrently.

A temporary Robolectric native-Skia comparison against the pre-change classes on
this Linux host (3 warm-ups, 11 measured runs) gave median 219.46 → 46.00 ms for a
720-document-pixel dense round-stamp sweep at 180 px, and 32.98 → 18.56 ms for a
25-sample eraser batch on one 256 px tile at 180 px. These are host microbenchmarks,
not Android frame times or pen latency; they isolate the redundant rendering work.
Use the checked-in device benchmarks for representative hardware measurements.


## Jetpack Ink comparison path

The separate Android Ink category allows comparison without changing existing
custom presets. Ordinary freehand authoring uses InProgressStrokesView and native
MotionEvents, including pressure and prediction. Soft spray uses one GPU texture
per particle rather than CPU raster stamps during wet authoring. This is not a
guarantee of equal cost for all tips: dense particles still create geometry and
overdraw, and screen-size mode can reach many full-resolution document tiles.

Finished meshes use their actual geometry bounds and a reusable 1024 px
HardwareBufferRenderer atlas, batching up to sixteen sparse 256 px tiles per GPU
submission/readback. Strokes reaching at least 128 tiles use spatial 2048 px
regions to draw the geometry once per region rather than once per tile. Only
requested sparse tiles enter the permanent raster layer. Thumbnail
rendering retains a separate 256 px target. Completion currently runs synchronously on the UI thread;
large marks can therefore pause at pen-up. No full-canvas readback or allocation
is introduced. Creator thumbnails cache their output but rendering a changed
thumbnail also incurs readback. Reference-constrained, fitted-shape, and animation
paths can rebuild geometry. Moving ordered tile completion to a worker with an
atomic wet/dry handoff remains follow-up work, as does physical stylus latency
profiling. Emulator correctness tests do not establish hardware frame pacing.

`JetpackInkInstrumentedTest` exercises the actual pinned native families and GPU
tile renderer, confirms visible pixels and preview/commit agreement, and checks
undo/redo. API 34 is required; existing brushes retain their prior support range.


## Physical-device profiling at 5632 × 5632

`ScreenStrokePerformanceTest` schedules 250 Hz native stylus MotionEvents for a
320 ms crossing of a fitted, single-layer 5632 × 5632 document. It tests both a
180 document-pixel tip and a 180 physical-screen-pixel tip (resolved through camera
scale), twice per case, with pressure 0.7 and tilt 0.3 radians. Eraser cases start with painted tiles. It records scheduled
input queue delay, handler cost, native Ink receipt-to-draw-call timing, UI vsync
gaps, and pen-up-to-history handoff. These are synthetic software measurements,
not digitizer-to-photon latency. Two strokes are useful reproductions, not a
statistically robust thermal/endurance benchmark. The requested 120 Hz refresh was
reported as 60 Hz on both connected devices during these runs.

The earlier [Xiaomi Pad 6 baseline](performance/tablet-before.txt) at this resolution measured 2,278 ms p95 input
queue delay for the large dense custom brush and about 2–2.9 seconds for comparison
Ink handoff. The S25 comparison (SM-S931B, API 37) measured the GPU stamp/Ink changes.
The tablet was then reconnected for final native eraser, frame batching, Airbrush
commit and memory tests. The S25 report predates these final raster-path changes.

On Android 14+, ordinary custom stamp painting now records **new stamps only** into
native RenderNodes and displays them at the current camera scale. Pen-up renders
the same display lists into full-resolution sparse tiles through the bounded atlas.
Stamp count, spacing, shape, scatter, grain and pressure sampling are unchanged;
GPU/software antialiasing can differ slightly. Selection and layer clipping remain
in force. Reference masks and live animation recording retain the existing raster
path. Cancellation discards provisional nodes. The display list is capped at
2,048 batches / 65,536 stamps; exceeding it replays into the existing incremental
raster path for the rest of that gesture, preserving one undo transaction.
No full canvas bitmap or per-sample Compose state is introduced.

Solid Ink particles retain native geometry: a textured-quad experiment improved
short strokes but regressed sustained coverage on the tablet and was removed.
Fully rounded square-aspect Soft particles use their existing circular texture;
non-square tips keep native capsule geometry.

Input diagnostics publish at most 10 times per second with the overlay enabled;
with it disabled, MotionEvents publish no diagnostics into Compose. Camera and
memory notifications remain separate.

See the checked-in [S25 baseline](performance/s25-before.txt) and
[S25 updated run](performance/s25-after.txt) for measurements and remaining stalls.
Pen-up tile completion is still synchronous. An asynchronous implementation must
preserve edit order, keep completed wet strokes visible until atomic tile handoff,
bound pending coverage, and coordinate undo, save, layer changes and cancellation.
Large Pencil/eraser pixel work and synchronous completion remain bottlenecks; these
changes do **not** establish fluid drawing for every brush at every resolution.

## One-layer memory warnings

Layer admission and actual painting headroom are separate. Having no allowance
for another fully painted layer must not trigger a critical painting warning.
Warnings now compare tracked document bytes (including undo and images) to the
75% heap allowance, plus system low-memory headroom. Process PSS is still displayed
but includes driver/code/shared allocations outside that allowance. Critical means
95% document use, less than 8 MiB usable headroom, or Android low memory; elevated
means 85% use or less than 24 MiB headroom. Discarding redo on a new edit subtracts
its retained bytes, preventing phantom growth in the memory estimate.

`OneLayerMemoryInstrumentedTest` fills all 484 tiles of a 5632 square (121 MiB),
adds twelve ordinary strokes, verifies bounded history, normal memory pressure,
and undo/redo on the physical device. This does not promise unlimited history or
room for every single giant edit, photos, animation frames or recording buffers.


The earlier short-stroke tablet run is in [tablet-after.txt](performance/tablet-after.txt);
it predates the whole-canvas follow-up below and is not the final acceptance result. Pencil
and single-target eraser batches retain every input sample but update raster pixels
once per display frame, reducing redundant tile access. Pen-up flushes outstanding
samples before finalizing the undo transaction. The eraser pixel kernel uses NDK
C++ with float contraction disabled, retaining the Kotlin nearest-centerline and
pressure rules. Native/Kotlin differential tests require identical pixels, including
translucent originals and intersecting strokes. Tile buffers stay in managed code;
the native critical region only calculates one bounded tile rectangle.

Soft Airbrush commit now shares one blur halo across a 1024 px (4 × 4 tile) region.
It never caches or copies existing artwork into that buffer. The existing broad-soft
mask downsampling remains; a region's coverage, halo and extracted alpha need up
to roughly 21 MiB of temporary workspace at the largest mask scale. Only one region
is processed at a time. This trades bounded scratch space for less repeated blur
work. The resulting transaction records exact raster patches for replay.


Pencil preview allocation uses the actual pressure/tilt contact bounds (with room
for rotated corners and antialiasing), instead of the maximum possible shading
radius around every input segment. Eraser allocation and pixel reads likewise use
the kernel's pressure-dependent outer radius. This avoids allocating, reading and
later scanning large rings of untouched tiles. Rotated Pencil coverage is compared
against the prior conservative tile allocation so tighter bounds cannot silently cut off graphite.


## Sustained coverage follow-up (Xiaomi Pad 6)

`reportFastWholeCanvasCoverage` schedules 1,001 full-pressure samples over four
seconds, alternating 32 full-width sweeps across the fitted 5632 square. Tip size
is 180 screen pixels (about 718 document pixels). The eraser starts with all 484
tiles painted. Results check real coverage and memory as well as timing.

The [whole-canvas run](performance/tablet-whole-canvas.txt) demonstrates remaining
failures; passing the instrumentation means the workload completed correctly,
**not** that it met a latency target. Dense custom stamp p95 input queue delay
fell from 1,086 ms to 97 ms after caching its old RenderNode batches in one
bounded display-resolution compositing layer. The cache is rebuilt every 64
batches, capped at 2048 px per edge; full-resolution original nodes are retained
for commit. It still does not achieve one-frame latency.

Jetpack Stamp's textured-quad experiment measured 1,068 ms p95 receipt-to-draw and
12.7 seconds handoff. [Restoring native circles](performance/tablet-stamp-native-circles.txt)
measured 370 ms / 4.9 seconds. [Increasing spacing from .02 to .12](performance/tablet-stamp-spacing-012.txt)
measured 57 ms / 1.7 seconds, with unchanged flow; this changes appearance and is
an experiment, not a silent rewrite of existing tips. These are separate runs,
not controlled thermal statistics. The final implementation retains native solid
geometry and exposes the spacing choice in the creator. The all-tip report contains
the discarded textured-quad experiment for comparison; the isolated native-circle
report describes the final Stamp geometry.

Round Ink performs much better on the same device. Dense particle geometry,
transparent overdraw, growing wet display lists, per-pixel erasing and synchronous
full-resolution completion are distinct costs. The tablet is not simply too weak,
and switching language alone does not remove these costs. Broad Pencil/eraser and
pen-up latency still fail the intended fluid full-canvas experience. Ordered,
bounded asynchronous tile completion and incremental wet coverage are still needed;
there is no all-brush/no-lag acceptance claim or creator certification.

Undo now retains only the opposite state of affected tiles. Current pixels remain
in the layer; undo captures those pixels for redo and swaps the stored state.
Large opposite states use lossless per-tile raw premultiplied-byte compression
when it saves at least half the space; incompressible tiles stay raw. Compression
and decoding currently run synchronously at transaction boundaries, a remaining
latency cost. History counts both stacks dynamically and caps even tiny edits at
256 entries. A first full-canvas stroke no longer retains a duplicate 121 MiB after
image: the tested cases now report NORMAL painting pressure. This does not bound
all transient render/recording allocations or guarantee every incompressible edit
fits the budget.

Brush-library, favorite-tip and studio parameter thumbnails use a fixed blue ink
sample. Color-wheel changes must not recolor/rasterize those preview collections.
The active paint color still applies to actual canvas strokes.

Optional costs to review with the user (not removed automatically): native
parameter endpoint previews in the brush studio; visible-canvas palette
aggregation after drawing; onion-skin compositing; animation thumbnail refreshes;
periodic save attempts when idle. These provide distinct editing/recovery value
and should be profiled or made optional before removing them.
