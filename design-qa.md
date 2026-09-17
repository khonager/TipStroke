# TipStroke color chooser design QA

- Source visual truth: user-provided Procreate Disc reference (835 × 1620 px) and Classic reference (305 × 660 px) attached to the request. These are interaction/anatomy references rather than a request to copy Procreate's full app chrome.
- Implementation screenshots:
  - `app/build/qa/tipstroke-color-picker.png`
  - `app/build/qa/tipstroke-color-picker-classic.png`
  - `app/build/qa/tipstroke-color-picker-portrait.png`
  - `app/build/qa/tipstroke-color-picker-live-selection.png`
  - `app/build/qa/tipstroke-color-picker-dismissed.png`
  - `app/build/qa/tipstroke-color-picker-populated.png`
- Viewport: 1280 × 800 dp Android landscape at xhdpi; screenshots are 2560 × 1600 px at 2× density.
- State: color chooser open over the TipStroke canvas; HSV Wheel, HSV Sliders, portrait, live-selection, dismissed, empty-palette, populated-palette, and populated-history states reviewed.

## Full-view comparison evidence

The implementation keeps TipStroke's dark panel while carrying over the reference anatomy: current/previous swatches, distinct HSV wheel and slider modes, a hue ring with an inner saturation/value field, a 2D field with three component sliders, palette swatches, and color history. The portrait implementation is compact and centered instead of occupying the full height. Color changes apply live, so no action-button footer remains.

## Focused region comparison evidence

- HSV Wheel: the outer sweep ring, separate hue handle, full white-to-hue-to-black circular inner field, and independent inner handle are all visible and correctly separated. The smooth square-to-disc mapping exposes the complete HSV saturation/value range.
- HSV Sliders: the implementation screenshot shows the 2D saturation/value field followed by hue, saturation, and brightness sliders in the same hierarchy as the reference.
- Drawing palette: the populated screenshot shows three equal-weight swatches by default with adjacent minus/plus controls; the empty state explains when colors will appear.
- History: the populated screenshot shows ten compact, selectable drawn-with swatches and a clear action.
- Typography, spacing, and tokens: TipStroke's existing sans-serif weights, dark surface colors, 1 dp borders, rounded shapes, and coral action color are retained. No reference typography or unrelated Procreate chrome was copied.
- Image/asset fidelity: there are no raster image assets in this control. Functional color fields are rendered from native Compose color gradients; standard Material add/remove icons are used.
- Copy/content: labels explicitly describe the visible-composite palette and drawn-color history.

## Findings

No actionable P0, P1, or P2 issues remain.

## Comparison history

1. Initial Disc render: the numeric count between the palette decrement/increment buttons was squeezed by Material minimum touch sizing (P2). Fix: removed the redundant inline count, kept the count in the descriptive subtitle, and replaced the controls with evenly sized 36 dp icon surfaces.
2. Post-fix Disc, Classic, and populated-state renders: controls are aligned, legible, and unclipped; no further P0/P1/P2 findings.
3. User feedback found a stale-state interaction bug, incomplete brightness range, gesture spillover from the inner field to the hue ring, and excessive portrait height (P1/P2). Fixes: live state reads inside pointer input, gesture-region locking at touch-down, a full-range smooth HSV disc transform, removal of the action footer, compact portrait sizing, and outside-tap dismissal.
4. Post-fix portrait, HSV Wheel, HSV Sliders, live-selection, and dismissed renders show no remaining P0/P1/P2 findings.

## Interaction and runtime checks

- HSV Wheel and HSV Sliders tabs switch rendered modes.
- Wheel, 2D field, hue, saturation, and brightness controls update the active drawing color without closing.
- An inner-field gesture stays assigned to the inner field even when dragged to or beyond its edge.
- Tapping outside dismisses the chooser; tapping inside does not.
- Palette swatches and history swatches select a color.
- Minus/plus requests 1–8 freshly clustered visible colors, defaulting to 3.
- Cancel, Use color, and Clear history are wired.
- Full unit, lint, and debug assembly checks passed with no framework error overlay applicable to this native Android surface.

## Follow-up polish

- P3: verify haptics and stylus ergonomics on a physical tablet; Robolectric visual tests cannot assess tactile feedback.

final result: passed
