# Color tools and Procreate reference checklist

Research date: 2026-11-14. Sources below are the current official Procreate for iPad handbook, not Pocket. This is a feature inventory, not a claim of Procreate format or pixel-for-pixel compatibility. Implementation status refers to Android; iOS has not been extended by this change.

## Layer blending

The handbook lists Normal plus these families. Its current version distinguishes Darken from the legacy Shade behavior. [Official blend-mode list](https://help.procreate.com/procreate/handbook/layers/layers-blend).

| Family | Implemented in TipStroke | Not yet implemented |
| --- | --- | --- |
| Normal | Normal | — |
| Darken | Multiply, Darken, Color Burn | Shade, Linear Burn, Darker Color |
| Lighten | Lighten, Screen, Color Dodge, Add | Lighter Color |
| Contrast | Overlay, Soft Light, Hard Light | Vivid Light, Linear Light, Pin Light, Hard Mix |
| Difference | Difference, Exclusion | Subtract, Divide |
| Color | Hue, Saturation, Color, Luminosity | — |

Open a layer's controls and select **Blend** above opacity. Modes apply to raster and image layers, persist through save/load and duplication, and participate in undo/redo, image/animation exports, frame thumbnails, process replay, and composite color sampling. Layer thumbnails show the layer's own content without a backdrop. Opacity retains its existing control.

The 17 implemented modes use Android's native compositor (API 29+). The remaining modes need a dedicated compositor with bounded tile caching and matching export behavior. In particular, Shade is not silently mapped to Darken. No bitmap scans or canvas-sized painting copies are added to pointer handling. Non-normal ink previews use the existing native raster preview path so they are composited with their layer.

## Color selection and filling

Procreate's selection/fill features include Disc, Classic, Harmony, Value and Palettes interfaces; primary and secondary colors; previous-color switching; ten-color history; a detachable Color Companion; eyedropper sampling; ColorDrop and SwatchDrop; fill threshold, Continue Filling/Recolor, and selection color filling. [Color interface](https://help.procreate.com/procreate/handbook/colors/colors-interface).

TipStroke already supplies wheel/classic/value pickers, HSV/RGB/hex input, ten-color history, an eyedropper, a saved color shelf, drawing-derived palettes, and ColorDrop. These are existing capabilities, not full behavioral parity with every Procreate feature above.

**Added:** Harmony tab with Complementary, Split Complementary, Analogous, Triadic and Tetradic schemes. Edit the anchor with the wheel, then tap a suggestion to use it. Picking a suggestion preserves the anchor; save the selected color with the existing saved-palette action. Saturation and brightness follow the anchor. [Harmony reference](https://help.procreate.com/procreate/handbook/colors/colors-harmony).

## Palette management

Procreate has compact/card views, named swatches, create/replace/delete/reorder swatches, a palette library, active/default palettes, create/rename/duplicate/delete/reorder palettes, palette creation from camera/photos/files, and palette import/export/sharing. [Palette reference](https://help.procreate.com/procreate/handbook/colors/colors-palettes).

TipStroke currently has one bounded local saved shelf (64 colors), recent colors and palettes extracted from the drawing. Named multiple palettes, card views, camera extraction and palette file interchange remain unimplemented.

## Color adjustments and profiles

Procreate's color adjustments include Hue/Saturation/Brightness, Color Balance with shadows/midtones/highlights, Curves with histogram and RGB channels, and editable Gradient Maps. These layer adjustments remain unimplemented here; HSV picker controls select brush colors rather than transform painted pixels. [Color adjustments](https://help.procreate.com/procreate/handbook/adjustments/adjustments-color).

Procreate also supports sRGB, Display P3, CMYK and custom profiles. TipStroke remains 8-bit premultiplied sRGB; wide-gamut and print-profile workflows require explicit format/rendering work. [Color profiles overview](https://help.procreate.com/procreate/handbook/colors).

## Related painting and effects

Color-related Brush Studio controls include stamp/stroke color jitter, pressure/tilt color dynamics, secondary-color variation, wet mixing, rendering blend mode, luminance blending and burnt edges. TipStroke's existing brush engines are documented in [BRUSH_ENGINE.md](BRUSH_ENGINE.md); this change does not implement all of these brush controls. [Brush Studio settings](https://help.procreate.com/procreate/handbook/brushes/brush-studio-settings).

Adjacent Procreate effects include Gaussian/Motion/Perspective Blur, Noise, Sharpen, Bloom, Glitch, Halftone, Chromatic Aberration, Liquify and Clone. They are listed for completeness and are not implemented by this color change. [Adjustments overview](https://help.procreate.com/procreate/handbook/adjustments).
