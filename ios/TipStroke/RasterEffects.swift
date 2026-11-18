import UIKit
import TipStrokeShared

struct EffectSettings {
    var kind = 0
    var radius = 8
    var angle: Float = 0
    var centerX: Float = 0.5
    var centerY: Float = 0.5
    var threshold: Float = 0.7
    var intensity: Float = 1
}

extension RasterDocument {
    // TilePixels reads premultiplied RGBA; core effects take straight ARGB.
    static func argb(_ rgba: UInt32) -> Int32 {
        let a = rgba & 255
        guard a > 0 else { return 0 }
        let r = min(255, ((rgba >> 24) & 255) * 255 / a)
        let g = min(255, ((rgba >> 16) & 255) * 255 / a)
        let b = min(255, ((rgba >> 8) & 255) * 255 / a)
        return Int32(bitPattern: a << 24 | r << 16 | g << 8 | b)
    }
    static func writeARGB(_ value: Int32, to bytes: UnsafeMutablePointer<UInt8>, index: Int) {
        let c = UInt32(bitPattern: value), a = c >> 24
        bytes[index * 4] = UInt8(((c >> 16) & 255) * a / 255)
        bytes[index * 4 + 1] = UInt8(((c >> 8) & 255) * a / 255)
        bytes[index * 4 + 2] = UInt8((c & 255) * a / 255)
        bytes[index * 4 + 3] = UInt8(a)
    }
    static func effectTiles(_ snapshot: DocumentSnapshot, layer: UUID, selection: CanvasSelection?, settings: EffectSettings) throws -> [TileKey: CGImage] {
        let source = snapshot.pixels[layer] ?? [:], reader = TilePixels(source)
        let width = snapshot.record.width, height = snapshot.record.height
        let bounds = CGRect(x: 0, y: 0, width: width, height: height)
        let halo = settings.radius + 2, side = 256 + halo * 2
        let keys = Set(source.keys.flatMap { TileKey.intersecting($0.rect.insetBy(dx: -CGFloat(halo), dy: -CGFloat(halo)), width: width, height: height) })
            .filter { selection == nil || selection!.inverted || selection!.path.boundingBoxOfPath.intersects($0.rect) }
        guard keys.count <= 64 else { throw DrawingError.message("This effect exceeds the undo budget. Select a smaller area.") }
        var output: [TileKey: CGImage] = [:]
        let engine = NativeRasterEffects()
        for key in keys {
            let input = KotlinIntArray(size: Int32(side * side))
            let ox = key.x * 256 - halo, oy = key.y * 256 - halo
            for y in 0..<side { for x in 0..<side {
                let value = x + ox < width && y + oy < height ? try reader.rgba(x + ox, y + oy) : 0
                input.set(index: Int32(y * side + x), value: argb(value))
            } }
            let result = try engine.apply(pixels: input, side: Int32(side), effect: Int32(settings.kind), radius: Int32(settings.radius), angle: settings.angle,
                centerX: settings.centerX, centerY: settings.centerY, threshold: settings.threshold, intensity: settings.intensity,
                originX: Int32(ox), originY: Int32(oy), documentWidth: Int32(width), documentHeight: Int32(height))
            let context = try Self.context(width: 256, height: 256), bytes = context.data!.assumingMemoryBound(to: UInt8.self)
            for y in 0..<256 { for x in 0..<256 {
                let px = key.x * 256 + x, py = key.y * 256 + y
                let inside = bounds.contains(CGPoint(x: px, y: py)) && selection?.contains(CGPoint(x: Double(px) + 0.5, y: Double(py) + 0.5), in: bounds) != false
                let value = inside ? result.get(index: Int32((y + halo) * side + x + halo)) : argb(try reader.rgba(px, py))
                writeARGB(value, to: bytes, index: y * 256 + x)
            } }
            guard let image = context.makeImage() else { throw DrawingError.message("Could not prepare effect tiles.") }
            if source[key] != nil || (0..<(256 * 256)).contains(where: { bytes[$0 * 4 + 3] != 0 }) { output[key] = image }
        }
        return output
    }
}
