import UIKit
import ImageIO
import UniformTypeIdentifiers

/// Immutable pixels shared by layer copies, previews, and undo records.
final class PhotoSource {
    let image: CGImage
    let png: Data
    init(image: CGImage) throws {
        let buffer = NSMutableData()
        guard let writer = CGImageDestinationCreateWithData(buffer as CFMutableData, UTType.png.identifier as CFString, 1, nil) else {
            throw DrawingError.message("Could not prepare the imported photo.")
        }
        CGImageDestinationAddImage(writer, image, nil)
        guard CGImageDestinationFinalize(writer) else { throw DrawingError.message("Could not encode the photo.") }
        self.image = image; self.png = buffer as Data
    }
    init(file: URL, width: Int, height: Int) throws {
        let data = try Data(contentsOf: file, options: .mappedIfSafe)
        guard let reader = CGImageSourceCreateWithData(data as CFData, nil),
              let props = CGImageSourceCopyPropertiesAtIndex(reader, 0, nil) as? [CFString: Any],
              props[kCGImagePropertyPixelWidth] as? Int == width, props[kCGImagePropertyPixelHeight] as? Int == height,
              let image = CGImageSourceCreateImageAtIndex(reader, 0, [kCGImageSourceShouldCacheImmediately: true] as CFDictionary) else {
            throw DrawingError.message("The saved photo source is missing or damaged.")
        }
        self.image = image; self.png = data
    }
    var bytes: Int { image.bytesPerRow * image.height + png.count }
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
        guard geometry.validated(), (1...4096).contains(width), (1...4096).contains(height),
              [t.a, t.b, t.c, t.d, t.tx, t.ty].allSatisfy({ $0.isFinite && abs($0) < 1_000_000 }),
              abs(t.a * t.d - t.b * t.c) > 0.00000001 else { throw DrawingError.message("Invalid photo geometry.") }
    }
    func tile(_ key: TileKey, masks: [TileKey: CGImage]) throws -> CGImage? {
        guard bounds.intersects(key.rect), let image = source?.image else { return nil }
        let context = try RasterDocument.context(width: 256, height: 256)
        context.translateBy(x: -key.rect.minX, y: -key.rect.minY)
        geometry.render(in: context, visible: key.rect) { piece in
            RasterDocument.draw(image, in: piece, rect: sourceBounds)
            piece.setBlendMode(.destinationOut)
            for sourceKey in TileKey.intersecting(piece.boundingBoxOfClipPath, width: width, height: height) {
                if let coverage = masks[sourceKey] { RasterDocument.draw(coverage, in: piece, rect: sourceKey.rect) }
            }
        }
        return context.makeImage()
    }
}

extension RasterDocument {
    func importPhoto(_ source: PhotoSource, name: String) throws {
        let width = source.image.width, height = source.image.height
        guard record.layers.count < 32, source.bytes + 256 <= Self.historyBudget,
              residentTileCount + (source.bytes + Self.tileBytes - 1) / Self.tileBytes <= Self.maximumTiles else {
            throw DrawingError.message("This photo exceeds the layer, undo or resident image budget.")
        }
        let scale = min(bounds.width / CGFloat(width), bounds.height / CGFloat(height))
        let rect = CGRect(x: (bounds.width - CGFloat(width) * scale) / 2, y: (bounds.height - CGFloat(height) * scale) / 2,
                          width: CGFloat(width) * scale, height: CGFloat(height) * scale)
        let pose = CGAffineTransform(translationX: rect.minX, y: rect.minY).scaledBy(x: scale, y: scale)
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
