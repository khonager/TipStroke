# Native project format

The current local representation is a versioned directory package, not a serialized Kotlin object graph:

```text
manifest.json
thumbnail.png
layers/<stable-layer-id>/tiles/<x>_<y>.png
assets/<content-hash>.<source-extension>  # original imported image bytes
brushes/<brush-id>.json       # only referenced custom assets
vectors/...                   # only when real vector layers exist
animation/...                 # only when real animation data exists
```

`manifest.json` begins with `schemaVersion`, stable document/layer IDs, canvas dimensions, ordered typed layer records, selection, transforms, opacity, visibility, and modification time. Raster layer records also carry optional color-usage weights for the editor's most-used-color shortcuts; the raster tiles remain the artwork source of truth. For backward compatibility, projects without usage metadata infer an approximate palette from sparse raster tiles on the project I/O thread while loading. Unsupported schema versions produce a clear load error. Color-space metadata and forward-compatible unknown-field handling remain migration work before a distributable native file format.

Autosave writes to a temporary sibling directory on the project executor and atomically replaces the prior committed revision, retaining a backup until replacement succeeds. It snapshots immutable allocated tiles and never encodes or writes from the input path. Autosave runs periodically, on app backgrounding, and when returning to the gallery.

Image layer records refer to an original asset by stable ID and store transforms separately. Saving must copy the original encoded bytes into `assets/`; it must never save a canvas-sized resample as the layer source. The current in-memory milestone uses a persistable Android document URI until package persistence lands.

No empty future directories are created. PNG/JPEG/WebP export uses the same back-to-front layer snapshot and finite canvas bounds as thumbnails. Packaging a project for transfer/import is still future work; the gallery directories are app-private.
