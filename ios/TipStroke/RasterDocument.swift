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
    private struct Edit {
        var layer: UUID
        var before: [TileKey: TileState]
        var after: [TileKey: TileState]
        var cost: Int { (before.count + after.count) * RasterDocument.tileBytes }
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
        saved.modified = Date()
        for index in saved.layers.indices {
            saved.layers[index].tiles = Array((pixels[saved.layers[index].id] ?? [:]).keys)
                .sorted { ($0.y, $0.x) < ($1.y, $1.x) }
        }
        return DocumentSnapshot(record: saved, pixels: pixels)
    }

    func beginStroke() -> Bool {
        guard strokeLayer == nil,
              let layer = record.layers.first(where: { $0.id == activeLayer }), layer.visible, layer.opacity > 0 else { return false }
        strokeLayer = activeLayer
        before = [:]
        return true
    }

    // Draw only intersecting tiles. The context is temporary; published tiles are immutable.
    func paint(from start: CGPoint, to end: CGPoint, radius: CGFloat, color: UIColor, erasing: Bool) throws -> CGRect {
        guard let layer = strokeLayer else { return .null }
        let area = CGRect(x: min(start.x, end.x), y: min(start.y, end.y),
                          width: abs(end.x - start.x), height: abs(end.y - start.y))
            .insetBy(dx: -radius - 2, dy: -radius - 2).intersection(bounds)
        let keys = TileKey.intersecting(area, width: record.width, height: record.height)
            .filter { !erasing || pixels[layer]?[$0] != nil }
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
            context.setBlendMode(erasing ? .destinationOut : .normal)
            context.setStrokeColor(color.cgColor)
            context.setFillColor(color.cgColor)
            context.setLineWidth(radius * 2)
            context.setLineCap(.round)
            if start == end {
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
        guard strokeLayer == nil, let edit = undoStack.popLast() else { return }
        apply(edit.before, layer: edit.layer); redoStack.append(edit)
    }
    func redo() {
        guard strokeLayer == nil, let edit = redoStack.popLast() else { return }
        apply(edit.after, layer: edit.layer); undoStack.append(edit)
    }
    func clearHistory() { undoStack = []; redoStack = [] }
    private func trimHistory() {
        while undoStack.reduce(0, { $0 + $1.cost }) > Self.historyBudget { undoStack.removeFirst() }
    }
    private func apply(_ states: [TileKey: TileState], layer: UUID) {
        for (key, state) in states { pixels[layer, default: [:]][key] = state.image }
    }
    func addLayer() -> Bool {
        guard record.layers.count < 32 else { return false }
        var layer = LayerRecord(); layer.name = "Layer \(record.layers.count + 1)"
        record.layers.append(layer); record.selectedLayer = layer.id
        return true
    }
    func deleteLayer(_ id: UUID) {
        guard record.layers.count > 1 else { return }
        record.layers.removeAll { $0.id == id }; pixels[id] = nil
        if record.selectedLayer == id { record.selectedLayer = record.layers.last!.id }
        clearHistory()
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
    static func composite(_ snapshot: DocumentSnapshot, in context: CGContext, visible: CGRect) {
        context.saveGState()
        context.clip(to: CGRect(x: 0, y: 0, width: snapshot.record.width, height: snapshot.record.height))
        for layer in snapshot.record.layers where layer.visible && layer.opacity > 0 {
            context.setAlpha(layer.opacity)
            for key in TileKey.intersecting(visible, width: snapshot.record.width, height: snapshot.record.height) {
                if let image = snapshot.pixels[layer.id]?[key] { draw(image, in: context, rect: key.rect) }
            }
        }
        context.restoreGState()
    }
}
