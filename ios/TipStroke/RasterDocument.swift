import UIKit

// Main-thread owned. Only immutable CGImage snapshots cross onto the persistence queue.
final class RasterDocument {
    static let maximumTiles = 512 // 128 MiB of resident RGBA artwork
    static let historyBudget = 32 * 1024 * 1024
    static let tileBytes = 256 * 256 * 4
    var record: DocumentRecord
    private(set) var pixels: [UUID: [TileKey: CGImage]]
    private var before: [TileKey: TileState] = [:]
    private var strokeLayer: UUID?
    private var undoStack: [Edit] = []
    private var redoStack: [Edit] = []
    private struct Metadata {
        let record: DocumentRecord
        let pixels: [UUID: [TileKey: CGImage]]
    }
    private var metadataBefore: Metadata?
    private struct Edit {
        var layer: UUID
        var before: [TileKey: TileState]
        var after: [TileKey: TileState]
        var oldMetadata: Metadata? = nil
        var newMetadata: Metadata? = nil
        var cost: Int {
            func images(_ value: Metadata?) -> Set<ObjectIdentifier> {
                Set((value?.pixels.values.flatMap { $0.values.map { ObjectIdentifier($0) } }) ?? [])
            }
            // Metadata copies dictionaries and retains immutable images, never copies bitmap bytes.
            let retained = images(oldMetadata).symmetricDifference(images(newMetadata)).count
            return (before.count + after.count + retained) * RasterDocument.tileBytes + (oldMetadata == nil ? 0 : 256)
        }
    }
    private enum TileState {
        case empty
        case image(CGImage)
        var image: CGImage? { if case .image(let image) = self { return image }; return nil }
    }
    var canUndo: Bool { !undoStack.isEmpty }
    var canRedo: Bool { !redoStack.isEmpty }
    var activeLayer: UUID { record.selectedLayer ?? record.layers.last!.id }
    var bounds: CGRect { CGRect(x: 0, y: 0, width: record.width, height: record.height) }

    init(snapshot: DocumentSnapshot) {
        record = snapshot.record
        pixels = snapshot.pixels
    }
    convenience init(name: String, width: Int, height: Int) {
        var record = DocumentRecord()
        record.name = name; record.width = width; record.height = height
        record.selectedLayer = record.layers[0].id
        self.init(snapshot: DocumentSnapshot(record: record, pixels: [:]))
    }

    func snapshot() -> DocumentSnapshot {
        var saved = record
        saved.schemaVersion = 2
        saved.modified = Date()
        for index in saved.layers.indices {
            saved.layers[index].tiles = Array((pixels[saved.layers[index].id] ?? [:]).keys)
                .sorted { ($0.y, $0.x) < ($1.y, $1.x) }
        }
        return DocumentSnapshot(record: saved, pixels: pixels)
    }

    func beginStroke() -> Bool {
        guard strokeLayer == nil, metadataBefore == nil,
              let layer = record.layers.first(where: { $0.id == activeLayer }), layer.visible, !layer.locked, layer.opacity > 0 else { return false }
        strokeLayer = activeLayer
        before = [:]
        return true
    }

    // Draw only intersecting tiles. The context is temporary; published tiles are immutable.
    func paint(from start: CGPoint, to end: CGPoint, radius: CGFloat, color: UIColor, erasing: Bool, softness: CGFloat = 0) throws -> CGRect {
        guard let layer = strokeLayer else { return .null }
        let area = CGRect(x: min(start.x, end.x), y: min(start.y, end.y),
                          width: abs(end.x - start.x), height: abs(end.y - start.y))
            .insetBy(dx: -radius - 2, dy: -radius - 2).intersection(bounds)
        let alphaLocked = record.layers.first { $0.id == layer }?.alphaLocked == true
        if alphaLocked && erasing { return .null }
        let keys = TileKey.intersecting(area, width: record.width, height: record.height)
            .filter { (!erasing && !alphaLocked) || pixels[layer]?[$0] != nil }
        let newKeys = keys.filter { before[$0] == nil }
        guard (before.count + newKeys.count) * Self.tileBytes * 2 <= Self.historyBudget else {
            throw DrawingError.message("This stroke reached the undo memory limit. Try shorter strokes.")
        }
        let allocated = pixels.values.reduce(0) { $0 + $1.count }
        let added = keys.filter { pixels[layer]?[$0] == nil }.count
        guard allocated + added <= Self.maximumTiles else {
            throw DrawingError.message("This drawing reached its 128 MiB painting limit. Delete unused layers or start a smaller canvas.")
        }
        for key in keys {
            if before[key] == nil { before[key] = pixels[layer]?[key].map(TileState.image) ?? .empty }
            let context = try Self.context(width: 256, height: 256)
            if let previous = pixels[layer]?[key] { Self.draw(previous, in: context, rect: CGRect(x: 0, y: 0, width: 256, height: 256)) }
            context.translateBy(x: -key.rect.minX, y: -key.rect.minY)
            context.clip(to: bounds)
            context.setBlendMode(alphaLocked ? .sourceAtop : (erasing ? .destinationOut : .normal))
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
            guard let image = context.makeImage() else { throw DrawingError.message("Unable to allocate a painting tile.") }
            pixels[layer, default: [:]][key] = image
        }
        return area
    }

    func endStroke(cancelled: Bool) {
        guard let layer = strokeLayer else { return }
        if cancelled { apply(before, layer: layer) }
        else if !before.isEmpty {
            let after = before.keys.reduce(into: [TileKey: TileState]()) { $0[$1] = pixels[layer]?[$1].map(TileState.image) ?? .empty }
            redoStack = []
            undoStack.append(Edit(layer: layer, before: before, after: after))
            trimHistory()
        }
        before = [:]; strokeLayer = nil
    }

    func undo() {
        guard strokeLayer == nil, metadataBefore == nil, let edit = undoStack.popLast() else { return }
        if let state = edit.oldMetadata { record = state.record; pixels = state.pixels }
        else { apply(edit.before, layer: edit.layer) }
        redoStack.append(edit)
    }
    func redo() {
        guard strokeLayer == nil, metadataBefore == nil, let edit = redoStack.popLast() else { return }
        if let state = edit.newMetadata { record = state.record; pixels = state.pixels }
        else { apply(edit.after, layer: edit.layer) }
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
        guard (pixels[id]?.count ?? 0) * Self.tileBytes + 256 <= Self.historyBudget else {
            throw DrawingError.message("This layer is too large to retain in undo history. Its pixels have been preserved.")
        }
        beginMetadataEdit()
        record.layers.removeAll { $0.id == id }; pixels[id] = nil
        if record.selectedLayer == id { record.selectedLayer = record.layers.last!.id }
        endMetadataEdit()
    }

    func beginMetadataEdit() {
        guard metadataBefore == nil, strokeLayer == nil else { return }
        metadataBefore = Metadata(record: record, pixels: pixels)
    }
    func endMetadataEdit() {
        guard let old = metadataBefore else { return }
        metadataBefore = nil
        guard old.record != record else { return }
        redoStack = []
        undoStack.append(Edit(layer: activeLayer, before: [:], after: [:], oldMetadata: old,
                              newMetadata: Metadata(record: record, pixels: pixels)))
        trimHistory()
    }
    func duplicateLayer(_ id: UUID) throws {
        guard record.layers.count < 32, let index = record.layers.firstIndex(where: { $0.id == id }) else { return }
        let tiles = pixels[id] ?? [:]
        guard pixels.values.reduce(0, { $0 + $1.count }) + tiles.count <= Self.maximumTiles else {
            throw DrawingError.message("Duplicating this layer would exceed the painting memory budget.")
        }
        beginMetadataEdit()
        var copy = record.layers[index]; copy.id = UUID(); copy.name += " copy"
        copy.locked = false
        pixels[copy.id] = tiles // immutable images; new paint replaces only touched tiles
        record.layers.insert(copy, at: index + 1); record.selectedLayer = copy.id
        endMetadataEdit()
    }
    func importTiles(_ tiles: [TileKey: CGImage], name: String) throws {
        guard record.layers.count < 32,
              tiles.count * Self.tileBytes + 256 <= Self.historyBudget,
              pixels.values.reduce(0, { $0 + $1.count }) + tiles.count <= Self.maximumTiles else {
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
    func color(at point: CGPoint) throws -> UIColor? {
        guard bounds.contains(point) else { return nil }
        let x = floor(point.x), y = floor(point.y)
        let context = try Self.context(width: 1, height: 1)
        context.setFillColor(UIColor.white.cgColor); context.fill(CGRect(x: 0, y: 0, width: 1, height: 1))
        context.translateBy(x: -x, y: -y)
        Self.composite(snapshot(), in: context, visible: CGRect(x: x, y: y, width: 1, height: 1))
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
    static func composite(_ snapshot: DocumentSnapshot, in context: CGContext, visible: CGRect) {
        context.saveGState()
        context.clip(to: CGRect(x: 0, y: 0, width: snapshot.record.width, height: snapshot.record.height))
        for layer in snapshot.record.layers where layer.visible && layer.opacity > 0 {
            context.setAlpha(layer.opacity)
            context.setBlendMode(blendModes[layer.blendMode] ?? .normal)
            for key in TileKey.intersecting(visible, width: snapshot.record.width, height: snapshot.record.height) {
                if let image = snapshot.pixels[layer.id]?[key] { draw(image, in: context, rect: key.rect) }
            }
        }
        context.restoreGState()
    }
}
