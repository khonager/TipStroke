import UIKit
import ImageIO
import UniformTypeIdentifiers

/// Immutable pixels shared by layer copies, previews, and undo records.
final class PhotoSource {
    // Source coordinates and erase masks stay at original resolution. The editor's
    // disposable image is EXIF-oriented and bounded independently of the canvas.
    static let maximumDimension = 65_535
    let width: Int
    let height: Int
    let image: CGImage
    let encoded: Data

    init(image: CGImage) throws {
        let buffer = NSMutableData()
        guard let writer = CGImageDestinationCreateWithData(buffer as CFMutableData, UTType.png.identifier as CFString, 1, nil) else {
            throw DrawingError.message("Could not prepare the imported photo.")
        }
        CGImageDestinationAddImage(writer, image, nil)
        guard CGImageDestinationFinalize(writer) else { throw DrawingError.message("Could not encode the photo.") }
        self.image = image; self.encoded = buffer as Data
        width = image.width; height = image.height
    }

    init(file: URL, width expectedWidth: Int? = nil, height expectedHeight: Int? = nil) throws {
        let size = try file.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
        guard size > 0, size < RasterDocument.historyBudget - 65_536 else {
            throw DrawingError.message("The original photo exceeds the 32 MiB image import budget.")
        }
        let data = try Data(contentsOf: file, options: .mappedIfSafe)
        guard let reader = CGImageSourceCreateWithData(data as CFData, nil),
              let props = CGImageSourceCopyPropertiesAtIndex(reader, 0, nil) as? [CFString: Any],
              let w = props[kCGImagePropertyPixelWidth] as? Int, let h = props[kCGImagePropertyPixelHeight] as? Int,
              (1...Self.maximumDimension).contains(w), (1...Self.maximumDimension).contains(h),
              expectedWidth.map({ $0 == w }) ?? true, expectedHeight.map({ $0 == h }) ?? true else {
            throw DrawingError.message("The photo's source dimensions are unsupported or damaged.")
        }
        width = w; height = h; encoded = data
        image = try Self.decode(reader, width: w, height: h, maxEdge: 4096,
                                byteBudget: RasterDocument.historyBudget - data.count - 65_536)
    }

    private static func decode(_ reader: CGImageSource, width: Int, height: Int, maxEdge: Int, byteBudget: Int) throws -> CGImage {
        let scale = min(1, sqrt(Double(max(1, byteBudget)) / (4 * Double(width) * Double(height))))
        let edge = min(maxEdge, max(1, Int(Double(max(width, height)) * scale)))
        guard let decoded = CGImageSourceCreateThumbnailAtIndex(reader, 0, [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: edge,
            kCGImageSourceShouldCacheImmediately: true
        ] as CFDictionary) else { throw DrawingError.message("The photo could not be decoded.") }
        return decoded
    }

    /// PNG export requests detail at the layer's destination size, independently
    /// of its interactive cache. Only one enlarged layer is held by the compositor.
    func exportImage(for bounds: CGRect) throws -> CGImage {
        let longest = max(width, height)
        let desired = min(longest, max(1, Int(ceil(max(bounds.width, bounds.height)))))
        guard desired > max(image.width, image.height) else { return image }
        guard let reader = CGImageSourceCreateWithData(encoded as CFData, nil) else {
            throw DrawingError.message("The original photo could not be read.")
        }
        return try Self.decode(reader, width: width, height: height, maxEdge: desired, byteBudget: 64 * 1024 * 1024)
    }
    var bytes: Int { image.bytesPerRow * image.height + encoded.count }
}

struct PhotoLayerRecord: Codable, Equatable {
    var sourceID = UUID()
    let width: Int
    let height: Int
    var mesh: [CGPoint]?
    var filtered: Bool?
    var transform: CGAffineTransform
    var source: PhotoSource?
    private enum CodingKeys: String, CodingKey { case sourceID, width, height, transform, mesh, filtered }
    static func == (lhs: Self, rhs: Self) -> Bool {
        lhs.sourceID == rhs.sourceID && lhs.width == rhs.width && lhs.height == rhs.height && lhs.transform == rhs.transform && lhs.mesh == rhs.mesh && lhs.filtered == rhs.filtered
    }
    var sourceBounds: CGRect { CGRect(x: 0, y: 0, width: width, height: height) }
    var geometry: TransformGeometry { TransformGeometry(rect: sourceBounds, pose: transform, mesh: mesh, filtered: filtered ?? true) }
    var bounds: CGRect { geometry.bounds }
    func validate() throws {
        let t = transform
        guard geometry.validated(), (1...PhotoSource.maximumDimension).contains(width), (1...PhotoSource.maximumDimension).contains(height),
              [t.a, t.b, t.c, t.d, t.tx, t.ty].allSatisfy({ $0.isFinite && abs($0) < 1_000_000 }),
              abs(t.a * t.d - t.b * t.c) > 0.00000001 else { throw DrawingError.message("Invalid photo geometry.") }
    }
    func tile(_ key: TileKey, masks: [TileKey: CGImage], image sourceImage: CGImage? = nil) throws -> CGImage? {
        guard bounds.intersects(key.rect), let image = sourceImage ?? source?.image else { return nil }
        let context = try RasterDocument.context(width: 256, height: 256)
        context.translateBy(x: -key.rect.minX, y: -key.rect.minY)
        geometry.render(in: context, visible: key.rect) { piece in
            RasterDocument.draw(image, in: piece, rect: sourceBounds)
            piece.setBlendMode(.destinationOut)
            let visible = piece.boundingBoxOfClipPath
            for (sourceKey, coverage) in masks where visible.intersects(sourceKey.rect) {
                RasterDocument.draw(coverage, in: piece, rect: sourceKey.rect)
            }
        }
        return context.makeImage()
    }
}

extension RasterDocument {
    func importPhoto(_ source: PhotoSource, name: String) throws {
        let width = source.width, height = source.height
        guard record.layers.count < 32, source.bytes + 256 <= Self.historyBudget,
              residentTileCount + (source.bytes + Self.tileBytes - 1) / Self.tileBytes <= Self.maximumTiles else {
            throw DrawingError.message("This photo exceeds the layer, undo or resident image budget.")
        }
        let displaySize = CGSize(width: source.image.width, height: source.image.height)
        let scale = min(bounds.width / displaySize.width, bounds.height / displaySize.height)
        let rect = CGRect(x: (bounds.width - displaySize.width * scale) / 2, y: (bounds.height - displaySize.height * scale) / 2,
                          width: displaySize.width * scale, height: displaySize.height * scale)
        let pose = CGAffineTransform(translationX: rect.minX, y: rect.minY).scaledBy(x: rect.width / CGFloat(width), y: rect.height / CGFloat(height))
        var layer = LayerRecord(); layer.name = name
        layer.photo = PhotoLayerRecord(width: width, height: height, transform: pose, source: source)
        try layer.photo?.validate()
        beginMetadataEdit(); record.layers.append(layer); record.selectedLayer = layer.id; endMetadataEdit()
    }
    var activePhoto: PhotoLayerRecord? { record.layers.first { $0.id == activeLayer }?.photo }
    func transformPhoto(_ pose: CGAffineTransform) throws {
        guard let i = record.layers.firstIndex(where: { $0.id == activeLayer }), !record.layers[i].locked,
              var photo = record.layers[i].photo else { return }
        photo.transform = pose
        try photo.validate()
        beginMetadataEdit(); record.layers[i].photo = photo; endMetadataEdit()
    }
}

extension DocumentRecord {
    var photoSources: [UUID: PhotoSource] {
        layers.reduce(into: [:]) { sources, layer in
            if let photo = layer.photo, let source = photo.source { sources[photo.sourceID] = source }
        }
    }
}

/// Each drawing step may position an immutable photo independently. Absence represents a blank cel.
struct PhotoCelRecord: Codable, Equatable {
    var transform: CGAffineTransform
    var mesh: [CGPoint]?
    var filtered: Bool?
    init(transform: CGAffineTransform, mesh: [CGPoint]? = nil, filtered: Bool? = nil) {
        self.transform = transform; self.mesh = mesh; self.filtered = filtered
    }
    init(photo: PhotoLayerRecord) { self.init(transform: photo.transform, mesh: photo.mesh, filtered: photo.filtered) }
    func applying(to photo: PhotoLayerRecord) -> PhotoLayerRecord {
        var copy = photo; copy.transform = transform; copy.mesh = mesh; copy.filtered = filtered
        return copy
    }
}

extension DocumentRecord {
    mutating func installPhotoCels(_ animation: AnimationRecord, at index: Int) {
        guard let cels = animation.frames[index].photoCels else { return }
        for i in layers.indices {
            guard let photo = layers[i].photo, !animation.sharedLayerIDs.contains(layers[i].id) else { continue }
            let cel = cels[layers[i].id.uuidString]
            layers[i].photoCelActive = cel != nil
            if let cel { layers[i].photo = cel.applying(to: photo) }
        }
    }
    func capturePhotoCels(_ animation: inout AnimationRecord) {
        let index = animation.selectedIndex
        var current = animation.frames[index].photoCels ?? [:]
        for layer in layers where !animation.sharedLayerIDs.contains(layer.id) {
            if let photo = layer.photo { current[layer.id.uuidString] = layer.photoCelActive ? PhotoCelRecord(photo: photo) : nil }
        }
        animation.frames[index].photoCels = current
    }
}

extension RasterDocument {
    func installPhotoCels(_ animation: AnimationRecord) { record.installPhotoCels(animation, at: animation.selectedIndex) }
    func restoreLegacyPhotoCels() {
        guard var animation = record.animation else { return }
        let defaults = record.layers.reduce(into: [String: PhotoCelRecord]()) { cels, layer in
            if let photo = layer.photo { cels[layer.id.uuidString] = PhotoCelRecord(photo: photo) }
        }
        for index in animation.frames.indices where animation.frames[index].photoCels == nil {
            animation.frames[index].photoCels = defaults
        }
        record.animation = animation; installPhotoCels(animation)
    }
}

extension DocumentSnapshot {
    func atFrame(_ id: UUID) throws -> Self {
        var frame = self
        guard var animation = record.animation, let index = animation.frames.firstIndex(where: { $0.id == id }) else { return frame }
        frame.pixels = try pixelsForFrame(id)
        animation.selectedIndex = index
        frame.record.animation = animation
        frame.record.installPhotoCels(animation, at: index)
        return frame
    }
}
