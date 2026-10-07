# Product

TipStroke should feel like opening a sketchbook: the first meaningful action is putting down a mark. Android drawing quality and stylus latency outrank cross-platform reuse and visible feature count.

The product is raster-first, finite-canvas, local-first, account-free, ad-free, telemetry-free, and useful offline. The UI stays contextual and gesture-led rather than growing into a desktop graphics suite.

Drawing actions include **Resize canvas**. Each edge accepts a signed pixel amount: positive values add white paper and negative values crop. Existing raster pixels retain their exact size and colors; added space is transparent layer content over the white canvas. Cropped pixels outside the new bounds are discarded, and raster stroke undo history is cleared. Placed images and animation cels follow the same integer canvas offset without changing image scale or frame timing. Canvas sides remain between 16 and 8192 px.

Drawing mode has no branded or full-width header. Slightly translucent controls are split into small edge clusters, keeping the top center and canvas center clear; expanded tools such as the color picker remain side-attached in landscape.

Tablet ergonomics remain authoritative. Compact phone windows adapt those same tools into a short command strip and bottom dock that move out of the way only while an active stroke approaches them, then return at stroke end; this is a layout adaptation, not a separate drawing engine or reduced document format. Touch drawing is an explicit mode for fingers and passive capacitive pens, with two-finger navigation retained.

The long-term differentiator is a hybrid document that can contain true raster and vector layers plus animation metadata. That future does not change the current painting rule: artwork committed to a raster layer is pixels, not an indefinitely replayed stroke list.

## Current slice

- Local gallery with named custom-size documents, generated thumbnails, drag ordering, named stacks, duplication, deletion, and reopening. New drawings offer screen-size and device-budgeted maximum square presets, Full HD and 4K, manga page and panel sizes, icon sizes, and 16–128 px pixel-art sizes. Custom sizes from 16 to 8192 px per side can be saved as named presets; any preset can be the default for future drawings. The initial default is the maximum square, which estimates one fully painted 256 px-tile layer using at most half the device's app heap class, capped at 8192 px per side; sparse or additional layers can change actual memory use.
- Versioned local project directories containing manifests, sparse paint tiles, and copied original image assets.
- Multiple named paint/image layers with content previews, ordering, duplication, visibility, opacity, direct non-destructive image move/resize/rotation, and original-resolution image masks for erasing without flattening.
- PNG, JPEG, and WebP export through the layer compositor.
- Pencil, Ink, and Airbrush presets; transparent eraser; precise size/opacity controls; Wheel, Sliders, and Values color pickers with synchronized HEX/RGB/HSV entry; an adjustable palette clustered from the visible composite; a ten-color drawn-with history; and a compact Brush Studio for hardness, pressure response, and optional speed taper.
- Native stylus authoring and tile commits; configurable finger navigation, smudge, color pick, undo/redo, rotation lock, and diagnostics.
- Freehand lasso and rectangular selection on paint or image layers. Swiping layers right adds them to a multi-layer selection, allowing selected paint pixels and selected image layers to move together. The selection also constrains drawing and erasing. Image-layer selections constrain the source-resolution erase mask; whole images retain their separate non-destructive transform.

The first Android animation workflow now has time slots separate from layers, shared backgrounds, holds, frame duplication, onion skins, loop/ping-pong/once playback, live image-motion and line-reveal recording, and MP4/GIF/PNG-sequence export. The timeline is a compact contextual strip. Vectors, selected-pixel resize/rotation/warp, filters, and blend modes remain absent.
