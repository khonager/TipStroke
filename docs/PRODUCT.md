# Product

TipStroke should feel like opening a sketchbook: the first meaningful action is putting down a mark. Android drawing quality and stylus latency outrank cross-platform reuse and visible feature count.

The product is raster-first, finite-canvas, local-first, account-free, ad-free, telemetry-free, and useful offline. The UI stays contextual and gesture-led rather than growing into a desktop graphics suite.

Drawing mode has no branded or full-width header. Slightly translucent controls are split into small edge clusters, keeping the top center and canvas center clear; expanded tools such as the color picker remain side-attached in landscape.

The long-term differentiator is a hybrid document that can contain true raster and vector layers plus animation metadata. That future does not change the current painting rule: artwork committed to a raster layer is pixels, not an indefinitely replayed stroke list.

## Current slice

- Local gallery with named custom-size documents, generated thumbnails, drag ordering, named stacks, deletion, and reopening.
- Versioned local project directories containing manifests, sparse paint tiles, and copied original image assets.
- Multiple paint/image layers with ordering, visibility, opacity, direct non-destructive image move/resize/rotation, and original-resolution image masks for erasing without flattening.
- PNG, JPEG, and WebP export through the layer compositor.
- Pencil, Ink, and Airbrush presets; transparent eraser; precise size/opacity controls; an always-visible switcher for the drawing's most-used colors; and a compact Brush Studio for hardness, pressure response, and optional speed taper.
- Native stylus authoring and tile commits; configurable finger navigation, smudge, color pick, undo/redo, rotation lock, and diagnostics.
- Freehand lasso and rectangular selection on paint or image layers. The selection constrains drawing and erasing, and selected paint pixels can be moved as one undoable operation. Image-layer selections constrain the source-resolution erase mask; whole images retain their separate non-destructive transform.

Vectors, animation, selected-pixel resize/rotation/warp, filters, layer renaming, and blend modes remain intentionally absent.
