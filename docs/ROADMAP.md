# Roadmap

## NOW — local drawing workflow

Validate gallery creation/reopening, crash-resistant autosave, image export, configurable finger actions, low-latency stylus authoring, the experimental textured Pencil and particle Airbrush, transparent erasing, sparse per-layer tile commits, bounded undo/redo, non-destructive image layers, and reference-device diagnostics.

## NEXT

1. Recovery journaling/fsync, rename/duplicate gallery actions, and transferable project import/export.
2. Layer renaming, draggable corner handles/non-uniform transforms, blend modes, and tiled image pyramids for extremely large sources. Direct drag/pinch/twist image transforms and non-destructive image erasing are already available.
3. Resize/rotate/flip for selected raster pixels. Lasso/rectangle selection and move are already available for paint; image selections constrain non-destructive mask editing while whole images retain their own transforms.
4. Brush duplication, importable brush assets, and broader dynamics. Compact hardness, pressure, and speed controls plus TipStroke-owned Ink custom families are already available.
5. Animation model, cels, timeline, playback, onion skin, and initial export.
6. Real vector layers and the icon/logo workflow with SVG import/export.

## LATER

Additional brush families/effects, masks and blend modes, richer vector editing, advanced animation/export, Linux, iPad/iOS native input/rendering, and optional Windows/macOS clients.
