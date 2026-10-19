# Roadmap

## NOW — local drawing workflow

Validate gallery creation/reopening, drag ordering and stacks, crash-resistant autosave, image export, configurable finger and stylus-button actions, low-latency stylus authoring, the experimental textured Pencil and pigment Airbrush, transparent erasing, sparse per-layer tile commits, bounded undo/redo, non-destructive image layers, and reference-device diagnostics.

## NEXT

1. Recovery journaling/fsync, rename/duplicate gallery actions, and transferable project import/export.
2. Layer renaming, draggable corner handles/non-uniform transforms, blend modes, and tiled image pyramids for extremely large sources. Direct drag/pinch/twist image transforms and non-destructive image erasing are already available.
3. Resize/rotate/flip for selected raster pixels. Lasso/rectangle selection and move are already available for paint; image selections constrain non-destructive mask editing while whole images retain their own transforms.
4. Brush duplication, importable brush assets, and broader dynamics. Compact hardness, pressure, and speed controls plus TipStroke-owned Ink custom families are already available.
5. Refine the initial animation workflow: lazy loading for large cel collections, device profiling of live recording and MP4 encoding, richer cel manipulation, and more compact iconography after usability review. The separate frame/cel model, shared backgrounds, holds, onion skins, playback modes, live motion/line recording, and MP4/GIF/PNG-sequence export are in the first Android slice.
6. Real vector layers and the icon/logo workflow with SVG import/export.

## LATER

Additional brush families/effects, masks and blend modes, richer vector editing, advanced animation/export, Linux, iPad/iOS native input/rendering, and optional Windows/macOS clients.

## Drawing-process replay preview

Implemented: automatic bounded command recording for raster drawing, versioned/frozen brush replay, exact tile fallbacks for destructive edits, atomic sidecar persistence, native preview/scrubbing, GIF/MP4 export, and compatibility fixtures. First-version exclusions: image layers, animated documents, resize continuation, retrospective filtering, per-sample playback, recording reset/pause controls, and accelerated backward seeking. Before expanding support, measure real Pencil/Ink/Airbrush sessions on the tablet and verify native rendering/export after dependency updates.
