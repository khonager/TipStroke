# Roadmap

## NOW — local drawing workflow

Validate gallery creation/reopening, drag ordering and stacks, crash-resistant autosave, image export, configurable finger and stylus-button actions, low-latency stylus authoring, the experimental textured Pencil and pigment Airbrush, transparent erasing, sparse per-layer tile commits, bounded undo/redo, non-destructive image layers, and reference-device diagnostics.

## NEXT

1. Recovery journaling/fsync, rename/duplicate gallery actions, and transferable project import/export.
2. Draggable corner/perspective handles, blend modes, higher-resolution/adaptive warp meshes, and tiled image pyramids for extremely large sources. Layer renaming, drag/pinch/twist, independent-axis stretch, local Push/Bulge/Pinch, optional snapping, and non-destructive image erasing are available. Photo compositing is manual; automatic subject blending and true 3D sphere projection are not implemented.
3. Selected raster pixels now support resize/rotate/flip with live preview, Apply/Cancel, linked proportions, optional smoothing, and grouped undo. Lasso/rectangle/ellipse selection and move are available for paint; image selections constrain non-destructive mask editing while whole images retain their own transforms.
4. Android shape-and-grain brush authoring, imported/drawn editable sources, native freehand testing, dynamics, portable texture packages, a dedicated library, and favorites limited by available dock space are available; see [Brush library](BRUSH_LIBRARY.md). Next: richer rendering such as wet mixing and dual brushes. Public publishing/popularity requires an explicit optional-network policy decision and a backend; it is not implemented.
5. Refine the initial animation workflow: physical-device profiling of long projects, live recording and MP4 encoding, richer cel manipulation, and more compact iconography after usability review. Disk-backed edited cels, worker decoding, whole-project resolution reduction and copy-on-write frame duplication are available. The separate frame/cel model, shared backgrounds, holds, onion skins, playback modes, live motion/line recording, and MP4/GIF/PNG-sequence export are in the first Android slice.
6. Real vector layers and the icon/logo workflow with SVG import/export.

## LATER

Additional brush families/effects, masks and blend modes, richer vector editing, advanced animation/export, Linux, iPad/iOS native input/rendering, and optional Windows/macOS clients.

## Drawing-process replay preview

Implemented: automatic bounded command recording for raster drawing, versioned/frozen brush replay, exact tile fallbacks for destructive edits, atomic sidecar persistence, native preview/scrubbing, GIF/MP4 export, and compatibility fixtures. Imported images, transforms, source-mask edits, and recovery from legacy image cutoffs are supported. Animation-frame editing and recovery from legacy animation cutoffs are supported. Remaining exclusions: resize continuation, retrospective filtering, per-sample playback, recording reset/pause controls, and accelerated backward seeking. Before expanding support, measure real Pencil/Ink/Airbrush sessions on the tablet and verify native rendering/export after dependency updates.

## iPhone and iPad port

The initial native iOS source target and Codemagic workflows are implemented:
local library, sparse raster painting, basic Pencil/finger input, editable paint
layers, bounded stroke undo, autosave and PNG sharing. Apple SDK builds, simulator
checks and device validation must pass before distributing the first release.

Remaining parity work is explicit: lossless Android project import/export;
Jetpack-Ink-like brush quality and advanced Pencil input; gallery thumbnails and
organization; image import/editing, selections/fill/smudge; animation and process
recording. Do not silently flatten or discard unsupported Android document data.
The existing Android production surface remains native and independent.

### Source-backed media canvases (Android)

Implemented source-sized image/GIF/video canvases, local encoded source assets,
native video playback at 1×, free scrubbing, manually authored sparse drawing
intervals, independent drawing FPS/holds, project transfer, and smooth-video or
drawing-FPS merged silent exports. No video thumbnail churn during playback.

Follow-ups: physical-device playback/thermal profiling; audio passthrough;
sequential decode and GPU composition for faster export; exact variable-frame-rate
output timestamps; draggable drawing ranges and drawing-only
thumbnails; movable media layers; native iOS parity. GIF remains a CPU-decoded,
bounded preview. These follow-ups are not part of the current implementation.


### Longer Android projects and resolution reduction

Implemented disk-backed inactive animation cels, asynchronous spill of edited
frames with COW/revision protection, and 50%/25% whole-project raster reduction.
Cold histories are evicted; source image/video assets retain their quality.
The conservative simultaneous-layer limit remains resolution/device-dependent;
animation drawings use timeline cels rather than one layer per drawing.
Three minutes at 12 FPS is 2,160 drawings, within the existing 10,000-drawing limit.
This does not establish full-episode device performance. Remaining work includes
physical-tablet long-session profiling, audio support, faster sequential video
export, and scene/shot organization for larger productions.


### Single-track video edits (Android)

Implemented mixed photo/GIF/video tracks, split/trim/delete/duplicate/reorder,
photo duration, exact clip-relative seeking, transform/opacity keyframes with
three interpolation modes, overlapping crossfades, bounded timeline undo/redo,
versioned local persistence/transfer, and shared preview/export composition.
Drawing overlays remain attached to global timeline time. Audio, image morphing,
clip-linked drawing ranges, sequential/GPU preview performance, and native
iOS/desktop parity remain future work.

### Video drawing coverage direction (selected)

- Selected and implemented: a coverage strip beneath video thumbnails, with colored painted
  spans and hatched undrawn gaps, plus Next undrawn navigation. Derive coverage
  from actual cel content and hold duration, not just whether a frame entry exists.
- Alternative: thumbnail grid with drawn/blank badges; useful for frame-by-frame
  review but consumes more screen area and thumbnail decoding work.
- Combined: strip for navigation with an optional grid expanded on demand.

Keep timestamps as secondary details instead of the primary frame labels. The
coverage implementation handles erasing a cel back to empty and
shared backgrounds without marking an entire video as drawn.
