import UIKit
import TipStrokeShared

/// A stroke adds coverage only when the new segment raises the existing soft mask.
final class StrokeCoverage {
    /// Transform document offsets into a circular footprint of the same major radius.
    struct Nib {
        let xx: CGFloat
        let xy: CGFloat
        let yy: CGFloat
        init(aspect: CGFloat, angle: CGFloat) {
            let c = cos(angle), s = sin(angle)
            let stretch = 1 / max(0.01, aspect) - 1
            xx = 1 + s * s * stretch
            xy = -s * c * stretch
            yy = 1 + c * c * stretch
        }
    }
    private var lastNib: Nib?
    private var tiles: [TileKey: NSMutableData] = [:]
    private var lastRadius: CGFloat?
    private var lastAlpha: CGFloat?
    func reset() { tiles.removeAll(); lastRadius = nil; lastAlpha = nil; lastNib = nil }

    func paintTip(document: RasterDocument, brush: NativeBrush, from start: CGPoint, to end: CGPoint,
                  size: CGFloat, pressure: CGFloat, tilt: Float, roll: CGFloat, speed: CGFloat,
                  opacity: CGFloat, color: UIColor, erasing: Bool) throws -> CGRect {
        let response = CGFloat(brush.sizeResponse(pressure: Float(pressure)))
        let tiltScale = 1 + CGFloat(brush.sweepTilt) * CGFloat(max(0, min(1, tilt / 1.2)))
        let radius = max(0.25, size * response * tiltScale * speed / 2)
        let alpha = opacity * CGFloat(brush.opacityResponse(pressure: Float(pressure)) * brush.sweepFlow)
        let nib = brush.sweepAspect == 1 ? nil : Nib(aspect: CGFloat(brush.sweepAspect),
                                                     angle: CGFloat(brush.sweepRotation) * .pi / 180 + roll)
        return try paint(document: document, from: start, to: end, radius: radius, alpha: alpha,
                         hardness: CGFloat(brush.hardness), color: color, nib: nib, erasing: erasing)
    }

    func paint(document: RasterDocument, from start: CGPoint, to end: CGPoint, radius: CGFloat,
               alpha: CGFloat, hardness: CGFloat, color: UIColor, nib: Nib? = nil,
               erasing: Bool = false, texture: ((Int, Int) -> CGFloat)? = nil) throws -> CGRect {
        let priorRadius = lastRadius ?? radius, priorAlpha = lastAlpha ?? alpha
        let priorNib = lastNib ?? nib
        lastNib = nib
        lastRadius = radius; lastAlpha = alpha
        let outer = max(priorRadius, radius) + 1
        let area = CGRect(x: min(start.x, end.x), y: min(start.y, end.y),
            width: abs(start.x - end.x), height: abs(start.y - end.y)).insetBy(dx: -outer, dy: -outer).integral.intersection(document.bounds)
        let keys = TileKey.intersecting(area, width: document.record.width, height: document.record.height).filter { key in
            // Reject tiles away from a diagonal before allocating scratch coverage.
            let rect = key.rect.insetBy(dx: -outer, dy: -outer)
            let dx = end.x - start.x, dy = end.y - start.y
            let separation = abs(dx * (rect.midY - start.y) - dy * (rect.midX - start.x))
            return separation <= (abs(dx) * rect.height + abs(dy) * rect.width) / 2
        }
        guard tiles.count + keys.filter({ tiles[$0] == nil }).count <= RasterDocument.historyBudget / (RasterDocument.tileBytes * 2)
        else { throw DrawingError.message("This stroke exceeds the undo budget.") }
        let dx = end.x - start.x, dy = end.y - start.y, length2 = dx * dx + dy * dy
        var red: CGFloat = 0, green: CGFloat = 0, blue: CGFloat = 0, opacity: CGFloat = 1
        color.getRed(&red, green: &green, blue: &blue, alpha: &opacity)
        // Project onto the segment in nib coordinates so a sparse, slanted
        // graphite stroke sweeps the full side of the pencil between contacts.
        let axisX = ((priorNib?.xx ?? 1) + (nib?.xx ?? 1)) / 2
        let axisXY = ((priorNib?.xy ?? 0) + (nib?.xy ?? 0)) / 2
        let axisY = ((priorNib?.yy ?? 1) + (nib?.yy ?? 1)) / 2
        let sx = axisX * dx + axisXY * dy, sy = axisXY * dx + axisY * dy
        let metricX = axisX * sx + axisXY * sy, metricY = axisXY * sx + axisY * sy
        let metricLength = nib == nil ? length2 : sx * sx + sy * sy
        var paintedKeys: [TileKey] = []
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
                    let t = metricLength > 0 ? max(0, min(1, (vx * metricX + vy * metricY) / metricLength)) : 1
                    let r = priorRadius + t * (radius - priorRadius)
                    let ex = vx - t * dx, ey = vy - t * dy
                    let distance: CGFloat
                    let pixelWidth: CGFloat
                    if let nib, let priorNib {
                        let xx = priorNib.xx + t * (nib.xx - priorNib.xx)
                        let xy = priorNib.xy + t * (nib.xy - priorNib.xy)
                        let yy = priorNib.yy + t * (nib.yy - priorNib.yy)
                        let nx = xx * ex + xy * ey, ny = xy * ex + yy * ey
                        distance = hypot(nx, ny)
                        // Feather by one document pixel across the thin axis too.
                        pixelWidth = distance > 0 ? hypot(xx * nx + xy * ny, xy * nx + yy * ny) / distance : 1
                    } else {
                        distance = hypot(ex, ey)
                        pixelWidth = 1
                    }
                    let feather = max(pixelWidth, r * (1 - hardness))
                    let u = max(0, min(1, (r + 0.5 * pixelWidth - distance) / feather))
                    let coverage = u * u * (3 - 2 * u) * (priorAlpha + t * (alpha - priorAlpha)) * opacity * (texture?(x, y) ?? 1)
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
            if changed, let image = context.makeImage() { paintedKeys.append(key); patches.append((box, image)) }
        }
        if !patches.isEmpty {
            try document.editTiles(keys: paintedKeys, affectedArea: area, erasing: erasing) { context in
                for (rect, image) in patches where context.boundingBoxOfClipPath.intersects(rect) {
                    RasterDocument.draw(image, in: context, rect: rect)
                }
            }
        }
        return area
    }
}
