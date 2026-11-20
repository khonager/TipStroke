import UIKit
import TipStrokeShared

// Main-thread owned. Only immutable CGImage snapshots cross onto the persistence queue.
final class RasterDocument {
    static let maximumTiles = 512 // 128 MiB of resident RGBA artwork
    static let historyBudget = 32 * 1024 * 1024
    static let tileBytes = 256 * 256 * 4
    var paintInside = false
    var mirrorCopies = 0
    var mirrorCenter: CGPoint?
    private var strokeMirroring = true
    private var strokePaintInside = false
    private var strokeReferenceID: UUID?
    var mirrorTransforms: [CGAffineTransform] {
        guard strokeMirroring, mirrorCopies >= 2 else { return [.identity] }
        let center = mirrorCenter ?? CGPoint(x: bounds.midX, y: bounds.midY)
        return (0..<mirrorCopies).map { index in
            var t = CGAffineTransform(translationX: center.x, y: center.y)
            if mirrorCopies == 2 { t = t.scaledBy(x: index == 0 ? 1 : -1, y: 1) }
            else { t = t.rotated(by: CGFloat(index) * 2 * .pi / CGFloat(mirrorCopies)) }
            return t.translatedBy(x: -center.x, y: -center.y)
        }
    }
    var selection: CanvasSelection? {
        didSet { if let selection { previousSelection = (selection, bounds.size) } }
    }
    private var previousSelection: (CanvasSelection, CGSize)?
    var selectionOperation: SelectionOperation = .replace
    func restoreSelection() -> Bool {
        guard let (saved, size) = previousSelection, size == bounds.size else { return false }
        selection = saved
        return true
    }
    var record: DocumentRecord
    var framePixels: [UUID: [UUID: [TileKey: CGImage]]] = [:]
    var pixels: [UUID: [TileKey: CGImage]]
    private var before: [TileKey: TileState] = [:]
    private var strokeLayer: UUID?
    private var extraEraseLayers: [UUID] = []
    private var extraBefore: [UUID: [TileKey: TileState]] = [:]
    private var selectionAnchor: UUID?
    private var extraLayers = Set<UUID>()
    var editingLayerIDs: Set<UUID> {
        guard selectionAnchor == activeLayer else { return [activeLayer] }
        return extraLayers.intersection(record.layers.map(\.id)).union([activeLayer])
    }
    func chooseLayers(_ ids: Set<UUID>) { selectionAnchor = activeLayer; extraLayers = ids.subtracting([activeLayer]) }
    func groupSelection() {
        let ids = editingLayerIDs
        guard ids.count > 1 else { return }
        let group = UUID()
        beginMetadataEdit()
        for index in record.layers.indices where ids.contains(record.layers[index].id) { record.layers[index].editGroup = group }
        endMetadataEdit()
    }
    func ungroupSelection() {
        let ids = editingLayerIDs
        beginMetadataEdit()
        for index in record.layers.indices where ids.contains(record.layers[index].id) { record.layers[index].editGroup = nil }
        endMetadataEdit()
    }
    private var heldTileCount: Int { before.count + extraBefore.values.reduce(0) { $0 + $1.count } }
    private var undoStack: [Edit] = []
    private var redoStack: [Edit] = []
    private struct Metadata {
        let record: DocumentRecord
        let pixels: [UUID: [TileKey: CGImage]]
        let frames: [UUID: [UUID: [TileKey: CGImage]]]
    }
    private var metadataBefore: Metadata?
    private struct Edit {
        var layer: UUID
        var frame: UUID? = nil
        var before: [TileKey: TileState]
        var after: [TileKey: TileState]
        var movedSelection: (CanvasSelection, CanvasSelection)? = nil
        var extraBefore: [UUID: [TileKey: TileState]] = [:]
        var extraAfter: [UUID: [TileKey: TileState]] = [:]
        var oldMetadata: Metadata? = nil
        var newMetadata: Metadata? = nil
        var cost: Int {
            func images(_ value: Metadata?) -> Set<ObjectIdentifier> {
                Set(((value?.pixels.values.flatMap { $0.values.map { ObjectIdentifier($0) } }) ?? []) +
                    ((value?.frames.values.flatMap { $0.values.flatMap { $0.values.map { ObjectIdentifier($0) } } }) ?? []))
            }
            // Metadata copies dictionaries and retains immutable images, never copies bitmap bytes.
            let retained = images(oldMetadata).symmetricDifference(images(newMetadata)).count
            let oldSources = oldMetadata?.record.photoSources ?? [:]
            let newSources = newMetadata?.record.photoSources ?? [:]
            let retainedSources = Set(oldSources.keys).symmetricDifference(newSources.keys).reduce(0) {
                $0 + ((oldSources[$1] ?? newSources[$1])?.bytes ?? 0)
            }
            let others = extraBefore.values.reduce(0) { $0 + $1.count } + extraAfter.values.reduce(0) { $0 + $1.count }
            return retainedSources + (before.count + after.count + retained + others) * RasterDocument.tileBytes + (oldMetadata == nil ? 0 : 256)
        }
    }
    private enum TileState {
        case empty
        case image(CGImage)
        var image: CGImage? { if case .image(let image) = self { return image }; return nil }
    }
    private(set) var revision: UInt64 = 0
    var canUndo: Bool { !undoStack.isEmpty }
    var canRedo: Bool { !redoStack.isEmpty }
    var activeLayer: UUID { record.selectedLayer ?? record.layers.last!.id }
    var bounds: CGRect { CGRect(x: 0, y: 0, width: record.width, height: record.height) }

    init(snapshot: DocumentSnapshot) {
        record = snapshot.record
        pixels = snapshot.pixels
        framePixels = snapshot.frames
    }
    convenience init(name: String, width: Int, height: Int) {
        var record = DocumentRecord()
        record.name = name; record.width = width; record.height = height
        record.selectedLayer = record.layers[0].id
        self.init(snapshot: DocumentSnapshot(record: record, pixels: [:]))
    }

    func snapshot() -> DocumentSnapshot {
        var saved = record
        saved.schemaVersion = saved.media != nil || !(saved.animation?.sharedLayerIDs.isEmpty ?? true) ? 6 : (saved.layers.contains(where: { $0.photo != nil || $0.editGroup != nil }) ? 5 : 4)
        saved.modified = Date()
        for index in saved.layers.indices {
            saved.layers[index].tiles = Array((pixels[saved.layers[index].id] ?? [:]).keys)
                .sorted { ($0.y, $0.x) < ($1.y, $1.x) }
        }
        var frames = framePixels
        if var animation = saved.animation {
            let selected = animation.frames[animation.selectedIndex].id
            let hidden = frames[selected]?.filter { animation.sharedLayerIDs.contains($0.key) } ?? [:]
            frames[selected] = hidden.merging(pixels.filter { !animation.sharedLayerIDs.contains($0.key) }) { _, current in current }
            for index in animation.frames.indices {
                animation.frames[index].tiles = (frames[animation.frames[index].id] ?? [:]).reduce(into: [:]) { result, layer in
                    result[layer.key.uuidString] = Array(layer.value.keys).sorted { ($0.y, $0.x) < ($1.y, $1.x) }
                }
            }
            saved.animation = animation
        }
        return DocumentSnapshot(record: saved, pixels: pixels, frames: frames)
    }

    func beginStroke(mirrored: Bool = true, brushAids: Bool = true, photoErase: Bool = false) -> Bool {
        guard strokeLayer == nil, metadataBefore == nil,
              let layer = record.layers.first(where: { $0.id == activeLayer }), layer.visible, !layer.locked, layer.opacity > 0, layer.photo == nil || photoErase else { return false }
        strokePaintInside = paintInside && brushAids
        let paintingColorHints = record.layers.contains { candidate in
            guard let text = candidate.colorizeMask, let recipe = try? NativeColorizeRecipe.companion.decode(text: text) else { return false }
            return recipe.hintsId == activeLayer.uuidString
        }
        strokeReferenceID = strokePaintInside || paintingColorHints ? nil : record.layers.first { $0.reference }?.id
        strokeLayer = activeLayer; strokeMirroring = mirrored
        let selected = editingLayerIDs
        extraEraseLayers = photoErase ? record.layers.filter {
            $0.id != activeLayer && selected.contains($0.id) && $0.visible && !$0.locked && $0.opacity > 0
        }.map(\.id) : []
        extraBefore = [:]
        before = [:]
        return true
    }

    // Draw only intersecting tiles. The context is temporary; published tiles are immutable.
    func paint(from start: CGPoint, to end: CGPoint, radius: CGFloat, color: UIColor, erasing: Bool, softness: CGFloat = 0) throws -> CGRect {
        guard let layer = strokeLayer else { return .null }
        let area = CGRect(x: min(start.x, end.x), y: min(start.y, end.y),
                          width: abs(end.x - start.x), height: abs(end.y - start.y))
            .insetBy(dx: -radius - 2, dy: -radius - 2).intersection(bounds)
        let alphaLocked = strokePaintInside || record.layers.first { $0.id == layer }?.alphaLocked == true
        if alphaLocked && erasing && extraEraseLayers.isEmpty { return .null }
        let keys = TileKey.intersecting(area, width: record.width, height: record.height)
        try editTiles(keys: keys, erasing: erasing) { context in
            context.setStrokeColor(color.cgColor)
            context.setFillColor(color.cgColor)
            context.setLineWidth(radius * 2)
            context.setLineCap(.round)
            if softness > 0 {
                let distance = hypot(end.x - start.x, end.y - start.y)
                let steps = max(1, Int(ceil(distance / max(1, radius * 0.25))))
                let colors = [color.withAlphaComponent(color.cgColor.alpha * 0.18).cgColor,
                              color.withAlphaComponent(0).cgColor] as CFArray
                let locations: [CGFloat] = [max(0, 1 - softness), 1]
                if let gradient = CGGradient(colorsSpace: CGColorSpace(name: CGColorSpace.sRGB), colors: colors, locations: locations) {
                    for step in 1...steps {
                        let fraction = CGFloat(step) / CGFloat(steps)
                        let point = CGPoint(x: start.x + (end.x - start.x) * fraction, y: start.y + (end.y - start.y) * fraction)
                        context.drawRadialGradient(gradient, startCenter: point, startRadius: 0,
                                                   endCenter: point, endRadius: radius, options: [.drawsBeforeStartLocation])
                    }
                }
            } else if start == end {
                context.fillEllipse(in: CGRect(x: end.x - radius, y: end.y - radius, width: radius * 2, height: radius * 2))
            } else {
                context.move(to: start); context.addLine(to: end); context.strokePath()
            }
        }
        return area
    }

    func editTiles(in area: CGRect, erasing: Bool = false, draw: (CGContext) throws -> Void) throws {
        let keys = TileKey.intersecting(area, width: record.width, height: record.height)
        try editTiles(keys: keys, erasing: erasing, draw: draw)
    }
    func editTiles(keys: [TileKey], erasing: Bool, draw: (CGContext) throws -> Void) throws {
        guard let primary = strokeLayer else { return }
        try editTarget(keys: keys, erasing: erasing, draw: draw)
        if erasing {
            for id in extraEraseLayers {
                extraBefore[primary] = before
                before = extraBefore.removeValue(forKey: id) ?? [:]
                strokeLayer = id
                defer {
                    extraBefore[id] = before
                    before = extraBefore.removeValue(forKey: primary) ?? [:]
                    strokeLayer = primary
                }
                try editTarget(keys: keys, erasing: true, draw: draw)
            }
        }
    }
    private func editTarget(keys: [TileKey], erasing: Bool, draw: (CGContext) throws -> Void) throws {
        guard let layer = strokeLayer else { return }
        let referenceID = strokeReferenceID
        let alphaLocked = strokePaintInside || record.layers.first { $0.id == layer }?.alphaLocked == true || referenceID == layer
        if erasing && alphaLocked { return }
        if let photo = record.layers.first(where: { $0.id == layer })?.photo {
            guard erasing, referenceID == nil else { return }
            try erasePhotoMask(photo, layer: layer, affected: keys, draw: draw)
            return
        }
        let transforms = mirrorTransforms
        let expanded = Set(keys.flatMap { key in transforms.flatMap { TileKey.intersecting(key.rect.applying($0), width: record.width, height: record.height) } })
        let keys = expanded.filter { key in
            ((!erasing && !alphaLocked) || pixels[layer]?[key] != nil) &&
                (referenceID == nil || pixels[referenceID!]?[key] != nil || record.layers.first(where: { $0.id == referenceID })?.photo?.bounds.intersects(key.rect) == true)
        }
        let newKeys = keys.filter { before[$0] == nil }
        guard (heldTileCount + newKeys.count) * Self.tileBytes * 2 <= Self.historyBudget else {
            throw DrawingError.message("This edit exceeds the undo budget. Use a smaller selected area.")
        }
        guard residentTileCount + keys.filter({ pixels[layer]?[$0] == nil }).count <= Self.maximumTiles else {
            throw DrawingError.message("This edit exceeds the painting memory budget.")
        }
        for key in keys {
            if before[key] == nil { before[key] = pixels[layer]?[key].map(TileState.image) ?? .empty }
            let context = try Self.context(width: 256, height: 256)
            if let image = pixels[layer]?[key] { Self.draw(image, in: context, rect: CGRect(x: 0, y: 0, width: 256, height: 256)) }
            context.translateBy(x: -key.rect.minX, y: -key.rect.minY); context.clip(to: bounds)
            selection?.clip(context, bounds: bounds)
            if let referenceID, referenceID != layer {
                let snapshot = DocumentSnapshot(record: record, pixels: pixels)
                guard let mask = try Self.layerTile(snapshot, id: referenceID, key: key) else { continue }
                let transform = CGAffineTransform(translationX: key.rect.minX, y: key.rect.maxY).scaledBy(x: 1, y: -1)
                context.concatenate(transform)
                context.clip(to: CGRect(x: 0, y: 0, width: 256, height: 256), mask: mask)
                context.concatenate(transform.inverted())
            }
            context.setBlendMode(alphaLocked ? .sourceAtop : (erasing ? .destinationOut : .normal))
            context.setFillColor(UIColor.black.cgColor)
            for transform in transforms {
                context.saveGState(); context.concatenate(transform)
                try draw(context); context.restoreGState()
            }
            guard let image = context.makeImage() else { throw DrawingError.message("Could not allocate a painting tile.") }
            pixels[layer, default: [:]][key] = image
        }
    }
    private func erasePhotoMask(_ photo: PhotoLayerRecord, layer: UUID, affected: [TileKey], draw: (CGContext) throws -> Void) throws {
        let keys = Set(affected.flatMap { key in mirrorTransforms.flatMap { mirror in
            photo.geometry.sourceKeys(over: key.rect.applying(mirror), width: photo.width, height: photo.height)
        } })
        guard (heldTileCount + keys.filter({ before[$0] == nil }).count) * Self.tileBytes * 2 <= Self.historyBudget,
              residentTileCount + keys.filter({ pixels[layer]?[$0] == nil }).count <= Self.maximumTiles else {
            throw DrawingError.message("This erase exceeds the image editing budget.")
        }
        for key in keys {
            let old = pixels[layer]?[key]
            if before[key] == nil { before[key] = old.map(TileState.image) ?? .empty }
            let context = try Self.context(width: 256, height: 256)
            if let old { Self.draw(old, in: context, rect: CGRect(origin: .zero, size: key.rect.size)) }
            context.translateBy(x: -key.rect.minX, y: -key.rect.minY)
            context.clip(to: photo.sourceBounds)
            let region = affected.reduce(CGRect.null) { $0.union($1.rect) }
            for mirror in mirrorTransforms {
                try photo.geometry.pieces(in: region.applying(mirror), source: key.rect) { transform, clip, _ in
                    context.saveGState()
                    defer { context.restoreGState() }
                    if let clip { context.setShouldAntialias(false); context.addPath(clip); context.clip() }
                    context.concatenate(transform.inverted())
                    context.clip(to: bounds); selection?.clip(context, bounds: bounds)
                    context.concatenate(mirror)
                    try draw(context)
                }
            }
            guard let mask = context.makeImage() else { throw DrawingError.message("Could not render the erase mask.") }
            pixels[layer, default: [:]][key] = mask
        }
    }
    func replaceTiles(_ tiles: [TileKey: CGImage]) throws {
        guard beginStroke(mirrored: false) else { return }
        do {
            for (key, image) in tiles {
                try editTiles(in: key.rect) { context in
                    context.setBlendMode(.copy); Self.draw(image, in: context, rect: key.rect)
                }
            }
            endStroke(cancelled: false)
        } catch { endStroke(cancelled: true); throw error }
    }

    // Worker outputs already include selection/constraints. Publish the exact preview atomically.
    func replacePreparedTiles(_ tiles: [TileKey: CGImage], movedSelection: (CanvasSelection, CanvasSelection)? = nil) throws {
        guard tiles.count * Self.tileBytes * 2 <= Self.historyBudget,
              residentTileCount + tiles.keys.filter({ pixels[activeLayer]?[$0] == nil }).count <= Self.maximumTiles else {
            throw DrawingError.message("This effect exceeds the painting or undo budget.")
        }
        guard beginStroke(mirrored: false) else { throw DrawingError.message("The active layer cannot be edited.") }
        for (key, image) in tiles {
            before[key] = pixels[activeLayer]?[key].map(TileState.image) ?? .empty
            pixels[activeLayer, default: [:]][key] = image
        }
        endStroke(cancelled: false, movedSelection: movedSelection)
    }

    // Restore only tiles reached by this provisional stroke; retain their original undo states.
    var strokePreviewBounds: CGRect { before.keys.reduce(CGRect.null) { $0.union($1.rect) } }
    func resetStrokePreview() {
        guard let layer = strokeLayer else { return }
        apply(before, layer: layer)
        for (id, states) in extraBefore { apply(states, layer: id) }
    }

    func endStroke(cancelled: Bool, movedSelection: (CanvasSelection, CanvasSelection)? = nil) {
        guard let layer = strokeLayer else { return }
        if cancelled { resetStrokePreview() }
        else if heldTileCount > 0 {
            revision &+= 1
            let after = before.keys.reduce(into: [TileKey: TileState]()) { $0[$1] = pixels[layer]?[$1].map(TileState.image) ?? .empty }
            let extraAfter = extraBefore.reduce(into: [UUID: [TileKey: TileState]]()) { result, item in
                result[item.key] = item.value.keys.reduce(into: [:]) { delta, key in delta[key] = pixels[item.key]?[key].map(TileState.image) ?? .empty }
            }
            redoStack = []
            undoStack.append(Edit(layer: layer, frame: record.animation.flatMap { $0.sharedLayerIDs.contains(layer) && extraBefore.keys.allSatisfy($0.sharedLayerIDs.contains) ? nil : $0.frames[$0.selectedIndex].id }, before: before, after: after, movedSelection: movedSelection, extraBefore: extraBefore, extraAfter: extraAfter))
            if let movedSelection { selection = movedSelection.1 }
            trimHistory()
        }
        before = [:]; strokeLayer = nil; extraBefore = [:]; extraEraseLayers = []
    }

    func undo() {
        guard strokeLayer == nil, metadataBefore == nil, let edit = undoStack.popLast() else { return }
        revision &+= 1
        if let state = edit.oldMetadata { record = state.record; pixels = state.pixels; framePixels = state.frames }
        else { restoreFrameForHistory(edit.frame); apply(edit.before, layer: edit.layer) }
        for (id, states) in edit.extraBefore { apply(states, layer: id) }
        if let moved = edit.movedSelection { selection = moved.0 }
        redoStack.append(edit)
    }
    func redo() {
        guard strokeLayer == nil, metadataBefore == nil, let edit = redoStack.popLast() else { return }
        revision &+= 1
        if let state = edit.newMetadata { record = state.record; pixels = state.pixels; framePixels = state.frames }
        else { restoreFrameForHistory(edit.frame); apply(edit.after, layer: edit.layer) }
        for (id, states) in edit.extraAfter { apply(states, layer: id) }
        if let moved = edit.movedSelection { selection = moved.1 }
        undoStack.append(edit)
    }
    func clearHistory() { undoStack = []; redoStack = [] }
    private func trimHistory() {
        while !undoStack.isEmpty && (undoStack.reduce(0, { $0 + $1.cost }) > Self.historyBudget || undoStack.count > 128) { undoStack.removeFirst() }
    }
    private func apply(_ states: [TileKey: TileState], layer: UUID) {
        for (key, state) in states { pixels[layer, default: [:]][key] = state.image }
    }
    func addLayer() -> Bool {
        guard record.layers.count < 32 else { return false }
        beginMetadataEdit()
        var layer = LayerRecord(); layer.name = "Layer \(record.layers.count + 1)"
        record.layers.append(layer); record.selectedLayer = layer.id
        endMetadataEdit()
        return true
    }
    func deleteLayer(_ id: UUID) throws {
        guard record.layers.count > 1, record.layers.contains(where: { $0.id == id }) else { return }
        guard ((pixels[id]?.count ?? 0) + framePixels.values.reduce(0) { $0 + ($1[id]?.count ?? 0) }) * Self.tileBytes + 256 <= Self.historyBudget else {
            throw DrawingError.message("This layer is too large to retain in undo history. Its pixels have been preserved.")
        }
        beginMetadataEdit()
        record.animation?.backgroundLayers?.remove(id)
        record.layers.removeAll { $0.id == id }; pixels[id] = nil
        for index in record.layers.indices where record.layers[index].clipSource == id { record.layers[index].clipSource = nil }
        for frame in Array(framePixels.keys) { framePixels[frame]?[id] = nil }
        if record.selectedLayer == id { record.selectedLayer = record.layers.last!.id }
        endMetadataEdit()
    }

    func beginMetadataEdit() {
        guard metadataBefore == nil, strokeLayer == nil else { return }
        metadataBefore = Metadata(record: record, pixels: pixels, frames: framePixels)
    }
    func endMetadataEdit(force: Bool = false) {
        guard let old = metadataBefore else { return }
        metadataBefore = nil
        guard force || old.record != record else { return }
        revision &+= 1
        redoStack = []
        undoStack.append(Edit(layer: activeLayer, before: [:], after: [:], oldMetadata: old,
                              newMetadata: Metadata(record: record, pixels: pixels, frames: framePixels)))
        trimHistory()
    }
    func duplicateLayer(_ id: UUID) throws {
        guard record.layers.count < 32, let index = record.layers.firstIndex(where: { $0.id == id }) else { return }
        let tiles = pixels[id] ?? [:]
        let otherCopies = framePixels.values.reduce(0) { $0 + ($1[id]?.count ?? 0) }
        guard residentTileCount + tiles.count + otherCopies <= Self.maximumTiles else {
            throw DrawingError.message("Duplicating this layer would exceed the painting memory budget.")
        }
        beginMetadataEdit()
        var copy = record.layers[index]; copy.id = UUID(); copy.name += " copy"
        copy.locked = false; copy.reference = false
        pixels[copy.id] = tiles // immutable images; new paint replaces only touched tiles
        for frame in Array(framePixels.keys) { framePixels[frame]?[copy.id] = framePixels[frame]?[id] }
        record.layers.insert(copy, at: index + 1); record.selectedLayer = copy.id
        endMetadataEdit()
    }
    func importTiles(_ tiles: [TileKey: CGImage], name: String) throws {
        guard record.layers.count < 32,
              tiles.count * Self.tileBytes + 256 <= Self.historyBudget,
              residentTileCount + tiles.count <= Self.maximumTiles else {
            throw DrawingError.message("This image exceeds the layer, undo or painting memory budget. Use a smaller canvas.")
        }
        beginMetadataEdit()
        var layer = LayerRecord(); layer.name = name
        pixels[layer.id] = tiles
        record.layers.append(layer); record.selectedLayer = layer.id
        endMetadataEdit()
    }
    static func imageTiles(_ image: CGImage, width: Int, height: Int) throws -> [TileKey: CGImage] {
        let scale = min(CGFloat(width) / CGFloat(image.width), CGFloat(height) / CGFloat(image.height))
        let size = CGSize(width: CGFloat(image.width) * scale, height: CGFloat(image.height) * scale)
        let rect = CGRect(x: (CGFloat(width) - size.width) / 2, y: (CGFloat(height) - size.height) / 2,
                          width: size.width, height: size.height)
        let keys = TileKey.intersecting(rect, width: width, height: height)
        guard keys.count * Self.tileBytes + 256 <= Self.historyBudget else {
            throw DrawingError.message("This image is too large for undo at this canvas size. Use a smaller canvas.")
        }
        var result: [TileKey: CGImage] = [:]
        for key in keys {
            let context = try Self.context(width: 256, height: 256)
            context.translateBy(x: -key.rect.minX, y: -key.rect.minY)
            Self.draw(image, in: context, rect: rect)
            guard let tile = context.makeImage() else { throw DrawingError.message("Could not import the image.") }
            result[key] = tile
        }
        return result
    }
    func color(at point: CGPoint, mediaPreview: CGImage? = nil) throws -> UIColor? {
        guard bounds.contains(point) else { return nil }
        let x = floor(point.x), y = floor(point.y)
        let context = try Self.context(width: 1, height: 1)
        context.setFillColor(UIColor.white.cgColor); context.fill(CGRect(x: 0, y: 0, width: 1, height: 1))
        context.setFillColor(record.paperColor.cgColor); context.fill(CGRect(x: 0, y: 0, width: 1, height: 1))
        context.translateBy(x: -x, y: -y)
        if let mediaPreview { Self.draw(mediaPreview, in: context, rect: bounds) }
        try Self.composite(DocumentSnapshot(record: record, pixels: pixels), in: context, visible: CGRect(x: x, y: y, width: 1, height: 1))
        guard let bytes = context.data?.assumingMemoryBound(to: UInt8.self) else { return nil }
        return UIColor(red: CGFloat(bytes[0]) / 255, green: CGFloat(bytes[1]) / 255, blue: CGFloat(bytes[2]) / 255, alpha: 1)
    }
    static let blendModes: [String: CGBlendMode] = [
        "NORMAL": .normal, "MULTIPLY": .multiply, "DARKEN": .darken, "COLOR_BURN": .colorBurn,
        "LIGHTEN": .lighten, "SCREEN": .screen, "COLOR_DODGE": .colorDodge, "ADD": .plusLighter,
        "OVERLAY": .overlay, "SOFT_LIGHT": .softLight, "HARD_LIGHT": .hardLight,
        "DIFFERENCE": .difference, "EXCLUSION": .exclusion, "HUE": .hue,
        "SATURATION": .saturation, "COLOR": .color, "LUMINOSITY": .luminosity
    ]

    func installReduction(_ result: DocumentSnapshot, expectedRevision: UInt64) throws {
        guard revision == expectedRevision, strokeLayer == nil, metadataBefore == nil,
              record.selectedLayer == result.record.selectedLayer,
              record.animation?.selectedIndex == result.record.animation?.selectedIndex else {
            throw DrawingError.message("The drawing changed during resizing. Try again.")
        }
        record = result.record; pixels = result.pixels; framePixels = result.frames
        selection = nil; mirrorCenter = nil
        clearHistory(); revision &+= 1
    }

    func resize(width: Int, height: Int) throws {
        guard (16...4096).contains(width), (16...4096).contains(height) else { return }
        let bounds = CGRect(x: 0, y: 0, width: width, height: height)
        var changedTiles = 0
        func cropped(_ original: [UUID: [TileKey: CGImage]]) throws -> [UUID: [TileKey: CGImage]] {
            var replacement = original
            for (id, tiles) in original where record.layers.first(where: { $0.id == id })?.photo == nil {
                for (key, image) in tiles where !bounds.contains(key.rect) {
                    changedTiles += 1
                    guard changedTiles * Self.tileBytes * 2 + 256 <= Self.historyBudget else { throw DrawingError.message("This crop exceeds the undo budget.") }
                    if !bounds.intersects(key.rect) { replacement[id]?[key] = nil }
                    else {
                        let context = try Self.context(width: 256, height: 256)
                        context.translateBy(x: -key.rect.minX, y: -key.rect.minY); context.clip(to: bounds)
                        Self.draw(image, in: context, rect: key.rect)
                        guard let tile = context.makeImage() else { throw DrawingError.message("Could not crop this tile.") }
                        replacement[id]?[key] = tile
                    }
                }
            }
            return replacement
        }
        let replacement = try cropped(pixels)
        var frames = framePixels
        let selected = record.animation.map { $0.frames[$0.selectedIndex].id }
        for id in Array(frames.keys) where id != selected { frames[id] = try cropped(frames[id] ?? [:]) }
        if let selected { frames[selected] = replacement }
        beginMetadataEdit(); pixels = replacement; framePixels = frames
        record.width = width; record.height = height; selection = nil; previousSelection = nil; endMetadataEdit()
    }

    static func context(width: Int, height: Int) throws -> CGContext {
        guard let context = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8,
                                      bytesPerRow: width * 4, space: CGColorSpace(name: CGColorSpace.sRGB)!,
                                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else {
            throw DrawingError.message("There is not enough memory for this image.")
        }
        context.translateBy(x: 0, y: CGFloat(height)); context.scaleBy(x: 1, y: -1)
        return context
    }
    // CGImage uses Quartz image coordinates; keep document and UIKit coordinates top-left.
    static func draw(_ image: CGImage, in context: CGContext, rect: CGRect) {
        context.saveGState()
        context.translateBy(x: rect.minX, y: rect.maxY); context.scaleBy(x: 1, y: -1)
        context.draw(image, in: CGRect(origin: .zero, size: rect.size))
        context.restoreGState()
    }
    static func layerTile(_ snapshot: DocumentSnapshot, id: UUID, key: TileKey, depth: Int = 0) throws -> CGImage? {
        guard depth < 32, let layer = snapshot.record.layers.first(where: { $0.id == id }), layer.visible else { return nil }
        let image: CGImage?
        if let photo = layer.photo { image = try photo.tile(key, masks: snapshot.pixels[id] ?? [:]) }
        else { image = snapshot.pixels[id]?[key] }
        guard let image else { return nil }
        guard let source = layer.clipSource else { return image }
        guard let mask = try layerTile(snapshot, id: source, key: key, depth: depth + 1) else { return nil }
        let context = try Self.context(width: 256, height: 256)
        let rect = CGRect(x: 0, y: 0, width: 256, height: 256)
        draw(image, in: context, rect: rect); context.setBlendMode(.destinationIn)
        context.setAlpha(snapshot.record.layers.first(where: { $0.id == source })?.opacity ?? 1)
        draw(mask, in: context, rect: rect); return context.makeImage()
    }
    static func composite(_ snapshot: DocumentSnapshot, in context: CGContext, visible: CGRect) throws {
        context.saveGState()
        defer { context.restoreGState() }
        context.clip(to: CGRect(x: 0, y: 0, width: snapshot.record.width, height: snapshot.record.height))
        let gap = snapshot.record.media.map { media in snapshot.record.animation?.coverageIndex(at: media.positionUs) == nil } ?? false
        for layer in snapshot.record.layers where layer.visible && layer.opacity > 0 && (!gap || snapshot.record.animation?.sharedLayerIDs.contains(layer.id) == true) {
            context.setAlpha(layer.opacity); context.setBlendMode(blendModes[layer.blendMode] ?? .normal)
            for key in TileKey.intersecting(visible, width: snapshot.record.width, height: snapshot.record.height) {
                if let image = try layerTile(snapshot, id: layer.id, key: key) { draw(image, in: context, rect: key.rect) }
            }
        }
    }
    var residentTileCount: Int {
        let selectedFrame = record.animation.map { $0.frames[$0.selectedIndex].id }
        let photoTiles = record.photoSources.values.reduce(0) { $0 + ($1.bytes + Self.tileBytes - 1) / Self.tileBytes }
        return photoTiles + pixels.values.reduce(0) { $0 + $1.count } + framePixels.filter { $0.key != selectedFrame }.values.reduce(0) { count, layers in
            count + layers.values.reduce(0) { $0 + $1.count }
        }
    }
    func mergeDown(_ id: UUID) throws {
        guard let index = record.layers.firstIndex(where: { $0.id == id }), index > 0, record.animation == nil else { return }
        let lower = record.layers[index - 1]
        guard !lower.locked else { throw DrawingError.message("The lower layer is locked.") }
        let snapshot = snapshot()
        func renderedKeys(_ layer: LayerRecord) -> Set<TileKey> {
            if let photo = layer.photo { return Set(TileKey.intersecting(photo.bounds, width: record.width, height: record.height)) }
            return Set((pixels[layer.id] ?? [:]).keys)
        }
        let keys = renderedKeys(record.layers[index]).union(renderedKeys(lower))
        guard keys.count * Self.tileBytes * 3 + 256 <= Self.historyBudget else { throw DrawingError.message("Merge exceeds the undo budget.") }
        var merged: [TileKey: CGImage] = [:]
        for key in keys {
            let context = try Self.context(width: 256, height: 256)
            for layer in [lower, record.layers[index]] where layer.visible {
                if let image = try Self.layerTile(snapshot, id: layer.id, key: key) {
                    context.setAlpha(layer.opacity); context.setBlendMode(Self.blendModes[layer.blendMode] ?? .normal)
                    Self.draw(image, in: context, rect: CGRect(x: 0, y: 0, width: 256, height: 256))
                }
            }
            merged[key] = context.makeImage()
        }
        beginMetadataEdit()
        pixels[lower.id] = merged; pixels[id] = nil
        record.layers.remove(at: index); record.layers[index - 1].opacity = 1; record.layers[index - 1].blendMode = "NORMAL"
        record.layers[index - 1].photo = nil
        record.layers[index - 1].clipSource = nil; record.selectedLayer = lower.id
        for i in record.layers.indices where record.layers[i].clipSource == id { record.layers[i].clipSource = lower.id }
        endMetadataEdit()
    }
}
