import UIKit

/// Each selected layer retains immutable source pixels or its photo pose until Apply.
final class SelectionTransformSession {
    let revision: UInt64
    let layerID: UUID
    let sourceSelection: CanvasSelection?
    let initial: TransformGeometry
    let preview: CGImage
    let photos: [UUID: PhotoLayerRecord]
    let singlePhoto: Bool
    private let cuts: [UUID: [TileKey: CGImage]]
    var geometry: TransformGeometry

    init(document: RasterDocument) throws {
        let ids = document.editingLayerIDs
        let targets = document.record.layers.filter { ids.contains($0.id) && $0.visible && $0.photoCelActive }
        guard !targets.isEmpty, targets.allSatisfy({ !$0.locked && ($0.photo != nil || !$0.alphaLocked) }) else {
            throw DrawingError.message("Select visible, unlocked layers. Unlock transparency to transform paint.")
        }
        revision = document.revision; layerID = document.activeLayer; sourceSelection = document.selection
        singlePhoto = targets.count == 1 && targets[0].photo != nil
        photos = targets.reduce(into: [:]) { $0[$1.id] = $1.photo }
        let selection = document.selection
        let region = (selection?.inverted == true ? document.bounds : selection?.path.boundingBoxOfPath ?? document.bounds).intersection(document.bounds)
        var area = CGRect.null, count = 0, allCuts: [UUID: [TileKey: CGImage]] = [:]
        for layer in targets {
            if let photo = layer.photo {
                area = area.union(photo.bounds)
                continue
            }
            let tiles = (document.pixels[layer.id] ?? [:]).filter { $0.key.rect.intersects(region) }
            count += tiles.count
            guard count * RasterDocument.tileBytes * 2 <= RasterDocument.historyBudget else { throw DrawingError.message("Select a smaller area to transform.") }
            for (key,image) in tiles {
                let ctx = try RasterDocument.context(width: 256,height: 256)
                ctx.translateBy(x: -key.rect.minX,y: -key.rect.minY)
                selection?.clip(ctx,bounds: document.bounds)
                RasterDocument.draw(image,in: ctx,rect: key.rect)
                guard let cut = ctx.makeImage() else { throw DrawingError.message("Could not prepare selected paint.") }
                allCuts[layer.id, default: [:]][key] = cut
                area = area.union(key.rect.intersection(region))
            }
        }
        guard !area.isEmpty else { throw DrawingError.message("Select an area containing paint.") }
        cuts = allCuts
        let g = singlePhoto ? targets[0].photo!.geometry : TransformGeometry(rect: area)
        initial = g; geometry = g
        let rect = g.rect
        let scale = min(1,768/max(rect.width,rect.height))
        let w = max(1,Int(ceil(rect.width*scale))), h = max(1,Int(ceil(rect.height*scale)))
        let ctx = try RasterDocument.context(width:w,height:h)
        ctx.scaleBy(x:CGFloat(w)/rect.width,y:CGFloat(h)/rect.height)
        ctx.translateBy(x:-rect.minX,y:-rect.minY)
        for layer in targets {
            ctx.saveGState()
            ctx.setAlpha(targets.count == 1 ? 1 : layer.opacity)
            ctx.setBlendMode(RasterDocument.blendModes[layer.blendMode] ?? .normal)
            ctx.beginTransparencyLayer(auxiliaryInfo:nil)
            if let photo = layer.photo, let source = photo.source {
                func drawPhoto(_ piece: CGContext) {
                    RasterDocument.draw(source.image,in:piece,rect:photo.sourceBounds)
                    piece.setBlendMode(.destinationOut)
                    for (key,mask) in document.pixels[layer.id] ?? [:] where piece.boundingBoxOfClipPath.intersects(key.rect) {
                        RasterDocument.draw(mask,in:piece,rect:key.rect)
                    }
                }
                if singlePhoto { drawPhoto(ctx) }
                else { photo.geometry.render(in:ctx,visible:rect,draw:drawPhoto) }
            } else {
                for (key,image) in allCuts[layer.id] ?? [:] { RasterDocument.draw(image,in:ctx,rect:key.rect) }
            }
            ctx.endTransparencyLayer(); ctx.restoreGState()
        }
        guard let preview = ctx.makeImage() else { throw DrawingError.message("Could not prepare the transform preview.") }
        self.preview = preview
    }

    func apply(to document: RasterDocument, copy: Bool) throws {
        guard revision == document.revision,layerID == document.activeLayer else { throw DrawingError.message("The layer changed. Reopen Transform.") }
        let bounds = document.bounds
        var result: [UUID: [TileKey: CGImage]] = [:], poses: [UUID: PhotoLayerRecord] = [:]
        var touchedCount = 0, newCount = 0
        for (id,old) in photos {
            var updated = old
            if singlePhoto {
                updated.transform = geometry.pose; updated.mesh = geometry.mesh
            } else {
                updated.transform = old.transform.concatenating(geometry.pose)
                if geometry.mesh != nil {
                    let inverse = updated.transform.inverted()
                    updated.mesh = (0..<289).map { i in
                        let p = geometry.mapped(old.geometry.vertex(i)).applying(inverse)
                        return CGPoint(x:p.x/CGFloat(old.width),y:p.y/CGFloat(old.height))
                    }
                }
            }
            updated.filtered = geometry.filtered
            try updated.validate(); poses[id] = updated
        }
        for (id, cutouts) in cuts {
            let layer = document.pixels[id] ?? [:]
            var keys = copy ? Set<TileKey>() : Set(cutouts.keys)
            // Sparse paint stays sparse across large selections. Only transform occupied source tiles.
            geometry.pieces(in: bounds) { t, _, cell in
                for source in cutouts.keys where source.rect.intersects(cell) {
                    keys.formUnion(TileKey.intersecting(source.rect.intersection(cell).applying(t),
                                                        width: document.record.width,height: document.record.height))
                }
            }
            touchedCount += keys.count; newCount += keys.filter { layer[$0] == nil }.count
            guard touchedCount * RasterDocument.tileBytes * 2 + 256 <= RasterDocument.historyBudget,
                  document.residentTileCount + newCount <= RasterDocument.maximumTiles else { throw DrawingError.message("The transform exceeds the editing budget.") }
            for key in keys {
                let ctx = try RasterDocument.context(width:256,height:256)
                ctx.translateBy(x:-key.rect.minX,y:-key.rect.minY); ctx.clip(to:bounds)
                if let original = layer[key] { RasterDocument.draw(original,in:ctx,rect:key.rect) }
                if !copy, cutouts[key] != nil {
                    ctx.saveGState(); sourceSelection?.clip(ctx,bounds:bounds); ctx.clear(key.rect); ctx.restoreGState()
                }
                geometry.render(in:ctx,visible:key.rect) { piece in
                    for source in TileKey.intersecting(piece.boundingBoxOfClipPath,width:document.record.width,height:document.record.height) {
                        if let image = cutouts[source] { RasterDocument.draw(image,in:piece,rect:source.rect) }
                    }
                }
                guard let image = ctx.makeImage() else { throw DrawingError.message("Could not render transformed pixels.") }
                result[id,default:[:]][key] = image
            }
        }
        var moved: (CanvasSelection,CanvasSelection)?
        if !cuts.isEmpty, let sourceSelection {
            let path = CGMutablePath()
            if sourceSelection.inverted { path.addRect(bounds) }
            path.addPath(sourceSelection.path)
            moved = (sourceSelection,CanvasSelection(path:geometry.mapPath(path)))
        }
        document.beginMetadataEdit()
        for i in document.record.layers.indices {
            let id = document.record.layers[i].id
            if let photo = poses[id] { document.record.layers[i].photo = photo }
            if let tiles = result[id] { document.pixels[id,default:[:]].merge(tiles) { _,new in new } }
        }
        document.endMetadataEdit(force: !result.isEmpty, movedSelection: moved)
    }
}

extension TransformGeometry {
    func mapped(_ p: CGPoint) -> CGPoint {
            let u = (p.x-rect.minX)/rect.width*16, v = (p.y-rect.minY)/rect.height*16
            let col = min(15,max(0,Int(floor(u)))), row = min(15,max(0,Int(floor(v))))
            let a = u-CGFloat(col), b = v-CGFloat(row), i = row*17+col
            let f = a+b <= 1, o = vertex(f ? i : i+18), q = vertex(f ? i+1 : i+17), r = vertex(f ? i+17 : i+1)
            let v1 = f ? a : 1-a, v2 = f ? b : 1-b
            return CGPoint(x: o.x+(q.x-o.x)*v1+(r.x-o.x)*v2, y: o.y+(q.y-o.y)*v1+(r.y-o.y)*v2)
        }

    /// Sample curves in document coordinates so a previously straight selection edge follows mesh bends.
    func mapPath(_ path: CGPath) -> CGPath {
        guard mesh != nil else { var t = pose; return path.copy(using: &t) ?? path }
        let out = CGMutablePath()
        var previous = CGPoint.zero, start = CGPoint.zero
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
