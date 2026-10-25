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
        let record = try metadata(id)
        let directory = try revision(id)
        var pixels: [UUID: [TileKey: CGImage]] = [:]
        for layer in record.layers {
            for key in layer.tiles {
                let url = directory.appendingPathComponent(layer.id.uuidString).appendingPathComponent(key.fileName)
                guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
                      let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
                      properties[kCGImagePropertyPixelWidth] as? Int == 256,
                      properties[kCGImagePropertyPixelHeight] as? Int == 256,
                      let image = CGImageSourceCreateImageAtIndex(source, 0, [kCGImageSourceShouldCacheImmediately: true] as CFDictionary) else {
                    throw DrawingError.message("A saved tile is missing or damaged. The original drawing has been preserved.")
                }
                pixels[layer.id, default: [:]][key] = image
            }
        }
        return DocumentSnapshot(record: record, pixels: pixels)
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
        for layer in snapshot.record.layers {
            let target = staging.appendingPathComponent(layer.id.uuidString, isDirectory: true)
            try fm.createDirectory(at: target, withIntermediateDirectories: true)
            for key in layer.tiles {
                guard let image = snapshot.pixels[layer.id]?[key] else { throw DrawingError.message("A painting tile is missing.") }
                try Self.writePNG(image, to: target.appendingPathComponent(key.fileName))
            }
        }
        try JSONEncoder().encode(snapshot.record).write(to: staging.appendingPathComponent("manifest.json"), options: .atomic)
        try Data(revisionID.utf8).write(to: directory.appendingPathComponent("current"), options: .atomic)
        published = true
        for old in (try? fm.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)) ?? []
        where old.lastPathComponent != revisionID && UUID(uuidString: old.lastPathComponent) != nil {
            try? fm.removeItem(at: old)
        }
    }
    func delete(_ id: UUID) throws { try FileManager.default.removeItem(at: folder(id)) }

    func exportPNG(_ snapshot: DocumentSnapshot) throws -> URL {
        let record = snapshot.record
        let context = try RasterDocument.context(width: record.width, height: record.height)
        let bounds = CGRect(x: 0, y: 0, width: record.width, height: record.height)
        context.setFillColor(UIColor.white.cgColor); context.fill(bounds)
        RasterDocument.composite(snapshot, in: context, visible: bounds)
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
