import UIKit

/// A transform retains the source once. Editing handles changes geometry until Apply.
final class SelectionTransformSession {
    let revision: UInt64
    let layerID: UUID
    let sourceSelection: CanvasSelection?
    let initial: TransformGeometry
    let cuts: [TileKey: CGImage]
    let photo: PhotoLayerRecord?
    let preview: CGImage
    var geometry: TransformGeometry
    init(document: RasterDocument) throws {
        guard let layer = document.record.layers.first(where: { $0.id == document.activeLayer }),
              layer.visible, !layer.locked else { throw DrawingError.message("Select an unlocked, visible layer.") }
        revision = document.revision; layerID = layer.id; sourceSelection = document.selection; photo = layer.photo
        var cutouts: [TileKey: CGImage] = [:]
        let g: TransformGeometry
        if let photo = layer.photo {
            g = photo.geometry
        } else {
            guard !layer.alphaLocked else { throw DrawingError.message("Unlock transparency before transforming paint.") }
            let selection = document.selection
            let region = (selection?.inverted == true ? document.bounds : selection?.path.boundingBoxOfPath ?? document.bounds).intersection(document.bounds)
            let tiles = (document.pixels[layer.id] ?? [:]).filter { $0.key.rect.intersects(region) }
            guard !tiles.isEmpty, tiles.count * RasterDocument.tileBytes * 2 <= RasterDocument.historyBudget else {
                throw DrawingError.message("Select painted pixels within the editing memory budget.")
            }
            for (key, image) in tiles {
                let ctx = try RasterDocument.context(width: 256, height: 256)
                ctx.translateBy(x: -key.rect.minX, y: -key.rect.minY)
                selection?.clip(ctx, bounds: document.bounds)
                RasterDocument.draw(image, in: ctx, rect: key.rect)
                cutouts[key] = ctx.makeImage()
            }
            let rect = tiles.keys.reduce(CGRect.null) { $0.union($1.rect) }.intersection(region)
            g = TransformGeometry(rect: rect)
        }
        cuts = cutouts; initial = g; geometry = g
        let rect = g.rect
        let scale = min(1, 768 / max(rect.width, rect.height))
        let w = max(1, Int(ceil(rect.width * scale))), h = max(1, Int(ceil(rect.height * scale)))
        let context = try RasterDocument.context(width: w, height: h)
        context.scaleBy(x: CGFloat(w)/rect.width, y: CGFloat(h)/rect.height)
        context.translateBy(x: -rect.minX, y: -rect.minY)
        if let photo = layer.photo, let source = photo.source {
            RasterDocument.draw(source.image, in: context, rect: rect)
            context.setBlendMode(.destinationOut)
            for (key, mask) in document.pixels[layer.id] ?? [:] { RasterDocument.draw(mask, in: context, rect: key.rect) }
        } else {
            for (key, image) in cutouts { RasterDocument.draw(image, in: context, rect: key.rect) }
        }
        guard let preview = context.makeImage() else { throw DrawingError.message("Could not prepare the transform preview.") }
        self.preview = preview
    }
    func apply(to document: RasterDocument, copy: Bool) throws {
        guard revision == document.revision, layerID == document.activeLayer,
              let index = document.record.layers.firstIndex(where: { $0.id == layerID }),
              !document.record.layers[index].locked else { throw DrawingError.message("The layer changed. Reopen Transform.") }
        if var photo {
            photo.transform = geometry.pose; photo.mesh = geometry.mesh; photo.filtered = geometry.filtered
            try photo.validate()
            document.beginMetadataEdit(); document.record.layers[index].photo = photo; document.endMetadataEdit()
            return
        }
        let bounds = document.bounds
        let destination = TileKey.intersecting(geometry.bounds, width: document.record.width, height: document.record.height)
        let keys = Set(destination).union(copy ? Set() : Set(cuts.keys))
        guard keys.count * RasterDocument.tileBytes * 2 <= RasterDocument.historyBudget else { throw DrawingError.message("This transform exceeds the undo budget.") }
        let layer = document.pixels[layerID] ?? [:]
        guard document.residentTileCount + keys.filter({ layer[$0] == nil }).count <= RasterDocument.maximumTiles else {
            throw DrawingError.message("This transform exceeds the resident image budget.")
        }
        var result: [TileKey: CGImage] = [:]
        for key in keys {
            let ctx = try RasterDocument.context(width: 256, height: 256)
            ctx.translateBy(x: -key.rect.minX, y: -key.rect.minY); ctx.clip(to: bounds)
            if let image = layer[key] { RasterDocument.draw(image, in: ctx, rect: key.rect) }
            if !copy, cuts[key] != nil {
                ctx.saveGState()
                sourceSelection?.clip(ctx, bounds: bounds); ctx.clear(key.rect)
                ctx.restoreGState()
            }
            geometry.render(in: ctx, visible: key.rect) { piece in
                for source in TileKey.intersecting(piece.boundingBoxOfClipPath, width: document.record.width, height: document.record.height) {
                    if let image = cuts[source] { RasterDocument.draw(image, in: piece, rect: source.rect) }
                }
            }
            guard let image = ctx.makeImage() else { throw DrawingError.message("Could not render transformed pixels.") }
            result[key] = image
        }
        try document.replacePreparedTiles(result)
        if let sourceSelection {
            let path = CGMutablePath()
            if sourceSelection.inverted { path.addRect(bounds) }
            path.addPath(sourceSelection.path)
            document.selection = CanvasSelection(path: geometry.mapPath(path))
        }
    }
}

extension TransformGeometry {
    /// Sample curves in document coordinates so a previously straight selection edge follows mesh bends.
    func mapPath(_ path: CGPath) -> CGPath {
        guard mesh != nil else { var t = pose; return path.copy(using: &t) ?? path }
        let out = CGMutablePath()
        var previous = CGPoint.zero, start = CGPoint.zero
        func mapped(_ p: CGPoint) -> CGPoint {
            let u = (p.x-rect.minX)/rect.width*16, v = (p.y-rect.minY)/rect.height*16
            let col = min(15,max(0,Int(floor(u)))), row = min(15,max(0,Int(floor(v))))
            let a = u-CGFloat(col), b = v-CGFloat(row), i = row*17+col
            let f = a+b <= 1, o = vertex(f ? i : i+18), q = vertex(f ? i+1 : i+17), r = vertex(f ? i+17 : i+1)
            let v1 = f ? a : 1-a, v2 = f ? b : 1-b
            return CGPoint(x: o.x+(q.x-o.x)*v1+(r.x-o.x)*v2, y: o.y+(q.y-o.y)*v1+(r.y-o.y)*v2)
        }
        func mix(_ a: CGPoint, _ b: CGPoint, _ t: CGFloat) -> CGPoint { CGPoint(x: a.x+(b.x-a.x)*t, y: a.y+(b.y-a.y)*t) }
        func segment(_ end: CGPoint, controls: [CGPoint] = []) {
            let distance = hypot(end.x-previous.x,end.y-previous.y)
            let n = min(64,max(controls.isEmpty ? 1 : 8,Int(ceil(distance/max(1,min(rect.width,rect.height)/16)))))
            for i in 1...n {
                let t = CGFloat(i)/CGFloat(n)
                var pts = [previous]+controls+[end]
                while pts.count > 1 { pts = zip(pts,pts.dropFirst()).map { mix($0.0,$0.1,t) } }
                out.addLine(to: mapped(pts[0]))
            }
            previous = end
        }
        path.applyWithBlock { raw in
            let e = raw.pointee, pts = e.points
            switch e.type {
            case .moveToPoint: start = pts[0]; previous = start; out.move(to: mapped(start))
            case .addLineToPoint: segment(pts[0])
            case .addQuadCurveToPoint: segment(pts[1], controls: [pts[0]])
            case .addCurveToPoint: segment(pts[2], controls: [pts[0],pts[1]])
            case .closeSubpath: segment(start); out.closeSubpath()
            @unknown default: break
            }
        }
        return out
    }
}
