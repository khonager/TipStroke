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
animation/cels/<stable-layer-id>/<stable-frame-id>/<x>_<y>.png
```

The app-private `drawings/` root also contains `gallery.json`, a separate versioned index of top-level drawing IDs and named stacks. It owns only presentation order and membership. Drawing directories keep their stable IDs and locations when reordered or stacked, so gallery organization cannot corrupt artwork and can be rebuilt by reconciling the index with existing manifests. Empty or one-item stacks dissolve automatically.

`manifest.json` begins with `schemaVersion`, stable document/layer IDs, canvas dimensions, ordered typed layer records, transforms, opacity, visibility, and modification time. It also stores a gallery-preview orientation as zero through three quarter-turns; this is snapped from the editor camera on save and never rotates the underlying artwork. Raster layer records also carry optional color-usage weights for the editor's most-used-color shortcuts; the raster tiles remain the artwork source of truth. Image layers may carry sparse mask tiles in original image coordinates; absence means the source is fully visible. The transient editor selection is not persisted. Version 2 adds image masks and still reads version 1 projects. Projects without usage metadata infer an approximate palette from sparse raster tiles on the project I/O thread while loading. Unsupported schema versions produce a clear load error. Color-space metadata remains migration work. Portable archives retain the project files and their unknown fields verbatim, except for the document ID assigned on import.

Layer records may also carry an optional `groupId`. Layers with the same ID form a contiguous organizational group in painter order. Group membership does not affect pixels or opacity, so older version-3 readers can safely ignore this field. Ungrouped layers omit it. Moving a layer out of a group clears its ID; groups with one member dissolve.

Version 3 adds an optional `animation` object to the manifest and still reads versions 1 and 2. It stores ordered stable frame IDs, per-frame exposure counts, selected frame, FPS, playback mode, onion settings, shared background layer IDs, and image transforms keyed by layer and frame ID. An animated raster layer's sparse cel tiles live under `animation/cels/`; its ordinary `layers/.../tiles/` directory remains empty. Shared raster layers keep their ordinary tile path and are never copied into every frame. Image source bytes and erase masks remain shared by the image layer, while frame transforms are metadata. Deleting a frame removes its cel references when the next atomic save replaces the project directory.

The manifest also stores `backgroundColor` as an ARGB integer and `backgroundOpacity` from 0 to 1. These document-level values render beneath all layers and default to opaque white when absent in older projects. Transparent export omits this background; regular PNG and WebP export retains its opacity, while JPEG composites it over white.

Autosave writes to a temporary sibling directory on the project executor and atomically replaces the prior committed revision, retaining a backup until replacement succeeds. It snapshots immutable allocated tiles and never encodes or writes from the input path. Autosave runs periodically, on app backgrounding, and when returning to the gallery.

If Android terminates the process between moving the prior project to `.<id>.backup` and installing its replacement, the next gallery listing or project open restores that committed backup when the target is absent. Recovery and the directory swap share a lock so recovery cannot race a live swap. Invalid saved PNG tiles fail loading instead of silently becoming missing paint. Neither an unreadable project nor a restored missing document is replaced with a blank drawing.

Image layer records refer to an original asset by stable ID and store transforms and masks separately. Saving copies the original encoded bytes into `assets/`; it never saves a canvas-sized resample as the layer source. Mask tiles contain only alpha-removal coverage and remain sparse at source resolution.

No empty future directories are created. PNG/JPEG/WebP export uses the same back-to-front layer snapshot and finite canvas bounds as thumbnails. The gallery directories are app-private; portable `.tipstroke` packages provide transfer and import.

## Optional process recording

`process.tsr` is an optional sidecar inside the atomic project directory. Existing schema-3 artwork stays compatible; recording has its own `TipStroke-process-1` header, canvas dimensions, optional stopped reason, action count, and length-prefixed independently gzip-compressed actions. Actions contain layer/background metadata, explicit v1 brush/style/sample commands, or tile-coordinate/PNG replacement patches (zero length deletes a tile). Action 3 embeds a source-resolution PNG for an imported image; image metadata includes dimensions and transform, while `<layer-id>:mask` patches retain non-destructive erasing. Action 4 marks a recovery discontinuity and clears replay state before a current-artwork baseline. Older apps reject these unknown actions during replay; authoritative project artwork remains compatible. Native Ink commands retain the finalized input batch and `androidx-ink-1.1.0-alpha08` identifier. No video frames or temporary export videos are stored in the project. The recording snapshot is captured at the same logical point as the raster snapshot; subsequent edits cannot leak into a queued earlier save. Disposable replay-cache tiles live outside the project and do not change this format.

Saving snapshots the immutable compressed action list on the same serial executor after earlier captures, alongside the artwork snapshot. Gallery duplication copies the sidecar; deleting the project removes it. The compressed payload budget is 32 MiB with at most 30,000 actions; header/length overhead is additional. Corrupt/unknown sidecars are preserved when saving otherwise valid artwork, so they cannot silently erase history. Exported videos/GIFs are separate user-selected destination files.

## Portable TipStroke packages (version 1)

A `.tipstroke` file is a ZIP archive with `tipstroke.json` (`format: "TipStroke"`, `version: 1`, and a `drawings` array of document IDs), `gallery.json`, and complete project directories at `drawings/<id>/`. One format supports a single drawing, selected drawings, or the whole gallery. It preserves sparse layers, source image bytes, masks, animation cels/timing, background settings, thumbnails, and optional process recordings. Gallery metadata includes only selected drawings and preserves their stack names and order. Undo history and editor camera state are not portable project data.

Gallery **Import** opens Android's document picker. Imports are staged privately, checked for supported versions, safe paths, duplicate IDs, required assets and valid tile dimensions, then installed as new document IDs. Layer and frame IDs are retained. Existing drawings are never replaced; repeated imports make separate copies. Failed imports remove staged/new directories. Packages have limits of 2 GiB expanded bytes, 100,000 entries, and 10,000 drawings. Future unsupported package/project versions are rejected. Import does not interpret media ZIP exports as editable projects.

Gallery **Export / select drawings** supports individual or multiple selections and **Select whole gallery**. Each drawing's menu also provides Export. TipStroke export preserves editability; PNG/JPEG/WebP flatten the selected frame, and MP4/GIF/PNG-sequence require animation in every selected drawing. Multiple media exports are bundled in an ordinary ZIP with numbered filenames. Media render sequentially, holding one drawing at a time; package copying and rendering use the serial project I/O executor. The editor also offers editable TipStroke export after committing its current state.

Version 4 adds optional `clipSourceId` to raster and image layer records. It names
another stable layer ID and changes compositing without modifying either layer's
pixels. Absence means unclipped. Versions 1–3 still load as before; invalid links
are removed when constructing runtime layers. Version 4 prevents older readers
from silently displaying clipped artwork incorrectly. Portable import/export and
process-recording layer metadata preserve clipping IDs.

Version 5 adds optional `stretchX`, `stretchY` (positive finite floats, default 1) and `warp` to image transforms, including animation cels. `warp` is a row-major flat array of 578 finite normalized coordinates: X/Y pairs for the 17×17 vertices of a 16×16 mesh. Absent mesh means identity. Original assets and source-coordinate sparse masks are unchanged. Readers accept versions 1–5; older readers reject version 5 instead of silently displaying an undeformed image. Process metadata includes the same optional fields; recordings stop with an explicit status if metadata would exceed the legacy UTF record size.
