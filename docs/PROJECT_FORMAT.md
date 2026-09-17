# Native project format

The current local representation is a versioned directory package, not a serialized Kotlin object graph:

```text
manifest.json
thumbnail.png
layers/<stable-layer-id>/tiles/<x>_<y>.png
layers/<image-layer-id>/mask/<x>_<y>.png
assets/<content-hash>.<source-extension>  # original imported image bytes
brushes/<brush-id>.json       # only referenced custom assets
vectors/...                   # only when real vector layers exist
animation/...                 # only when real animation data exists
```

The app-private `drawings/` root also contains `gallery.json`, a separate versioned index of top-level drawing IDs and named stacks. It owns only presentation order and membership. Drawing directories keep their stable IDs and locations when reordered or stacked, so gallery organization cannot corrupt artwork and can be rebuilt by reconciling the index with existing manifests. Empty or one-item stacks dissolve automatically.

`manifest.json` begins with `schemaVersion`, stable document/layer IDs, canvas dimensions, ordered typed layer records, transforms, opacity, visibility, and modification time. It also stores a gallery-preview orientation as zero through three quarter-turns; this is snapped from the editor camera on save and never rotates the underlying artwork. Raster layer records also carry optional color-usage weights for the editor's most-used-color shortcuts; the raster tiles remain the artwork source of truth. Image layers may carry sparse mask tiles in original image coordinates; absence means the source is fully visible. The transient editor selection is not persisted. Version 2 adds image masks and still reads version 1 projects. Projects without usage metadata infer an approximate palette from sparse raster tiles on the project I/O thread while loading. Unsupported schema versions produce a clear load error. Color-space metadata and forward-compatible unknown-field handling remain migration work before a distributable native file format.

Autosave writes to a temporary sibling directory on the project executor and atomically replaces the prior committed revision, retaining a backup until replacement succeeds. It snapshots immutable allocated tiles and never encodes or writes from the input path. Autosave runs periodically, on app backgrounding, and when returning to the gallery.

Image layer records refer to an original asset by stable ID and store transforms and masks separately. Saving copies the original encoded bytes into `assets/`; it never saves a canvas-sized resample as the layer source. Mask tiles contain only alpha-removal coverage and remain sparse at source resolution.

No empty future directories are created. PNG/JPEG/WebP export uses the same back-to-front layer snapshot and finite canvas bounds as thumbnails. Packaging a project for transfer/import is still future work; the gallery directories are app-private.
