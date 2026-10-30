# Brush engine

`BrushPreset` is versioned serializable domain data; rendering is separate. The v2 schema contains an ID, name, engine type, base size, opacity, hardness, spacing, stabilization, pressure curves, and an optional speed taper. Texture assets, scatter, flow, dynamics, rotation, tip/grain references, and richer blend behavior can be added through schema migration.

Current presets:

- Pencil: a narrow graphite tip driven by pressure, tilt, barrel orientation, speed, bounded edge variation, and procedural clustered paper tooth.
- Ink: a smooth solid nib with clearly visible pressure response and independent entry/exit tapers, tuned toward a pointed Baskerville-style line.
- Airbrush: one native blurred raster mask, with pressure-controlled size and opacity plus hardness-controlled feathering.

Pixel tools are a separate transient rendering mode over the same sparse raster layers. Pixel draws a connected freehand path on integer document cells; Line joins the first and latest contact with a straight grid line; Shade draws a stable 50% checkerboard dither along a freehand path. Their sizes start at 1 px on 16–128 px canvases and are integer square footprints. The native wet preview and tile commit share the same painter, with antialiasing and mask blur disabled. Switching back to a regular brush restores that brush's saved tuning. Pixel tool choice does not alter the project format.

Pencil and Ink use the experimental custom-brush API pinned to Jetpack Ink `1.1.0-alpha08`. Ink meshes are rasterized into sparse tiles and discarded. Because the experimental Ink opacity and tilt targets are not visually dependable in the wet Pencil preview on all devices, Pencil feeds an effectively transparent Jetpack Ink authoring stroke while TipStroke's native sparse-tile renderer applies pressure, tilt, and barrel orientation immediately. Those exact live pixels become the permanent raster at pen-up in one bounded undo transaction; Pencil is never replaced by a differently smoothed or textured mesh after the stylus lifts. The alpha API is intentionally isolated in `TipStrokeInkBrushes`, so version churn does not enter the domain or persistence layers. Airbrush uses the native raster path described below because Ink cannot finalize its required single stamped falloff in the pinned release.

Airbrush does not use multiple Ink coats. It follows the same native Android blur technique as the soft eraser. With opacity pressure disabled, all sampled widths are joined into one filled stroke silhouette before one `BlurMaskFilter` pass. With opacity pressure enabled, variable-alpha segments first form an unblurred coverage mask using source replacement. The combined mask is blurred once and tinted only when composited. Blurring each segment separately can leave colored ridges around overlapping caps. The pressure mask uses document-aligned 256 px scratch tiles with a blur halo, keeping temporary allocation independent of stroke length and matching preview and committed tile edges. Both paths produce one continuous center-to-edge falloff like Procreate's Soft Airbrush. At 100% opacity the body covers underlying color while the edge fades continuously; hardness reduces the blur radius toward a hard edge. The native wet preview redraws only the current in-memory stroke; pen-up rasterizes only intersecting sparse tiles and creates one bounded undo transaction. The permanent result retains exact canvas-pixel resolution like every other raster mark.

The Linux desktop preview uses the same one-stroke principle within its smaller AWT renderer: while drawing, it retains one temporary path and paints nested continuous bands rather than individual radial stamps. On pen-up, that exact path is rasterized into only the intersecting sparse tiles. This prevents low-event-rate mice and trackpads from exposing a row of circular input samples; it does not add desktop pressure, tilt, layers, or project-package support.

Pencil treats the size control as shading capacity rather than the diameter of its upright point: the round HB point uses only 18% of the selected size, while the side contact can grow from that point to 10× on its long axis and 6.5× on its short axis before the user-selected tilted-size boost is applied. Its default shading-switch response keeps ordinary point drawing stable, then smoothly changes into side shading between user-selected start and full-shading angles. The optional gradual response retains the continuous aggressive ease-out curve from a small dead zone to a selected full-shading angle. Both are bounded to the observed Android tilt range of 0.08–1.2 radians (about 5–69°). Tilt rapidly decouples contact width from pressure—the exposed side of a real pencil remains broad under a light hand—while deposition falls to 62%, leaving pressure to control darkness more than shade width. Barrel orientation rotates the anisotropic contact, and fast movement can taper size/opacity. Upright lines use subtle, fine paper tooth so light strokes remain connected; tilted contacts use fine, continuous-tone graphite grain without large mottled patches or binary white flecks. Density and edge variation remain deliberately small so texture does not deform the silhouette. Current and historical motion samples retain their own tilt and orientation, so fast shading strokes do not collapse to the final pen angle. The native renderer filters duplicate positions, overlaps adjacent contact quads by less than one pixel, and fills their corner wedges instead of exposing one oval per input sample. New segments carry prior tangent context but never redraw an already-finished segment, so Android event batching cannot leave corrected ghost edges, corner gaps, or dark bands. Source replacement happens on an isolated sparse scratch layer; pen-up composites that scratch layer into the selected layer once as one bounded undo transaction. Only new segments and intersecting sparse tiles are processed, and the grouped layer preview keeps wet and dry pixels visually identical. Conservative stroke bounds cover the widest rotated tilt mark so sparse tile edges cannot clip it.

Erasing paint is `BlendBehavior.ERASE` and uses `PorterDuff.DST_OUT`, never the background color. Opacity and pressure scale the removed coverage instead of being ignored by `CLEAR`. Bounds include the soft-edge halo regardless of the selected brush engine. Erasing an imported image instead paints into a sparse original-resolution mask; the encoded source asset is unchanged. Eraser hardness controls edge blur.

Animation stroke recording builds cumulative sparse raster cels from the captured input samples. Each cel contains the line up to that frame's assigned progress; the frame tiles remain the permanent source of truth after recording. The reveal pass uses the native `StrokeCanvasPainter` path rather than retaining Ink objects in the project. Inspect a recorded Ink stroke on a device for any visible difference between the wet authoring stroke and the finished frame sequence.

Brush Studio exposes edge hardness where supported, pressure affecting size, pressure affecting opacity, and optional faster-stroke taper. Pencil additionally exposes point size, a choice of shading-switch or gradual tilt behavior, angle controls for that behavior, tilted-size boost, side-contact opacity, and graphite grain. Switch mode directly controls the angles where shading starts and becomes full; gradual mode directly controls the full-shading angle. Tilted-size boost ranges from 0% (the upright point and tilted contact have the same dimensions) through subtle intermediate expansion to 400% of the built-in side-contact expansion. Combined with point size, this allows a near-one-pixel upright point to grow into a very broad shading contact. An always-visible simulated stroke runs from 0° to 69°, marks the selected response angles, and updates with every Pencil setting. Its separate hover calibration square reads `MotionEvent.AXIS_TILT` directly without requiring screen contact, previews the active response, displays the reported angle, and maps the held angle to the switch threshold or gradual full-shading angle. Disabling a pressure response, setting tilted-size boost to 0%, or setting Pencil side opacity to 100% omits the corresponding no-op Jetpack Ink behavior node because the Ink API rejects constant target ranges. Size and overall opacity remain direct canvas controls. Tuning, calibration, size, and opacity persist locally per built-in brush, while the eraser has an independent remembered setup. These local preferences avoid expanding the document format with a large brush library prematurely.

Opacity uses a separate pressure channel from size. Ink treats a rapid collapse toward zero across the final 120 ms as lift-off noise and reuses the last stable opacity pressure; raw pressure remains unchanged so size can still taper naturally. Sustained low-pressure drawing before pen-up remains unchanged. Ink finalization rebuilds the temporary mesh without its experimental opacity modifier, then applies the corrected bounded raster pressure mask. Pencil applies pressure directly in its live raster renderer and stabilizes only the terminal sample, so pen-up never retroactively changes pixels that were already shown.

## Experimental dependency

Jetpack Ink 1.1 custom brushes are alpha API. TipStroke accepts that source-compatibility risk during the pre-release period and pins an exact version. Upgrades require device checks for family construction, texture rendering, live/final handoff, and project reopening. Project files contain raster pixels and versioned TipStroke brush settings—not serialized Ink objects—so an Ink API migration cannot invalidate existing artwork.

`SelfOverlap.ACCUMULATE`, particle-gap custom coats, and `StampingTexture` airbrush coats currently render in `InProgressStrokesView` but produce an empty finalized image through `CanvasStrokeRenderer` in `1.1.0-alpha08`. Airbrush therefore uses the native blurred raster path for both its live result and permanent tiles. The connected-device visual regression test exercises that production path so an attractive wet preview cannot silently disappear at commit.

Eraser gestures still use a cancellable sparse-tile transaction while the pointer is down because Ink does not author a clear-blend stroke over the already-composited lower layers. Only the new segment and intersecting tiles are processed. Each reached tile retains a byte-per-pixel maximum coverage mask for the current eraser gesture; coverage is applied against its touch-down snapshot, preventing overlapping sample caps from repeatedly erasing the feathered edge. Lifting and starting another gesture can erase further. Cancellation restores before snapshots and pen-up records one bounded undo transaction; both release the temporary coverage masks.

## Replay compatibility

Process recording v1 stores complete brush parameters rather than mutable preset IDs alone. `ReplayPainterV1`, `ReplayBrushMathV1`, and `ReplayInkBrushesV1` are frozen compatibility implementations, including texture generation and pressure/tilt math. Never update them to match a new brush look. Introduce a new renderer version and keep v1 dispatch for earlier actions. Any new production brush algorithm must update recording dispatch as part of the same change; production-vs-replay pixel tests guard that seam.

Native Ink is pinned to 1.1.0-alpha08. A dependency guard test requires explicit compatibility review before upgrading it; a string version tag alone cannot preserve an old native implementation. Run the connected native round-trip test and retain compatibility or migrate recordings before removing native support. Checked-in `recording/v1.tsr` and `v1.png` fixtures guard older raster recordings independently of current presets. Do not regenerate these fixtures to accommodate a brush change.

### Pencil ends and closed shapes

Continuous Pencil strokes terminate in the same textured contact ribbon used
along the stroke, without separate oval end stamps. A stationary tap uses a
textured, bevelled nib footprint. Once that tap becomes a moving stroke, its
provisional sparse dot tiles are replaced once by the ribbon; normal subsequent
samples continue to render incrementally. Tap texture uses the same tint/grain
composition as the ribbon.

Fitted closed outlines repeat their first point exactly. Closed Pencil ribbons
join the final tangent to the initial tangent instead of drawing terminal caps;
closed Ink/erase outlines use a closed path rather than overlapping endpoint
stamps. Fitted closed shapes use average contact pressure/tilt and disable speed
taper to avoid a visible pressure discontinuity at the seam. No new persistent
shape types or project schema fields are required.

The existing version-one process replay painter predates the joined Pencil
ribbon and exact closed outlines. New recordings store the affected sparse
raster tiles for those strokes, and for the corrected Airbrush and eraser rendering, so replay matches the committed artwork exactly;
older recordings keep using their original version-one drawing commands.

## iOS compatibility brushes

The first native iOS renderer uses pressure-scaled, round Core Graphics segments
for Ink and a lower-opacity Pencil variant; it does not emulate Jetpack Ink's
textured pencil family. Eraser uses destination-out blending on the active layer.
Size is in document pixels; brush settings are frozen at touch-down. Segment
overlap can accumulate opacity, so translucent marks differ from Android brush
semantics. Airbrush, tilt shading, custom assets and Brush Studio are not ported.
Only reached 256 px tiles are changed, with a cancellable before-image transaction.
