# Native project format

The planned format is a versioned ZIP-like package, not a serialized Kotlin object graph:

```text
manifest.json
thumbnail.webp
layers/<stable-layer-id>/tiles/<x>_<y>.png
assets/<content-hash>.<source-extension>  # original imported image bytes
brushes/<brush-id>.json       # only referenced custom assets
vectors/...                   # only when real vector layers exist
animation/...                 # only when real animation data exists
```

`manifest.json` begins with `schemaVersion`, stable document/layer IDs, canvas dimensions, color-space metadata, ordered typed layer records, timestamps, and referenced asset versions. Unknown optional fields should be ignored; unsupported required schema versions should produce a clear read-only/migration error.

Autosave should write dirty tiles to a temporary sibling package or journal on a background dispatcher, fsync critical metadata, then atomically replace the prior committed revision. It should snapshot immutable tile revisions under a short lock and never encode or write from the input thread. Recovery chooses the newest complete revision and cleans abandoned temporary data.

Image layer records refer to an original asset by stable ID and store transforms separately. Saving must copy the original encoded bytes into `assets/`; it must never save a canvas-sized resample as the layer source. The current in-memory milestone uses a persistable Android document URI until package persistence lands.

No empty future directories are created in v0. Persistence is the next milestone; PNG export should follow the same layer compositor and can be delivered with it.
