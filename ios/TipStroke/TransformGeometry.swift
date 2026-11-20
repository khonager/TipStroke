import UIKit
import TipStrokeShared

/// A normalized triangle mesh uses the same diagonal and handle weights as the shared PhotoWarp.
struct TransformGeometry {
    let rect: CGRect
    var pose: CGAffineTransform
    var mesh: [CGPoint]?
    var filtered = true
    private let cells = 16

    init(rect: CGRect, pose: CGAffineTransform = .identity, mesh: [CGPoint]? = nil, filtered: Bool = true) {
        self.rect = rect; self.pose = pose; self.mesh = mesh; self.filtered = filtered
    }
    func point(_ u: CGFloat, _ v: CGFloat) -> CGPoint {
        CGPoint(x: rect.minX + u * rect.width, y: rect.minY + v * rect.height)
    }
    func vertex(_ index: Int) -> CGPoint {
        let p = mesh?[index] ?? CGPoint(x: CGFloat(index % 17) / 16, y: CGFloat(index / 17) / 16)
        return point(p.x, p.y).applying(pose)
    }
    var bounds: CGRect {
        guard let mesh else { return rect.applying(pose) }
        return mesh.reduce(CGRect.null) { result, p in
            let q = point(p.x, p.y).applying(pose)
            return result.union(CGRect(origin: q, size: .zero))
        }
    }
    func validated() -> Bool {
        guard let mesh else { return true }
        guard mesh.count == 289, mesh.allSatisfy({ $0.x.isFinite && $0.y.isFinite && abs($0.x) < 1024 && abs($0.y) < 1024 }) else { return false }
        func area(_ a: Int, _ b: Int, _ c: Int) -> CGFloat {
            let p = mesh[a], q = mesh[b], r = mesh[c]
            return (q.x - p.x) * (r.y - p.y) - (q.y - p.y) * (r.x - p.x)
        }
        for row in 0..<16 { for col in 0..<16 {
            let i = row * 17 + col
            if area(i, i+1, i+17) <= 0.0000001 || area(i+18, i+17, i+1) <= 0.0000001 { return false }
        } }
        return true
    }

    /// For each visible triangle supply its source-to-document transform and optional source clip.
    func pieces(in destination: CGRect, source: CGRect? = nil,
                _ draw: (CGAffineTransform, CGPath?, CGRect) throws -> Void) rethrows {
        if mesh == nil {
            if rect.applying(pose).intersects(destination) { try draw(pose, nil, rect) }
            return
        }
        let stepX = rect.width / CGFloat(cells), stepY = rect.height / CGFloat(cells)
        for row in 0..<cells { for col in 0..<cells {
            let cell = CGRect(x: rect.minX + CGFloat(col) * stepX, y: rect.minY + CGFloat(row) * stepY, width: stepX, height: stepY)
            if let source, !source.intersects(cell) { continue }
            let i = row * 17 + col
            for upper in [true, false] {
                let a = vertex(upper ? i : i+18), b = vertex(upper ? i+1 : i+17), c = vertex(upper ? i+17 : i+1)
                let sx = upper ? stepX : -stepX, sy = upper ? stepY : -stepY
                let x = upper ? cell.minX : cell.maxX, y = upper ? cell.minY : cell.maxY
                let aa = (b.x-a.x)/sx, bb = (b.y-a.y)/sx, cc = (c.x-a.x)/sy, dd = (c.y-a.y)/sy
                let t = CGAffineTransform(a: aa, b: bb, c: cc, d: dd, tx: a.x-aa*x-cc*y, ty: a.y-bb*x-dd*y)
                if !cell.applying(t).intersects(destination) { continue }
                let path = CGMutablePath()
                path.move(to: CGPoint(x: x, y: y)); path.addLine(to: CGPoint(x: x + sx, y: y))
                path.addLine(to: CGPoint(x: x, y: y + sy)); path.closeSubpath()
                try draw(t, path, cell)
            }
        } }
    }
    func render(in context: CGContext, visible: CGRect, draw: (CGContext) -> Void) {
        pieces(in: visible) { t, path, _ in
            context.saveGState()
            context.concatenate(t)
            if let path {
                context.setShouldAntialias(false); context.addPath(path); context.clip()
            }
            context.interpolationQuality = filtered ? .medium : .none
            draw(context)
            context.restoreGState()
        }
    }
    func sourceKeys(over destination: CGRect, width: Int, height: Int) -> Set<TileKey> {
        var keys = Set<TileKey>()
        pieces(in: destination) { transform, _, cell in
            keys.formUnion(TileKey.intersecting(destination.applying(transform.inverted()).intersection(cell), width: width, height: height))
        }
        return keys
    }
    enum Tool: String, CaseIterable { case move = "Move", scale = "Scale", stretch = "Stretch", rotate = "Rotate", distort = "Perspective", warp = "Mesh", push = "Push", bulge = "Bulge", pinch = "Pinch" }
    func dragged(tool: Tool, from start: CGPoint, to end: CGPoint, divisions: Int, radius: CGFloat) -> Self {
        var result = self
        let dx = end.x-start.x, dy = end.y-start.y
        let pivot = CGPoint(x: bounds.midX, y: bounds.midY)
        func aroundPivot(_ t: CGAffineTransform) -> CGAffineTransform {
            CGAffineTransform(translationX: -pivot.x, y: -pivot.y).concatenating(t).translatedInWorld(pivot.x, pivot.y)
        }
        switch tool {
        case .move: result.pose = pose.translatedInWorld(dx, dy)
        case .scale, .stretch:
            let startX = start.x-pivot.x, startY = start.y-pivot.y, endX = end.x-pivot.x, endY = end.y-pivot.y
            let uniform = max(0.01, hypot(endX,endY) / max(1,hypot(startX,startY)))
            let sx = tool == .scale ? uniform : abs(startX) < 1 ? 1 : max(0.01,endX/startX)
            let sy = tool == .scale ? uniform : abs(startY) < 1 ? 1 : max(0.01,endY/startY)
            result.pose = pose.concatenating(aroundPivot(CGAffineTransform(scaleX: sx, y: sy)))
        case .rotate:
            let angle = atan2(end.y-pivot.y,end.x-pivot.x) - atan2(start.y-pivot.y,start.x-pivot.x)
            result.pose = pose.concatenating(aroundPivot(CGAffineTransform(rotationAngle: angle)))
        default:
            let shape = mesh.map { PhotoWarp(points: $0.map { TipStrokeShared.Point(x: Float($0.x), y: Float($0.y)) }) } ?? PhotoWarp.companion.identity()
            let a = start.applying(pose.inverted()), b = end.applying(pose.inverted())
            let delta = TipStrokeShared.Point(x: Float((b.x-a.x)/rect.width), y: Float((b.y-a.y)/rect.height))
            let changed: PhotoWarp
            if tool == .distort || tool == .warp {
                let n = tool == .distort ? 1 : divisions
                let best = (0..<(n+1)*(n+1)).min { lhs,rhs in
                    func distance(_ id: Int) -> CGFloat {
                        let v = vertex((id/(n+1))*16/n*17 + (id%(n+1))*16/n)
                        return hypot(start.x-v.x, start.y-v.y)
                    }
                    return distance(lhs) < distance(rhs)
                } ?? 0
                let col = Int32(best % (n+1)), row = Int32(best / (n+1))
                if tool == .distort { changed = shape.distortCorner(column: col, row: row, delta: delta) }
                else { changed = shape.moveControl(column: col, row: row, divisions: Int32(n), delta: delta) }
            } else {
                let center = TipStrokeShared.Point(x: Float((a.x-rect.minX)/rect.width), y: Float((a.y-rect.minY)/rect.height))
                let native: PhotoEditTool = tool == .push ? .push : (tool == .bulge ? .bulge : .pinch)
                changed = shape.deform(tool: native, center: center, delta: delta, radius: Float(max(0.001,radius/max(1,bounds.height))), aspect: Float(rect.width/rect.height))
            }
            result.mesh = changed.points.map { CGPoint(x: CGFloat($0.x), y: CGFloat($0.y)) }
        }
        return result
    }
}
private extension CGAffineTransform {
    func translatedInWorld(_ x: CGFloat, _ y: CGFloat) -> Self { concatenating(CGAffineTransform(translationX: x, y: y)) }
}
