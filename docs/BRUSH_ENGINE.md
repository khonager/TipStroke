# Brush engine

`BrushPreset` is versioned serializable domain data; rendering is separate. The v2 schema contains an ID, name, engine type, base size, opacity, hardness, spacing, stabilization, pressure curves, and an optional speed taper. Texture assets, scatter, flow, dynamics, rotation, tip/grain references, and richer blend behavior can be added through schema migration.

Current presets:

- Pencil: an elliptical graphite tip driven by pressure, tilt, barrel orientation, speed, and procedural paper grain.
- Ink: reference latency brush with strong pressure-size response.
- Airbrush: sparse textured particles with pressure response, adjustable scatter/hardness, spacing, speed taper, and within-stroke paint buildup.

Pencil and Airbrush use the experimental custom-brush API pinned to Jetpack Ink `1.1.0-alpha08`; Ink remains the stock pressure pen. The custom texture bitmaps are generated deterministically at runtime, served by TipStroke's immutable `TextureBitmapStore`, shared by `InProgressStrokesView` and `CanvasStrokeRenderer`, and require no packaged brush assets. Completed Ink meshes are rasterized into sparse tiles and discarded. The alpha API is intentionally isolated in `TipStrokeInkBrushes`, so version churn does not enter the domain or persistence layers.

Airbrush stamps a sparse radial particle texture rather than drawing blurred circles. Particle position, rotation, and density vary deterministically along stroke distance. `SelfOverlap.ACCUMULATE` makes a slow pass or held stylus build paint naturally; it no longer produces the tinted circular edge bands caused by repeatedly compositing anti-aliased circle stamps. At high zoom the brush remains in document coordinates: the permanent result has exactly the canvas pixel resolution and is magnified by the camera like every other raster mark.

Pencil uses an elongated rounded tip. Pressure changes size and opacity, tilt broadens and flattens the graphite contact patch, barrel orientation rotates it, fast movement can taper size/opacity, and a tiled modulation texture breaks up the fill like paper tooth. Conservative stroke bounds cover the widest rotated tilt mark so sparse tile edges cannot clip it.

Erasing paint is `BlendBehavior.ERASE` and uses `PorterDuff.CLEAR`, never the background color. Erasing an imported image instead paints into a sparse original-resolution mask; the encoded source asset is unchanged. Eraser hardness controls edge blur.

Brush Studio deliberately exposes only edge hardness where supported, pressure affecting size, pressure affecting opacity, and optional faster-stroke taper. Size and overall opacity remain direct canvas controls. Tuning, size, and opacity persist locally per built-in brush, while the eraser has an independent remembered setup. These local preferences avoid expanding the document format with a large brush library prematurely.

## Experimental dependency

Jetpack Ink 1.1 custom brushes are alpha API. TipStroke accepts that source-compatibility risk during the pre-release period and pins an exact version. Upgrades require device checks for family construction, texture rendering, live/final handoff, and project reopening. Project files contain raster pixels and versioned TipStroke brush settings—not serialized Ink objects—so an Ink API migration cannot invalidate existing artwork.

Eraser gestures still use a cancellable sparse-tile transaction while the pointer is down because Ink does not author a clear-blend stroke over the already-composited lower layers. Only the new segment and intersecting tiles are processed; cancellation restores before snapshots and pen-up records one bounded undo transaction.
