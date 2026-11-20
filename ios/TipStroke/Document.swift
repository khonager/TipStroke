import Foundation
import CoreGraphics
import TipStrokeShared

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

struct LayerRecord: Codable, Identifiable, Equatable {
    var id = UUID()
    var name = "Layer 1"
    var visible = true
    var opacity: Double = 1
    var tiles: [TileKey] = []
    var blendMode = "NORMAL"
    var locked = false
    var alphaLocked = false
    var clipSource: UUID?
    var photo: PhotoLayerRecord?
    var colorizeMask: String?
    var reference = false

    init() {}
    private enum CodingKeys: String, CodingKey { case id, name, visible, opacity, tiles, blendMode, locked, alphaLocked, clipSource, reference, colorizeMask, photo }
    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try values.decode(UUID.self, forKey: .id)
        name = try values.decode(String.self, forKey: .name)
        visible = try values.decode(Bool.self, forKey: .visible)
        opacity = try values.decode(Double.self, forKey: .opacity)
        tiles = try values.decode([TileKey].self, forKey: .tiles)
        blendMode = try values.decodeIfPresent(String.self, forKey: .blendMode) ?? "NORMAL"
        locked = try values.decodeIfPresent(Bool.self, forKey: .locked) ?? false
        alphaLocked = try values.decodeIfPresent(Bool.self, forKey: .alphaLocked) ?? false
        clipSource = try values.decodeIfPresent(UUID.self, forKey: .clipSource)
        photo = try values.decodeIfPresent(PhotoLayerRecord.self, forKey: .photo)
        colorizeMask = try values.decodeIfPresent(String.self, forKey: .colorizeMask)
        reference = try values.decodeIfPresent(Bool.self, forKey: .reference) ?? false
    }
}

struct DocumentRecord: Codable, Identifiable, Equatable {
    // Separate format namespace: never interpret Android project manifests as iOS documents.
    var format = "dev.tipstroke.ios.raster"
    var schemaVersion = 4
    var id = UUID()
    var name = "Untitled"
    var width = 2048
    var height = 2048
    var modified = Date()
    var layers = [LayerRecord()]
    var selectedLayer: UUID?
    var media: MediaTrack?
    var animation: AnimationRecord?
    var backgroundARGB: UInt32?
    var backgroundOpacity: Double?

    func validate() throws {
        guard format == "dev.tipstroke.ios.raster", (1...6).contains(schemaVersion) else {
            throw DrawingError.message("This drawing uses an unsupported project format.")
        }
        guard (16...4096).contains(width), (16...4096).contains(height),
              (1...32).contains(layers.count), Set(layers.map(\.id)).count == layers.count,
              !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              selectedLayer == nil || layers.contains(where: { $0.id == selectedLayer }) else {
            throw DrawingError.message("This drawing has invalid document metadata.")
        }
        if let animation {
            guard (1...240).contains(animation.frames.count), (1...60).contains(animation.fps),
                animation.frames.indices.contains(animation.selectedIndex),
                Set(animation.frames.map(\.id)).count == animation.frames.count,
                animation.frames.allSatisfy({ (1...600).contains($0.exposure) }),
                ["LOOP", "PING_PONG", "ONCE"].contains(animation.playbackMode) else {
                throw DrawingError.message("Invalid animation timeline.")
            }
        }
        if let media {
            let plan = try media.plan()
            guard animation != nil, (0..<plan.durationUs).contains(media.positionUs),
                  animation?.frames.allSatisfy({ ($0.sourceTimeUs.map { (0...86_400_000_000).contains($0) } ?? true)
                    && ($0.durationUs.map { (1...86_400_000_000).contains($0) } ?? true) }) == true else {
                throw DrawingError.message("Invalid media playhead or drawing coverage.")
            }
        }
        if let opacity = backgroundOpacity, !opacity.isFinite || !(0...1).contains(opacity) { throw DrawingError.message("Invalid background opacity.") }
        guard layers.filter({ $0.reference }).count <= 1 else { throw DrawingError.message("Only one reference layer is supported.") }
        var tileCount = 0
        for (index, layer) in layers.enumerated() {
            try layer.photo?.validate()
            let tileWidth = layer.photo?.width ?? width, tileHeight = layer.photo?.height ?? height
            if let mask = layer.colorizeMask {
                let recipe = try NativeColorizeRecipe.companion.decode(text: mask)
                guard UUID(uuidString: recipe.lineArtId) != nil, UUID(uuidString: recipe.hintsId) != nil else { throw DrawingError.message("Invalid colorize layer IDs.") }
            }
            if let source = layer.clipSource, !layers.prefix(index).contains(where: { $0.id == source }) { throw DrawingError.message("Invalid clipping source.") }
            guard RasterDocument.blendModes[layer.blendMode] != nil, layer.opacity.isFinite, (0...1).contains(layer.opacity),
                  Set(layer.tiles).count == layer.tiles.count,
                  layer.tiles.allSatisfy({ $0.x >= 0 && $0.y >= 0 && $0.x < (tileWidth + 255) / 256 && $0.y < (tileHeight + 255) / 256 }) else {
                throw DrawingError.message("This drawing has invalid layer metadata.")
            }
            tileCount += layer.tiles.count
        }
        if let animation {
            let layerIDs = Set(layers.map { $0.id.uuidString })
            for (index, frame) in animation.frames.enumerated() {
                for (layerID, keys) in frame.tiles {
                    let photo = layers.first(where: { $0.id.uuidString == layerID })?.photo
                    let tileWidth = photo?.width ?? width, tileHeight = photo?.height ?? height
                    guard layerIDs.contains(layerID), Set(keys).count == keys.count,
                          keys.allSatisfy({ $0.x >= 0 && $0.y >= 0 && $0.x < (tileWidth + 255) / 256 && $0.y < (tileHeight + 255) / 256 }) else {
                        throw DrawingError.message("Invalid animation tile metadata.")
                    }
                    if index != animation.selectedIndex || animation.sharedLayerIDs.contains(UUID(uuidString: layerID)!) { tileCount += keys.count }
                }
            }
            let current = animation.frames[animation.selectedIndex]
            guard animation.sharedLayerIDs.isSubset(of: Set(layers.map(\.id))), layers.allSatisfy({ animation.sharedLayerIDs.contains($0.id) || Set(current.tiles[$0.id.uuidString] ?? []) == Set($0.tiles) }) else {
                throw DrawingError.message("The selected frame does not match the painting tiles.")
            }
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
    var frames: [UUID: [UUID: [TileKey: CGImage]]] = [:]
}

struct AnimationFrameRecord: Codable, Equatable, Identifiable {
    var sourceTimeUs: Int64?
    var durationUs: Int64?
    var id = UUID()
    var exposure = 1
    var tiles: [String: [TileKey]] = [:]
}
struct AnimationRecord: Codable, Equatable {
    var frames = [AnimationFrameRecord()]
    var selectedIndex = 0
    var fps = 12
    var playbackMode = "LOOP"
    var backgroundLayers: Set<UUID>?
    var onionSkin = false
}
