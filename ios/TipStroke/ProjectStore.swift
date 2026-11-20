import UIKit
import ImageIO
import UniformTypeIdentifiers

// All methods execute on queue. Save publishes one atomic revision pointer only after every file exists.
final class ProjectStore {
    static let shared = ProjectStore()
    let queue = DispatchQueue(label: "dev.tipstroke.ios.projects", qos: .userInitiated)
    let root: URL
    init(root: URL? = nil) {
        self.root = root ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("TipStroke", isDirectory: true)
    }
    private func folder(_ id: UUID) -> URL { root.appendingPathComponent(id.uuidString, isDirectory: true) }
    private func revision(_ id: UUID) throws -> URL {
        let pointer = try Data(contentsOf: folder(id).appendingPathComponent("current"))
        guard let text = String(data: pointer, encoding: .utf8), let revision = UUID(uuidString: text) else {
            throw DrawingError.message("The saved drawing revision is invalid.")
        }
        return folder(id).appendingPathComponent(revision.uuidString, isDirectory: true)
    }
    func list() throws -> [DocumentRecord] {
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        return try FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)
            .compactMap { url -> DocumentRecord? in
                guard let id = UUID(uuidString: url.lastPathComponent) else { return nil }
                // An interrupted initial save has no published document and is not gallery data.
                guard FileManager.default.fileExists(atPath: url.appendingPathComponent("current").path) else { return nil }
                return try metadata(id)
            }.sorted { $0.modified > $1.modified }
    }
    func metadata(_ id: UUID) throws -> DocumentRecord {
        let data = try Data(contentsOf: revision(id).appendingPathComponent("manifest.json"))
        let record = try JSONDecoder().decode(DocumentRecord.self, from: data)
        try record.validate()
        guard record.id == id else { throw DrawingError.message("Drawing identity does not match its folder.") }
        return record
    }
    func load(_ id: UUID) throws -> DocumentSnapshot {
        var record = try metadata(id)
        let directory = try revision(id)
        var sources: [UUID: PhotoSource] = [:]
        let activeTiles = record.layers.reduce(0) { $0 + $1.tiles.count }
        let otherTiles = record.animation.map { animation in
            animation.frames.enumerated().filter { $0.offset != animation.selectedIndex }.reduce(0) { result, item in
                result + item.element.tiles.values.reduce(0) { $0 + $1.count }
            }
        } ?? 0
        var sourceBytes = (activeTiles + otherTiles) * RasterDocument.tileBytes
        for index in record.layers.indices {
            guard var photo = record.layers[index].photo else { continue }
            if sources[photo.sourceID] == nil {
                let url = directory.appendingPathComponent("photos").appendingPathComponent(photo.sourceID.uuidString + ".png")
                let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
                let bytes = photo.width * photo.height * 4
                guard size >= 0, size <= RasterDocument.historyBudget, sourceBytes + bytes + size <= RasterDocument.maximumTiles * RasterDocument.tileBytes else {
                    throw DrawingError.message("Photo sources exceed the drawing memory budget.")
                }
                sources[photo.sourceID] = try PhotoSource(file: url, width: photo.width, height: photo.height)
                sourceBytes += bytes + size
            }
            photo.source = sources[photo.sourceID]
            record.layers[index].photo = photo
        }
        var pixels: [UUID: [TileKey: CGImage]] = [:]
        func readTile(_ url: URL) throws -> CGImage {
            guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
                  let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
                  properties[kCGImagePropertyPixelWidth] as? Int == 256,
                  properties[kCGImagePropertyPixelHeight] as? Int == 256,
                  let image = CGImageSourceCreateImageAtIndex(source, 0, [kCGImageSourceShouldCacheImmediately: true] as CFDictionary) else {
                throw DrawingError.message("A saved tile is missing or damaged. The original drawing has been preserved.")
            }
            return image
        }
        for layer in record.layers {
            for key in layer.tiles {
                pixels[layer.id, default: [:]][key] = try readTile(directory.appendingPathComponent(layer.id.uuidString).appendingPathComponent(key.fileName))
            }
        }
        var frames: [UUID: [UUID: [TileKey: CGImage]]] = [:]
        if let animation = record.animation {
            for frame in animation.frames {
                let selected = frame.id == animation.frames[animation.selectedIndex].id
                if selected { frames[frame.id] = pixels.filter { !animation.sharedLayerIDs.contains($0.key) } }
                for (layerID, keys) in frame.tiles {
                    guard let id = UUID(uuidString: layerID) else { throw DrawingError.message("Invalid frame layer.") }
                    if selected && !animation.sharedLayerIDs.contains(id) { continue }
                    for key in keys {
                        let url = directory.appendingPathComponent("frames").appendingPathComponent(frame.id.uuidString)
                            .appendingPathComponent(layerID).appendingPathComponent(key.fileName)
                        frames[frame.id, default: [:]][id, default: [:]][key] = try readTile(url)
                    }
                }
            }
        }
        return DocumentSnapshot(record: record, pixels: pixels, frames: frames)
    }

    func save(_ snapshot: DocumentSnapshot) throws {
        try snapshot.record.validate()
        let fm = FileManager.default
        let directory = folder(snapshot.record.id)
        let revisionID = UUID().uuidString
        let staging = directory.appendingPathComponent(revisionID, isDirectory: true)
        try fm.createDirectory(at: staging, withIntermediateDirectories: true)
        var published = false
        defer { if !published { try? fm.removeItem(at: staging) } }
        let sources = snapshot.record.photoSources
        if !sources.isEmpty {
            let photos = staging.appendingPathComponent("photos", isDirectory: true)
            try fm.createDirectory(at: photos, withIntermediateDirectories: false)
            for (id, source) in sources {
                try source.png.write(to: photos.appendingPathComponent(id.uuidString + ".png"))
            }
        }
        guard snapshot.record.layers.allSatisfy({ $0.photo == nil || $0.photo?.source != nil }) else {
            throw DrawingError.message("A photo source is missing.")
        }
        for layer in snapshot.record.layers {
            let target = staging.appendingPathComponent(layer.id.uuidString, isDirectory: true)
            try fm.createDirectory(at: target, withIntermediateDirectories: true)
            for key in layer.tiles {
                guard let image = snapshot.pixels[layer.id]?[key] else { throw DrawingError.message("A painting tile is missing.") }
                try Self.writePNG(image, to: target.appendingPathComponent(key.fileName))
            }
        }
        if let animation = snapshot.record.animation {
            for frame in animation.frames {
                for (layerID, keys) in frame.tiles {
                    guard let id = UUID(uuidString: layerID) else { throw DrawingError.message("Invalid frame layer.") }
                    if frame.id == animation.frames[animation.selectedIndex].id && !animation.sharedLayerIDs.contains(id) { continue }
                    let target = staging.appendingPathComponent("frames").appendingPathComponent(frame.id.uuidString).appendingPathComponent(layerID)
                    try fm.createDirectory(at: target, withIntermediateDirectories: true)
                    for key in keys {
                        guard let image = snapshot.frames[frame.id]?[id]?[key] else { throw DrawingError.message("An animation tile is missing.") }
                        try Self.writePNG(image, to: target.appendingPathComponent(key.fileName))
                    }
                }
            }
        }
        let thumbnail = try Self.thumbnail(snapshot)
        try Self.writePNG(thumbnail, to: staging.appendingPathComponent("thumbnail.png"))
        try JSONEncoder().encode(snapshot.record).write(to: staging.appendingPathComponent("manifest.json"), options: .atomic)
        try Data(revisionID.utf8).write(to: directory.appendingPathComponent("current"), options: .atomic)
        published = true
        for old in (try? fm.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)) ?? []
        where old.lastPathComponent != revisionID && UUID(uuidString: old.lastPathComponent) != nil {
            try? fm.removeItem(at: old)
        }
    }
    static func thumbnail(_ snapshot: DocumentSnapshot) throws -> CGImage {
        let scale = min(256.0 / Double(snapshot.record.width), 256.0 / Double(snapshot.record.height))
        let width = max(1, Int(Double(snapshot.record.width) * scale)), height = max(1, Int(Double(snapshot.record.height) * scale))
        let context = try RasterDocument.context(width: width, height: height)
        context.scaleBy(x: scale, y: scale)
        let bounds = CGRect(x: 0, y: 0, width: snapshot.record.width, height: snapshot.record.height)
        context.setFillColor(snapshot.record.paperColor.cgColor); context.fill(bounds)
        try RasterDocument.composite(snapshot, in: context, visible: bounds)
        guard let image = context.makeImage() else { throw DrawingError.message("Could not render the thumbnail.") }; return image
    }
    func thumbnail(_ id: UUID) throws -> UIImage? {
        UIImage(contentsOfFile: try revision(id).appendingPathComponent("thumbnail.png").path)
    }
    func rename(_ id: UUID, name: String) throws {
        var snapshot = try load(id); snapshot.record.name = name; snapshot.record.modified = Date(); try save(snapshot)
    }
    func duplicate(_ id: UUID) throws {
        var snapshot = try load(id)
        let copy = UUID()
        snapshot.record.id = copy; snapshot.record.name += " copy"; snapshot.record.modified = Date()
        do {
            try save(snapshot); try copyPackageBase(from: id, to: copy)
            if FileManager.default.fileExists(atPath: replayFolder(id).path) {
                try FileManager.default.copyItem(at: replayFolder(id), to: replayFolder(copy))
            }
        }
        catch { try? delete(copy); throw error }
    }
    func delete(_ id: UUID) throws { try FileManager.default.removeItem(at: folder(id)) }

    func exportPNG(_ snapshot: DocumentSnapshot, transparent: Bool = false) throws -> URL {
        let record = snapshot.record
        let context = try RasterDocument.context(width: record.width, height: record.height)
        let bounds = CGRect(x: 0, y: 0, width: record.width, height: record.height)
        if !transparent { context.setFillColor(record.paperColor.cgColor); context.fill(bounds) }
        try RasterDocument.composite(snapshot, in: context, visible: bounds)
        guard let image = context.makeImage() else { throw DrawingError.message("Could not render the drawing.") }
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("TipStrokeExports", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        // Keep only recent exports, avoiding unlimited temporary disk growth.
        for file in (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.contentModificationDateKey])) ?? [] {
            if let date = try? file.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate,
               date < Date().addingTimeInterval(-86400) { try? FileManager.default.removeItem(at: file) }
        }
        let url = directory.appendingPathComponent("TipStroke-\(UUID().uuidString).png")
        try Self.writePNG(image, to: url)
        return url
    }
    static func writePNG(_ image: CGImage, to url: URL) throws {
        guard let target = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil) else {
            throw DrawingError.message("Could not create the PNG file.")
        }
        CGImageDestinationAddImage(target, image, nil)
        guard CGImageDestinationFinalize(target) else { throw DrawingError.message("The PNG could not be saved. Check available storage.") }
    }
}
