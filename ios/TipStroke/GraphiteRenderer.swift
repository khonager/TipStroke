import UIKit
import TipStrokeShared

final class GraphiteRenderer {
    struct Contact {
        let point: CGPoint
        let halfWidth: CGFloat
        let halfHeight: CGFloat
        let angle: CGFloat
        func edge(normal: CGVector) -> CGVector {
            let c = cos(angle), s = sin(angle)
            let nx = normal.dx * c + normal.dy * s, ny = normal.dy * c - normal.dx * s
            let scale = max(0.0001, hypot(nx * halfWidth, ny * halfHeight))
            let x = nx * halfWidth * halfWidth / scale, y = ny * halfHeight * halfHeight / scale
            return CGVector(dx: x * c - y * s, dy: x * s + y * c)
        }
        func cap(_ path: CGMutablePath) {
            let transform = CGAffineTransform(translationX: point.x, y: point.y).rotated(by: angle)
            path.addEllipse(in: CGRect(x: -halfWidth, y: -halfHeight, width: 2 * halfWidth, height: 2 * halfHeight), transform: transform)
        }
    }

    private static let grain = Graphite.shared.grain()
    private var textures: [Int: CGImage] = [:]
    private var tint: UIColor?
    private var previous: Contact?

    func begin() { previous = nil }

    func paint(document: RasterDocument, brush: NativeBrush, point: CGPoint, size: CGFloat, pressure: CGFloat,
               tilt: Float, angle: CGFloat, speed: CGFloat, color: UIColor, opacity: CGFloat) throws -> CGRect {
        let tip = Graphite.shared.contact(brush: brush.preset, size: Float(size), opacity: Float(opacity * color.cgColor.alpha),
            x: Float(point.x), y: Float(point.y), pressure: Float(pressure), opacityPressure: Float(pressure),
            tilt: tilt, speedScale: Float(speed))
        let next = Contact(point: point, halfWidth: CGFloat(tip.width) / 2, halfHeight: CGFloat(tip.height) / 2, angle: angle)
        let path = CGMutablePath()
        next.cap(path)
        if let previous {
            let dx = point.x - previous.point.x, dy = point.y - previous.point.y, length = hypot(dx, dy)
            if length > 0.001 {
                let normal = CGVector(dx: -dy / length, dy: dx / length)
                let a = previous.edge(normal: normal), b = next.edge(normal: normal)
                path.move(to: CGPoint(x: previous.point.x + a.dx, y: previous.point.y + a.dy))
                path.addLine(to: CGPoint(x: point.x + b.dx, y: point.y + b.dy))
                path.addLine(to: CGPoint(x: point.x - b.dx, y: point.y - b.dy))
                path.addLine(to: CGPoint(x: previous.point.x - a.dx, y: previous.point.y - a.dy))
                path.closeSubpath()
            }
        }
        previous = next
        let level = min(3, max(0, Int((3 * brush.tiltResponse(radians: tilt) * brush.pencilGrain).rounded())))
        let texture = try image(level: level, color: color)
        let dirty = path.boundingBoxOfPath.insetBy(dx: -1, dy: -1)
        try document.editTiles(in: dirty) { context in
            context.addPath(path); context.clip()
            context.setAlpha(CGFloat(tip.alpha) / 255)
            context.drawTiledImage(in: CGRect(x: 0, y: 0, width: CGFloat(texture.width) * 0.38, height: CGFloat(texture.height) * 0.38), image: texture)
        }
        return dirty
    }

    private func image(level: Int, color: UIColor) throws -> CGImage {
        if tint != color { textures.removeAll(); tint = color }
        if let image = textures[level] { return image }
        let tip = Self.grain, width = Int(tip.width), height = Int(tip.height)
        let context = try RasterDocument.context(width: width, height: height)
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        color.getRed(&r, green: &g, blue: &b, alpha: &a)
        let bytes = context.data!.assumingMemoryBound(to: UInt8.self), valley = [230, 190, 145, 100][level]
        for i in 0..<(width * height) {
            let alpha = valley + (255 - valley) * Int(tip.coverage(index: Int32(i))) / 255
            bytes[4*i] = UInt8((CGFloat(alpha) * r).rounded().clampedByte)
            bytes[4*i+1] = UInt8((CGFloat(alpha) * g).rounded().clampedByte)
            bytes[4*i+2] = UInt8((CGFloat(alpha) * b).rounded().clampedByte)
            bytes[4*i+3] = UInt8(alpha)
        }
        guard let image = context.makeImage() else { throw DrawingError.message("Could not prepare graphite grain.") }
        textures[level] = image
        return image
    }
}

private extension CGFloat {
    var clampedByte: CGFloat { min(255, max(0, self)) }
}
