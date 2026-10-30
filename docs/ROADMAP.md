# Roadmap

## NOW — local drawing workflow

Validate gallery creation/reopening, drag ordering and stacks, crash-resistant autosave, image export, configurable finger and stylus-button actions, low-latency stylus authoring, the experimental textured Pencil and pigment Airbrush, transparent erasing, sparse per-layer tile commits, bounded undo/redo, non-destructive image layers, and reference-device diagnostics.

## NEXT

1. Recovery journaling/fsync, rename/duplicate gallery actions, and transferable project import/export.
2. Draggable corner/perspective handles, blend modes, higher-resolution/adaptive warp meshes, and tiled image pyramids for extremely large sources. Layer renaming, drag/pinch/twist, independent-axis stretch, local Push/Bulge/Pinch, optional snapping, and non-destructive image erasing are available. Photo compositing is manual; automatic subject blending and true 3D sphere projection are not implemented.
3. Selected raster pixels now support resize/rotate/flip with live preview, Apply/Cancel, linked proportions, optional smoothing, and grouped undo. Lasso/rectangle/ellipse selection and move are available for paint; image selections constrain non-destructive mask editing while whole images retain their own transforms.
4. Brush duplication, importable brush assets, and broader dynamics. Compact hardness, pressure, and speed controls plus TipStroke-owned Ink custom families are already available.
5. Refine the initial animation workflow: disk-backed loading and eviction of edited cels for very large projects, device profiling of live recording and MP4 encoding, richer cel manipulation, and more compact iconography after usability review. Worker-decoded cels and copy-on-write frame duplication are available. The separate frame/cel model, shared backgrounds, holds, onion skins, playback modes, live motion/line recording, and MP4/GIF/PNG-sequence export are in the first Android slice.
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
