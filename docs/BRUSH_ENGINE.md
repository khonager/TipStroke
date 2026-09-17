# Brush engine

`BrushPreset` is versioned serializable domain data; rendering is separate. The v2 schema contains an ID, name, engine type, base size, opacity, hardness, spacing, stabilization, pressure curves, and an optional speed taper. Texture assets, scatter, flow, dynamics, rotation, tip/grain references, and richer blend behavior can be added through schema migration.

Current presets:

- Pencil: a narrow graphite tip driven by pressure, tilt, barrel orientation, speed, bounded edge variation, and procedural clustered paper tooth.
- Ink: a smooth solid nib with pressure response and independent entry/exit tapers, tuned toward a pointed Baskerville-style line.
- Airbrush: ten nested translucent native coats approximating a Gaussian falloff, with pressure-sized geometry and hardness-controlled feathering.

Pencil, Ink, and Airbrush use the experimental custom-brush API pinned to Jetpack Ink `1.1.0-alpha08`. The Pencil texture bitmap is generated deterministically at runtime and served by TipStroke's immutable `TextureBitmapStore`, shared by `InProgressStrokesView` and `CanvasStrokeRenderer`; no packaged or third-party brush asset is copied. Completed Ink meshes are rasterized into sparse tiles and discarded. The alpha API is intentionally isolated in `TipStrokeInkBrushes`, so version churn does not enter the domain or persistence layers.

Airbrush uses continuous round geometry with nested coats rather than a sparse spray texture. The outer coats contribute very little alpha and progressively smaller coats contribute more, producing a smooth center-to-edge falloff close to the blurred eraser profile. The innermost coat is opaque, so a 100% opacity pass fully covers underlying color at its core while retaining a feathered translucent edge. Hardness expands the inner coats toward the outer radius until the mark becomes a hard disk. Every coat remains a native wet Ink mesh, and the permanent result has exactly the canvas pixel resolution like every other raster mark. Each coat's constant alpha is encoded in a tiny uniform tiling texture because alpha08's `DISCARD` path renderer supports tiling textures but ignores color functions and opacity-targeting behaviors; pressure still changes the airbrush diameter, while overall opacity remains the reliable flow control.

The soft profile is capped at ten coats because Jetpack Ink `1.1.0-alpha08` rejects larger `BrushFamily` instances at native construction time. The connected-device brush test constructs every production family so this limit is checked before shipping.

Pencil starts with a compact round contact patch. Pressure changes size and opacity; tilt broadens and flattens it into side shading; barrel orientation rotates that patch; and fast movement can taper size/opacity. Two deterministic, smoothly correlated noise signals add at most 6% size variation and 1.2% lateral offset, breaking the mechanically smooth silhouette without generating detached marks. Its fixed-scale paper texture combines smooth multi-octave value noise, subtle fibers, and clustered valleys. Texture coverage is binary: graphite pixels are fully opaque at 100% brush opacity and the valleys expose untouched paper, rather than making all deposited graphite translucent. The clusters read as graphite tooth without independent-pixel television static. Conservative stroke bounds cover the widest rotated tilt mark so sparse tile edges cannot clip it.

Erasing paint is `BlendBehavior.ERASE` and uses `PorterDuff.CLEAR`, never the background color. Erasing an imported image instead paints into a sparse original-resolution mask; the encoded source asset is unchanged. Eraser hardness controls edge blur.

Brush Studio deliberately exposes only edge hardness where supported, pressure affecting size, pressure affecting opacity, and optional faster-stroke taper. Size and overall opacity remain direct canvas controls. Tuning, size, and opacity persist locally per built-in brush, while the eraser has an independent remembered setup. These local preferences avoid expanding the document format with a large brush library prematurely.

## Experimental dependency

Jetpack Ink 1.1 custom brushes are alpha API. TipStroke accepts that source-compatibility risk during the pre-release period and pins an exact version. Upgrades require device checks for family construction, texture rendering, live/final handoff, and project reopening. Project files contain raster pixels and versioned TipStroke brush settings—not serialized Ink objects—so an Ink API migration cannot invalidate existing artwork.

`SelfOverlap.ACCUMULATE` and particle-gap custom coats currently render in `InProgressStrokesView` but produce an empty finalized image through `CanvasStrokeRenderer` in `1.1.0-alpha08`. TipStroke therefore uses `SelfOverlap.DISCARD` and continuous nested coats for Airbrush. The connected-device visual regression test exists specifically to prevent an attractive wet preview from silently disappearing at commit again.

Eraser gestures still use a cancellable sparse-tile transaction while the pointer is down because Ink does not author a clear-blend stroke over the already-composited lower layers. Only the new segment and intersecting tiles are processed; cancellation restores before snapshots and pen-up records one bounded undo transaction.
