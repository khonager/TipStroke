# Android brush creator and sharing

Open a canvas, choose **Adjust**, then **Brush library · Create and share**.
The library lives in a dialog so permanent canvas controls stay minimal. A selected
custom brush occupies its engine's existing dock slot. **Create brush** starts with
the current canvas brush settings; **Featured → Customize** starts from a built-in.

The creator edits a draft: name, optional creator attribution and sharing terms,
engine, size, opacity, pressure-to-size/opacity minima and response exponents,
speed taper, and engine-specific settings. Airbrush exposes edge hardness. Pencil
exposes point size, grain, tilt mode, tilt sensitivity or shading angles, size boost,
and side opacity. The native raster stroke preview uses the selected size and
updates as settings change. It is a simulated sample, not a freehand test pad.
Spacing and stabilization remain reserved preset fields: the current renderers do
not consume them, so the creator does not offer misleading sliders for them.

**Save and use** atomically saves the draft and selects its exact dynamics. Cancel
leaves the saved preset unchanged. My brushes supports search, edit, duplicate,
use, and confirmed deletion. Deleting a preset does not change painted artwork or
the currently loaded tool. Canvas adjustments are temporary per-preset tuning;
to preserve a new variation, create another brush from the adjusted tool.

**Share** opens Android's share sheet with a read-only content URI. **Export** saves
a `.tipbrush` JSON file using Android's document picker. The recipient saves the
attachment and chooses **Import** in the brush library. Imports always receive a
new local ID, including repeated imports; they never overwrite another preset.
Attribution and sharing terms are user-entered metadata, not verified identities.

## Format and storage

A brush package uses `format: "tipstroke-brush"`, package `version: 1`, and brush
`schemaVersion: 2`. It includes the preset ID/name/engine, all current BrushPreset
numeric and enum fields, author, and description. Unsupported versions, unknown
enums, invalid numeric ranges, and missing fields fail without changing the library.
This first package version supports pressure curves ending at 1, matching Android's
canvas controls. Files are capped at 64 KiB before parsing; names at 60 characters,
author at 80, description at 1,000. There are no embedded textures, URLs to fetch,
or executable brush definitions.

`files/brush-library.json` stores at most 200 presets and 4 MiB. AtomicFile protects
replacement; IO and package parsing run off the input thread. A failed library load
preserves the file and disables mutations. Share files live only in the dedicated
`cache/shared-brushes/` provider directory, bounded to 20 recent attachments.
The document schema and permanent sparse raster tile format do not change.

## Community store boundary

Featured is the bundled Pencil, Ink, and Airbrush catalog, not a popularity chart.
There is no public publishing, remote browsing, download count, ranking, account,
or commerce backend. A community store needs a separate decision to allow optional
network access under AGENTS.md, plus a service/deployment choice, publisher identity,
content rights, moderation, and ranking rules. No popularity data is fabricated.
The portable package is the foundation for that future service. iOS has not gained
this editor or package-import UI in this change.
