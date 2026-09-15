# Brush engine

`BrushPreset` is versioned serializable domain data; rendering is separate. The v2 schema contains an ID, name, engine type, base size, opacity, hardness, spacing, stabilization, pressure curves, and an optional speed taper. Texture assets, scatter, flow, dynamics, rotation, tip/grain references, and richer blend behavior can be added through schema migration.

Current presets:

- Pencil: smaller, lower opacity, natural pressure response.
- Ink: reference latency brush with strong pressure-size response.
- Airbrush: large low-opacity soft stroke, incrementally rasterized with the same native painter while wet and at commit.

Jetpack Ink stable stock brushes author wet marks. Completed input samples are rasterized into tiles; Ink geometry is discarded. Erasing paint is `BlendBehavior.ERASE` and uses `PorterDuff.CLEAR`, never the background color. Erasing an imported image instead paints into a sparse original-resolution mask; the encoded source asset is unchanged. Hardness controls the edge blur of both eraser paths.

Brush Studio deliberately exposes only edge hardness, pressure affecting size, pressure affecting opacity, and optional faster-stroke taper. Size and overall opacity remain direct canvas controls. Adjustments persist locally per built-in brush rather than expanding the document format with a large brush library prematurely.

## Known visual limitation

Ink 1.0 stable has no stock soft-airbrush or clear-blend authoring brush. Airbrush and eraser therefore use a cancellable sparse-tile transaction while the pointer is down. Only the new segment and its intersecting tiles are processed; cancellation restores the before snapshots, and pen-up records one bounded undo transaction. This makes erasing reveal the real lower-layer composite immediately and avoids repeatedly rasterizing a long Airbrush stroke.
