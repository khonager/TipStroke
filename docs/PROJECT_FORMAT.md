# Native project format

The planned format is a versioned ZIP-like package, not a serialized Kotlin object graph:

```text
manifest.json
thumbnail.webp
layers/<stable-layer-id>/tiles/<x>_<y>.png
brushes/<brush-id>.json       # only referenced custom assets
vectors/...                   # only when real vector layers exist
animation/...                 # only when real animation data exists
```

`manifest.json` begins with `schemaVersion`, stable document/layer IDs, canvas dimensions, color-space metadata, ordered typed layer records, timestamps, and referenced asset versions. Unknown optional fields should be ignored; unsupported required schema versions should produce a clear read-only/migration error.

Autosave should write dirty tiles to a temporary sibling package or journal on a background dispatcher, fsync critical metadata, then atomically replace the prior committed revision. It should snapshot immutable tile revisions under a short lock and never encode or write from the input thread. Recovery chooses the newest complete revision and cleans abandoned temporary data.

No empty future directories are created in v0. Persistence is the next milestone; PNG export should follow the same tile compositor and can be delivered with it.
