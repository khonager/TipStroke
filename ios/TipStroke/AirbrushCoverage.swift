import UIKit

/// A stroke adds coverage only when the new segment raises the existing soft mask.
final class AirbrushCoverage {
    private var tiles: [TileKey: NSMutableData] = [:]
    private var lastRadius: CGFloat?
    private var lastAlpha: CGFloat?
    func reset() { tiles.removeAll(); lastRadius = nil; lastAlpha = nil }

    func paint(document: RasterDocument, from start: CGPoint, to end: CGPoint, radius: CGFloat,
               alpha: CGFloat, hardness: CGFloat, color: UIColor) throws -> CGRect {
        let priorRadius = lastRadius ?? radius, priorAlpha = lastAlpha ?? alpha
        lastRadius = radius; lastAlpha = alpha
        let outer = max(priorRadius, radius) + 1
        let area = CGRect(x: min(start.x, end.x), y: min(start.y, end.y),
            width: abs(start.x - end.x), height: abs(start.y - end.y)).insetBy(dx: -outer, dy: -outer).integral.intersection(document.bounds)
        let keys = TileKey.intersecting(area, width: document.record.width, height: document.record.height)
        guard tiles.count + keys.filter({ tiles[$0] == nil }).count <= RasterDocument.historyBudget / (RasterDocument.tileBytes * 2)
        else { throw DrawingError.message("This airbrush stroke exceeds the undo budget.") }
        let dx = end.x - start.x, dy = end.y - start.y, length2 = dx * dx + dy * dy
        var red: CGFloat = 0, green: CGFloat = 0, blue: CGFloat = 0, opacity: CGFloat = 1
        color.getRed(&red, green: &green, blue: &blue, alpha: &opacity)
        var patches: [(CGRect, CGImage)] = []
        for key in keys {
            let box = key.rect.intersection(area)
            let width = Int(box.width), height = Int(box.height)
            let mask = tiles[key] ?? NSMutableData(length: 256 * 256)!
            tiles[key] = mask
            let previous = mask.mutableBytes.assumingMemoryBound(to: UInt8.self)
            let context = try RasterDocument.context(width: width, height: height)
            let output = context.data!.assumingMemoryBound(to: UInt8.self)
            var changed = false
            for row in 0..<height {
                let y = Int(box.minY) + row
                for col in 0..<width {
                    let x = Int(box.minX) + col
                    let vx = CGFloat(x) + 0.5 - start.x, vy = CGFloat(y) + 0.5 - start.y
                    let t = length2 > 0 ? max(0, min(1, (vx * dx + vy * dy) / length2)) : 1
                    let r = priorRadius + t * (radius - priorRadius)
                    let distance = hypot(vx - t * dx, vy - t * dy)
                    let feather = max(1, r * (1 - hardness))
                    let u = max(0, min(1, (r + 0.5 - distance) / feather))
                    let coverage = u * u * (3 - 2 * u) * (priorAlpha + t * (alpha - priorAlpha)) * opacity
                    let new = UInt8(min(255, max(0, (255 * coverage).rounded())))
                    let idx = (y % 256) * 256 + x % 256
                    let old = previous[idx]
                    guard new > old else { continue }
                    changed = true
                    let increment = (CGFloat(new - old) * 255 / CGFloat(255 - old)).rounded()
                    let i = 4 * (row * width + col)
                    output[i] = UInt8(min(255, max(0, increment * red)))
                    output[i+1] = UInt8(min(255, max(0, increment * green)))
                    output[i+2] = UInt8(min(255, max(0, increment * blue)))
                    output[i+3] = UInt8(increment)
                    previous[idx] = new
                }
            }
            if changed, let image = context.makeImage() { patches.append((box, image)) }
        }
        if !patches.isEmpty {
            try document.editTiles(keys: keys, erasing: false) { context in
                for (rect, image) in patches where context.boundingBoxOfClipPath.intersects(rect) {
                    RasterDocument.draw(image, in: context, rect: rect)
                }
            }
        }
        return area
    }
}
