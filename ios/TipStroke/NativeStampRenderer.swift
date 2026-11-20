import UIKit
import TipStrokeShared

/// Native raster adapter; arc-length, scatter, dynamics, source and grain coverage are shared Kotlin.
final class NativeStampRenderer {
    private let brush: NativeBrush
    private let shape: CGImage
    private let grain: BrushTip?
    private var tintCache: [UInt32: CGImage] = [:]
    init(brush: NativeBrush) throws {
        self.brush = brush
        shape = try Self.image(brush.shape(), color: .white)
        grain = brush.hasGrain ? brush.grain() : nil
    }
    static func image(_ tip: BrushTip, color: UIColor) throws -> CGImage {
        let width = Int(tip.width), height = Int(tip.height)
        let context = try RasterDocument.context(width: width, height: height)
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        color.getRed(&r, green: &g, blue: &b, alpha: &a)
        let bytes = context.data!.assumingMemoryBound(to: UInt8.self)
        for index in 0..<(width * height) {
            let alpha = CGFloat(tip.coverage(index: Int32(index)))
            bytes[index * 4] = UInt8((alpha * r).rounded()); bytes[index * 4 + 1] = UInt8((alpha * g).rounded())
            bytes[index * 4 + 2] = UInt8((alpha * b).rounded()); bytes[index * 4 + 3] = UInt8(alpha)
        }
        guard let image = context.makeImage() else { throw DrawingError.message("Could not create the brush tip.") }; return image
    }
    func paint(_ stamps: [BrushStamp], color: UIColor, document: RasterDocument, erasing: Bool, roll: CGFloat = 0) throws -> CGRect {
        var dirty = CGRect.null
        var hue: CGFloat = 0, saturation: CGFloat = 0, brightness: CGFloat = 0, alpha: CGFloat = 0
        color.getHue(&hue, saturation: &saturation, brightness: &brightness, alpha: &alpha)
        let stamps = stamps.filter { $0.alpha > 0 }
        var keys = Set<TileKey>()
        for stamp in stamps {
            let radius = CGFloat(stamp.size) * 0.72 + 1
            let bounds = CGRect(x: CGFloat(stamp.x) - radius, y: CGFloat(stamp.y) - radius, width: radius * 2, height: radius * 2)
            dirty = dirty.union(bounds)
            keys.formUnion(TileKey.intersecting(bounds, width: document.record.width, height: document.record.height))
        }
        // Each affected tile is copied once for the complete batch, never once per dab.
        try document.editTiles(keys: Array(keys), erasing: erasing) { context in
        for stamp in stamps {
            let size = CGFloat(stamp.size), x = CGFloat(stamp.x), y = CGFloat(stamp.y)
            let radius = size * 0.72 + 1
            let bounds = CGRect(x: x - radius, y: y - radius, width: radius * 2, height: radius * 2)
            dirty = dirty.union(bounds)
            let h = (hue + CGFloat(stamp.hue) / 360 + 1).truncatingRemainder(dividingBy: 1)
            let s = max(0, min(1, saturation + CGFloat(stamp.saturation)))
            let tint = UIColor(hue: h, saturation: s, brightness: brightness, alpha: 1)
            var texture: CGImage?
            if let grain {
                let key = UInt32((h * 255).rounded()) << 16 | UInt32((s * 255).rounded()) << 8 | UInt32((brightness * 255).rounded())
                texture = tintCache[key]
                if texture == nil {
                    texture = try Self.image(grain, color: tint)
                    if tintCache.count >= 32 { tintCache.removeAll() }
                    tintCache[key] = texture
                }
            }
            guard context.boundingBoxOfClipPath.intersects(bounds) else { continue }
            context.saveGState()
            do {
                context.setAlpha(CGFloat(stamp.alpha))
                let local = CGAffineTransform(translationX: x, y: y).rotated(by: CGFloat(stamp.angle) * .pi / 180 + roll)
                    .scaledBy(x: size, y: size * CGFloat(self.brush.aspectRatio))
                context.concatenate(local)
                // Flip the image mask into the native top-left document coordinate system.
                context.scaleBy(x: 1, y: -1)
                context.clip(to: CGRect(x: -0.5, y: -0.5, width: 1, height: 1), mask: self.shape)
                context.scaleBy(x: 1, y: -1)
                if let texture {
                    context.concatenate(local.inverted())
                    let side = CGFloat(self.brush.grainScale)
                    let origin = self.brush.grainFollowsStamp ? CGPoint(x: x - size / 2, y: y - size / 2) : .zero
                    if self.brush.id.hasPrefix("manga-") { context.interpolationQuality = .none }
                    context.drawTiledImage(in: CGRect(x: origin.x, y: origin.y, width: side, height: side), image: texture)
                } else {
                    context.setFillColor(tint.cgColor); context.fill(CGRect(x: -0.5, y: -0.5, width: 1, height: 1))
                }
            }
            context.restoreGState()
        }
        }
        return dirty
    }
}
