import Foundation
import CoreGraphics

struct TileKey: Hashable, Codable {
    static let side = 256
    let x: Int
    let y: Int
    var fileName: String { "\(x)_\(y).png" }
    var rect: CGRect { CGRect(x: x * Self.side, y: y * Self.side, width: Self.side, height: Self.side) }

    static func intersecting(_ rect: CGRect, width: Int, height: Int) -> [TileKey] {
        let clipped = rect.intersection(CGRect(x: 0, y: 0, width: width, height: height))
        guard !clipped.isNull, clipped.width > 0, clipped.height > 0 else { return [] }
        let left = Int(floor(clipped.minX / 256))
        let top = Int(floor(clipped.minY / 256))
        let right = Int(ceil(clipped.maxX / 256)) - 1
        let bottom = Int(ceil(clipped.maxY / 256)) - 1
        return (top...bottom).flatMap { y in (left...right).map { TileKey(x: $0, y: y) } }
    }
}

struct LayerRecord: Codable, Identifiable {
    var id = UUID()
    var name = "Layer 1"
    var visible = true
    var opacity: Double = 1
    var tiles: [TileKey] = []
}

struct DocumentRecord: Codable, Identifiable {
    // Separate format namespace: never interpret Android project manifests as iOS documents.
    var format = "dev.tipstroke.ios.raster"
    var schemaVersion = 1
    var id = UUID()
    var name = "Untitled"
    var width = 2048
    var height = 2048
    var modified = Date()
    var layers = [LayerRecord()]
    var selectedLayer: UUID?

    func validate() throws {
        guard format == "dev.tipstroke.ios.raster", schemaVersion == 1 else {
            throw DrawingError.message("This drawing uses an unsupported project format.")
        }
        guard (16...4096).contains(width), (16...4096).contains(height),
              (1...32).contains(layers.count), Set(layers.map(\.id)).count == layers.count,
              !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              selectedLayer == nil || layers.contains(where: { $0.id == selectedLayer }) else {
            throw DrawingError.message("This drawing has invalid document metadata.")
        }
        var tileCount = 0
        for layer in layers {
            guard layer.opacity.isFinite, (0...1).contains(layer.opacity),
                  Set(layer.tiles).count == layer.tiles.count,
                  layer.tiles.allSatisfy({ $0.x >= 0 && $0.y >= 0 && $0.x < (width + 255) / 256 && $0.y < (height + 255) / 256 }) else {
                throw DrawingError.message("This drawing has invalid layer metadata.")
            }
            tileCount += layer.tiles.count
        }
        guard tileCount <= RasterDocument.maximumTiles else {
            throw DrawingError.message("This drawing exceeds the iOS tile memory budget.")
        }
    }
}

enum DrawingError: LocalizedError {
    case message(String)
    var errorDescription: String? { if case .message(let message) = self { return message }; return nil }
}

struct DocumentSnapshot {
    var record: DocumentRecord
    var pixels: [UUID: [TileKey: CGImage]]
}
