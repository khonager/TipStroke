import UIKit
import TipStrokeShared

/// Transient selection in document coordinates. It never becomes painting data.
struct CanvasSelection {
    var path: CGPath
    var inverted = false
    func contains(_ point: CGPoint, in bounds: CGRect) -> Bool {
        bounds.contains(point) && (path.contains(point, using: .evenOdd) != inverted)
    }
    func clip(_ context: CGContext, bounds: CGRect) {
        if inverted { context.addRect(bounds) }
        context.addPath(path); context.clip(using: .evenOdd)
    }
}

/// Tile-local read cache for command processing; no document-sized image or pixel array.
final class TilePixels {
    private let images: [TileKey: CGImage]
    private var cache: [TileKey: CGContext] = [:]
    private var order: [TileKey] = []
    init(_ images: [TileKey: CGImage]) { self.images = images }
    func rgba(_ x: Int, _ y: Int) throws -> UInt32 {
        guard x >= 0, y >= 0 else { return 0 }
        let key = TileKey(x: x / 256, y: y / 256)
        guard let image = images[key] else { return 0 }
        let context: CGContext
        if let existing = cache[key] { context = existing }
        else {
            context = try RasterDocument.context(width: 256, height: 256)
            RasterDocument.draw(image, in: context, rect: CGRect(x: 0, y: 0, width: 256, height: 256))
            if order.count == 16 { cache.removeValue(forKey: order.removeFirst()) }
            order.append(key); cache[key] = context
        }
        let p = context.data!.assumingMemoryBound(to: UInt8.self).advanced(by: ((y % 256) * 256 + x % 256) * 4)
        return UInt32(p[0]) << 24 | UInt32(p[1]) << 16 | UInt32(p[2]) << 8 | UInt32(p[3])
    }
}

extension RasterDocument {
    /// Runs on a worker-owned snapshot. Visited bits and output contexts are sparse and bounded.
    static func floodFill(_ snapshot: DocumentSnapshot, layerID: UUID, point: CGPoint,
                          color: UIColor, tolerance: Int, selection: CanvasSelection?) throws -> [TileKey: CGImage] {
        let width = snapshot.record.width, height = snapshot.record.height
        let bounds = CGRect(x: 0, y: 0, width: width, height: height)
        guard bounds.contains(point) else { return [:] }
        let source = snapshot.pixels[layerID] ?? [:]
        let reader = TilePixels(source)
        let target = try reader.rgba(Int(point.x), Int(point.y))
        var visited: [TileKey: [UInt64]] = [:]
        var output: [TileKey: CGContext] = [:]
        var pending = [(Int(point.x), Int(point.y))]
        let tolerance = max(0, min(255, tolerance))
        func visitedAt(_ x: Int, _ y: Int) -> Bool {
            let index = (y % 256) * 256 + x % 256
            return ((visited[TileKey(x: x / 256, y: y / 256)]?[index / 64] ?? 0) & (UInt64(1) << (index % 64))) != 0
        }
        func matches(_ x: Int, _ y: Int) throws -> Bool {
            guard x >= 0, y >= 0, x < width, y < height, !visitedAt(x, y),
                  selection?.contains(CGPoint(x: Double(x) + 0.5, y: Double(y) + 0.5), in: bounds) != false else { return false }
            let value = try reader.rgba(x, y)
            return [0, 8, 16, 24].allSatisfy { shift in
                abs(Int((value >> shift) & 255) - Int((target >> shift) & 255)) <= tolerance
            }
        }
        while let (seedX, y) = pending.popLast() {
            guard try matches(seedX, y) else { continue }
            var left = seedX, right = seedX
            while try matches(left - 1, y) { left -= 1 }
            while try matches(right + 1, y) { right += 1 }
            var above = false, below = false
            for x in left...right {
                let key = TileKey(x: x / 256, y: y / 256)
                if visited[key] == nil {
                    guard visited.count < 64 else { throw DrawingError.message("This fill exceeds the undo budget. Use a smaller selected area.") }
                    visited[key] = Array(repeating: 0, count: 1024)
                    let context = try Self.context(width: 256, height: 256)
                    if let image = source[key] { Self.draw(image, in: context, rect: CGRect(x: 0, y: 0, width: 256, height: 256)) }
                    context.setFillColor(color.cgColor); context.setShouldAntialias(false)
                    if snapshot.record.layers.first(where: { $0.id == layerID })?.alphaLocked == true { context.setBlendMode(.sourceAtop) }
                    output[key] = context
                }
                let index = (y % 256) * 256 + x % 256
                visited[key]![index / 64] |= UInt64(1) << (index % 64)
                output[key]!.fill(CGRect(x: x % 256, y: y % 256, width: 1, height: 1))
                let nextAbove = try matches(x, y - 1), nextBelow = try matches(x, y + 1)
                if nextAbove && !above { pending.append((x, y - 1)) }
                if nextBelow && !below { pending.append((x, y + 1)) }
                above = nextAbove; below = nextBelow
            }
            guard pending.count <= 131_072 else { throw DrawingError.message("This fill is too complex for the current memory budget.") }
        }
        return try output.mapValues { context in
            guard let image = context.makeImage() else { throw DrawingError.message("Could not finish the fill.") }; return image
        }
    }

    func paintPath(_ path: CGPath, color: UIColor, width: CGFloat, filled: Bool, erasing: Bool = false) throws {
        let area = path.boundingBoxOfPath.insetBy(dx: -width, dy: -width)
        try editTiles(in: area, erasing: erasing) { context in
            context.setStrokeColor(color.cgColor); context.setFillColor(color.cgColor)
            context.setLineWidth(width); context.setLineCap(.round); context.setLineJoin(.round)
            context.addPath(path)
            if filled { context.fillPath(using: .evenOdd) } else { context.strokePath() }
        }
    }

    func paintPixelLine(from: CGPoint, to: CGPoint, size: Int, color: UIColor, dither: Bool) throws {
        let size = max(1, min(64, size))
        let x0 = Int(floor(from.x)), y0 = Int(floor(from.y)), x1 = Int(floor(to.x)), y1 = Int(floor(to.y))
        let area = CGRect(x: min(x0, x1), y: min(y0, y1), width: abs(x1 - x0) + size, height: abs(y1 - y0) + size)
        try editTiles(in: area) { context in
            context.setShouldAntialias(false); context.setFillColor(color.cgColor)
            var x = x0, y = y0
            let dx = abs(x1 - x0), dy = -abs(y1 - y0), sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1
            var error = dx + dy
            while true {
                if dither {
                    for oy in 0..<size { for ox in 0..<size where (x + ox + y + oy) & 1 == 0 {
                        context.fill(CGRect(x: x + ox, y: y + oy, width: 1, height: 1))
                    } }
                } else { context.fill(CGRect(x: x, y: y, width: size, height: size)) }
                if x == x1 && y == y1 { break }
                let twice = error * 2
                if twice >= dy { error += dy; x += sx }
                if twice <= dx { error += dx; y += sy }
            }
        }
    }

    func smudge(from: CGPoint, to: CGPoint, radius: CGFloat, strength: CGFloat) throws {
        guard from != to, strength > 0 else { return }
        let radius = Int(radius.rounded()).clamped(to: 1...96), side = radius * 2 + 1
        let source = CGRect(x: Int(from.x.rounded()) - radius, y: Int(from.y.rounded()) - radius, width: side, height: side)
        let destination = CGRect(x: Int(to.x.rounded()) - radius, y: Int(to.y.rounded()) - radius, width: side, height: side)
        func capture(_ rect: CGRect) throws -> CGContext {
            let context = try Self.context(width: side, height: side)
            context.translateBy(x: -rect.minX, y: -rect.minY)
            selection?.clip(context, bounds: bounds)
            for key in TileKey.intersecting(rect, width: record.width, height: record.height) {
                if let image = pixels[activeLayer]?[key] { Self.draw(image, in: context, rect: key.rect) }
            }
            return context
        }
        let pickup = try capture(source), target = try capture(destination)
        let a = pickup.data!.assumingMemoryBound(to: UInt8.self), b = target.data!.assumingMemoryBound(to: UInt8.self)
        guard (0..<(side * side)).contains(where: { a[$0 * 4 + 3] != 0 }) else { return }
        var delta = [Float](repeating: 0, count: side * side * 4)
        for y in 0..<side { for x in 0..<side {
            let coverage = max(0, min(1, Float(radius) + 0.5 - hypot(Float(x - radius), Float(y - radius))))
            let amount = Float(max(0, min(1, strength))) * 0.5 * coverage
            let index = (y * side + x) * 4
            for channel in 0..<4 { delta[index + channel] = (Float(b[index + channel]) - Float(a[index + channel])) * amount }
        } }
        func deltaAt(x: Int, y: Int, channel: Int) -> Float {
            guard x >= 0, y >= 0, x < side, y < side else { return 0 }
            return delta[(y * side + x) * 4 + channel]
        }
        // Exchange premultiplied color and alpha, including overlap corrections. This
        // transports pigment at transparent edges instead of repeatedly copying it.
        let dx = Int(destination.minX - source.minX), dy = Int(destination.minY - source.minY)
        for y in 0..<side { for x in 0..<side {
            let index = (y * side + x) * 4
            for channel in [3, 0, 1, 2] {
                let av = Float(a[index + channel]) + delta[index + channel] - deltaAt(x: x - dx, y: y - dy, channel: channel)
                let bv = Float(b[index + channel]) - delta[index + channel] + deltaAt(x: x + dx, y: y + dy, channel: channel)
                a[index + channel] = UInt8(max(0, min(channel == 3 ? 255 : Float(a[index + 3]), av)).rounded())
                b[index + channel] = UInt8(max(0, min(channel == 3 ? 255 : Float(b[index + 3]), bv)).rounded())
            }
        } }
        guard let first = pickup.makeImage(), let second = target.makeImage() else { throw DrawingError.message("Could not prepare smudge tiles.") }
        let keys = Set(TileKey.intersecting(source, width: record.width, height: record.height))
            .union(TileKey.intersecting(destination, width: record.width, height: record.height))
        let layer = record.layers.first(where: { $0.id == activeLayer })
        let alphaLocked = layer?.alphaLocked == true || layer?.reference == true
        try editTiles(keys: Array(keys), erasing: false) { context in
            context.setBlendMode(alphaLocked ? .sourceAtop : .copy)
            Self.draw(first, in: context, rect: source); Self.draw(second, in: context, rect: destination)
        }
    }

    /// Transform only selected pixels. Scratch/output memory is checked before publishing anything.
    func transformSelection(_ transform: CGAffineTransform, copy: Bool = false) throws {
        guard abs(transform.determinant) > 0.00001, transform.a.isFinite, transform.b.isFinite,
              transform.c.isFinite, transform.d.isFinite, transform.tx.isFinite, transform.ty.isFinite else { return }
        let selected = selection
        let sourceBounds = selected?.inverted == true ? bounds : (selected?.path.boundingBoxOfPath ?? bounds).intersection(bounds)
        let sourceKeys = TileKey.intersecting(sourceBounds, width: record.width, height: record.height).filter { pixels[activeLayer]?[$0] != nil }
        guard record.layers.first(where: { $0.id == activeLayer })?.alphaLocked != true else {
            throw DrawingError.message("Unlock transparency before transforming pixels.")
        }
        let destinationKeys = TileKey.intersecting(sourceBounds.applying(transform), width: record.width, height: record.height)
        guard sourceKeys.count <= 64,
              Set(sourceKeys).union(destinationKeys).count * Self.tileBytes * 2 <= Self.historyBudget else {
            throw DrawingError.message("This transform exceeds the undo budget. Use a smaller selected area.")
        }
        var cutouts: [TileKey: CGImage] = [:]
        for key in sourceKeys {
            let context = try Self.context(width: 256, height: 256)
            context.translateBy(x: -key.rect.minX, y: -key.rect.minY)
            selected?.clip(context, bounds: bounds)
            Self.draw(pixels[activeLayer]![key]!, in: context, rect: key.rect)
            cutouts[key] = context.makeImage()
        }
        guard beginStroke() else { return }
        do {
            if !copy {
                try editTiles(in: sourceBounds, erasing: true) { context in context.fill(sourceBounds) }
            }
            selection = nil
            try editTiles(in: sourceBounds.applying(transform)) { context in
                context.concatenate(transform)
                for (key, image) in cutouts { Self.draw(image, in: context, rect: key.rect) }
            }
            endStroke(cancelled: false)
            if let selected {
                let region = CGMutablePath()
                if selected.inverted { region.addRect(bounds) }
                region.addPath(selected.path)
                var t = transform
                selection = CanvasSelection(path: region.copy(using: &t) ?? region)
            }
        } catch { selection = selected; endStroke(cancelled: true); throw error }
    }
}

private extension CGAffineTransform { var determinant: CGFloat { a * d - b * c } }

private extension Int { func clamped(to range: ClosedRange<Int>) -> Int { Swift.min(range.upperBound, Swift.max(range.lowerBound, self)) } }

extension RasterDocument {
    func shadowSource(color: UIColor) throws -> [TileKey: CGImage] {
        guard let selection else { throw DrawingError.message("Select an area before using Shadow pull.") }
        guard record.layers.first(where: { $0.id == activeLayer })?.alphaLocked != true, !paintInside else {
            throw DrawingError.message("Turn off Paint inside and alpha lock to pull a shadow.")
        }
        let keys = (pixels[activeLayer] ?? [:]).keys.filter { selection.inverted || selection.path.boundingBoxOfPath.intersects($0.rect) }
        guard keys.count <= 64 else { throw DrawingError.message("Select a smaller area for Shadow pull.") }
        var result: [TileKey: CGImage] = [:]
        for key in keys {
            let context = try Self.context(width: 256, height: 256)
            context.translateBy(x: -key.rect.minX, y: -key.rect.minY)
            selection.clip(context, bounds: bounds)
            Self.draw(pixels[activeLayer]![key]!, in: context, rect: key.rect)
            context.setBlendMode(.sourceIn); context.setFillColor(color.cgColor); context.fill(key.rect)
            result[key] = context.makeImage()
        }
        return result
    }
    func paintShadow(_ source: [TileKey: CGImage], offset: CGPoint) throws {
        let selected = selection; selection = nil
        defer { selection = selected }
        let t = CGAffineTransform(translationX: offset.x, y: offset.y)
        let keys = Set(source.keys.flatMap { TileKey.intersecting($0.rect.applying(t), width: record.width, height: record.height) })
        try editTiles(keys: Array(keys), erasing: false) { context in
            context.setBlendMode(.destinationOver)
            for (key, image) in source where key.rect.applying(t).intersects(context.boundingBoxOfClipPath) {
                Self.draw(image, in: context, rect: key.rect.applying(t))
            }
        }
    }
}

extension RasterDocument {
    func pushColor(from: CGPoint, to: CGPoint, radius: CGFloat, strength: CGFloat) throws {
        guard from != to, strength > 0 else { return }
        let radius = min(96, max(1, radius))
        let area = CGRect(x: floor(to.x - radius), y: floor(to.y - radius), width: ceil(radius * 2) + 2, height: ceil(radius * 2) + 2).intersection(bounds)
        guard !area.isNull else { return }
        let reader = TilePixels(pixels[activeLayer] ?? [:])
        let output = try Self.context(width: Int(area.width), height: Int(area.height))
        let bytes = output.data!.assumingMemoryBound(to: UInt8.self)
        let preserveAlpha = record.layers.first(where: { $0.id == activeLayer })?.alphaLocked == true
        let warp = NativeColorPush(x: Float(to.x), y: Float(to.y), dx: Float(to.x - from.x), dy: Float(to.y - from.y), radius: Float(radius), strength: Float(strength))
        for y in 0..<Int(area.height) { for x in 0..<Int(area.width) {
            let source = warp.source(x: Float(area.minX) + Float(x), y: Float(area.minY) + Float(y))
            let sx = Int(floor(source.x)), sy = Int(floor(source.y))
            let fx = source.x - Float(sx), fy = source.y - Float(sy)
            let samples = try [reader.rgba(sx, sy), reader.rgba(sx + 1, sy), reader.rgba(sx, sy + 1), reader.rgba(sx + 1, sy + 1)]
            let weights = [(1 - fx) * (1 - fy), fx * (1 - fy), (1 - fx) * fy, fx * fy]
            for channel in 0..<4 {
                var value: Float = 0
                for i in 0..<4 { value += Float((samples[i] >> (24 - channel * 8)) & 255) * weights[i] }
                bytes[(y * Int(area.width) + x) * 4 + channel] = UInt8(max(0, min(255, value)).rounded())
            }
            if preserveAlpha {
                let i = (y * Int(area.width) + x) * 4
                let original = try reader.rgba(Int(area.minX) + x, Int(area.minY) + y)
                let oldAlpha = Int(original & 255), newAlpha = Int(bytes[i + 3])
                for channel in 0..<3 {
                    bytes[i + channel] = newAlpha > 0 ? UInt8(min(oldAlpha, Int(bytes[i + channel]) * oldAlpha / newAlpha)) : UInt8((original >> (24 - channel * 8)) & 255)
                }
                bytes[i + 3] = UInt8(oldAlpha)
            }
        } }
        guard let image = output.makeImage() else { throw DrawingError.message("Could not prepare Color push.") }
        try editTiles(in: area) { context in
            context.setBlendMode(.copy); Self.draw(image, in: context, rect: area)
        }
    }
}
