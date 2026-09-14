# Brush engine

`BrushPreset` is versioned serializable domain data; rendering is separate. The v1 schema contains an ID, name, engine type, base size, opacity, hardness, spacing, stabilization, and pressure curves. Texture assets, scatter, flow, dynamics, rotation, tip/grain references, and richer blend behavior can be added through schema migration.

Current presets:

- Pencil: smaller, lower opacity, natural pressure response.
- Ink: reference latency brush with strong pressure-size response.
- Airbrush: large low-opacity soft committed stroke.

Jetpack Ink stable stock brushes author wet marks. Completed input samples are rasterized into tiles; Ink geometry is discarded. Erasing is `BlendBehavior.ERASE` and uses `PorterDuff.CLEAR`, never the background color.

## Known visual limitation

Ink 1.0 stable has no stock soft-airbrush/clear-blend authoring brush. The Airbrush wet preview is a marker-like approximation before the committed tile applies softness. The eraser wet preview uses white because v0 has one transparent paint layer over a white canvas; true multi-layer eraser preview must use a replaceable custom wet renderer before layers are added.
