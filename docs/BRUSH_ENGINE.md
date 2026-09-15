# Brush engine

`BrushPreset` is versioned serializable domain data; rendering is separate. The v1 schema contains an ID, name, engine type, base size, opacity, hardness, spacing, stabilization, and pressure curves. Texture assets, scatter, flow, dynamics, rotation, tip/grain references, and richer blend behavior can be added through schema migration.

Current presets:

- Pencil: smaller, lower opacity, natural pressure response.
- Ink: reference latency brush with strong pressure-size response.
- Airbrush: large low-opacity soft stroke, with the same native painter used while wet and at commit.

Jetpack Ink stable stock brushes author wet marks. Completed input samples are rasterized into tiles; Ink geometry is discarded. Erasing is `BlendBehavior.ERASE` and uses `PorterDuff.CLEAR`, never the background color.

## Known visual limitation

Ink 1.0 stable has no stock soft-airbrush authoring brush, so Airbrush uses a lightweight native overlay and the shared custom raster painter. The wet preview now has the same softness, pressure sizing, and opacity as the committed pixels. The eraser wet preview remains a white approximation; a true multi-layer clear preview needs temporary compositing of the layers beneath the selected paint layer.
