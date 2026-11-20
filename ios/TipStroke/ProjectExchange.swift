import UIKit
import ImageIO
import AVFoundation
import UniformTypeIdentifiers
import ZIPFoundation

/// Android project packages contain independent PNG tiles and the original manifest.
/// Keep platform-specific manifest fields with imported drawings so exporting an edit
/// preserves metadata (group IDs, color usage, gallery orientation and process history).
typealias JSONObject = [String: Any]

private enum PackageIO {
    static let fm = FileManager.default
    static let invalid = DrawingError.message("The TipStroke project package is damaged or unsupported.")
    static let sizeLimit: UInt64 = 2 * 1024 * 1024 * 1024

    static func json(_ url: URL) throws -> JSONObject {
        let bytes = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
        guard (1...8_388_608).contains(bytes),
              let object = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? JSONObject else { throw invalid }
        return object
    }

    static func write(_ object: JSONObject, at url: URL) throws {
        try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]).write(to: url, options: .atomic)
    }

    static func safeID(_ value: String) -> Bool {
        !value.isEmpty && value.utf8.count <= 255 && value.utf8.allSatisfy {
            (65...90).contains($0) || (97...122).contains($0) || (48...57).contains($0) || $0 == 45 || $0 == 95
        }
    }

    static func temporaryFolder() throws -> URL {
        let url = fm.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try fm.createDirectory(at: url, withIntermediateDirectories: false)
        return url
    }

    static func unpack(_ input: URL, to folder: URL) throws {
        let archive = try Archive(url: input, accessMode: .read)
        var seen = Set<String>()
        var total: UInt64 = 0
        for entry in archive {
            let path = entry.type == .directory && entry.path.hasSuffix("/") ? String(entry.path.dropLast()) : entry.path
            let components = path.components(separatedBy: "/")
            guard entry.type != .symlink, !path.contains("\\"), !path.utf8.contains(0),
                  components.allSatisfy({ !$0.isEmpty && $0 != "." && $0 != ".." }),
                  seen.count < 100_000, seen.insert(path.lowercased()).inserted,
                  entry.uncompressedSize <= sizeLimit - total else { throw invalid }
            let output = folder.appendingPathComponent(path)
            if entry.type == .directory {
                try fm.createDirectory(at: output, withIntermediateDirectories: true)
                continue
            }
            try fm.createDirectory(at: output.deletingLastPathComponent(), withIntermediateDirectories: true)
            guard fm.createFile(atPath: output.path, contents: nil) else { throw invalid }
            let file = try FileHandle(forWritingTo: output)
            defer { try? file.close() }
            var written: UInt64 = 0
            let checksum = try archive.extract(entry) { chunk in
                guard UInt64(chunk.count) <= entry.uncompressedSize - written else { throw invalid }
                try file.write(contentsOf: chunk)
                written += UInt64(chunk.count)
            }
            guard checksum == entry.checksum, written == entry.uncompressedSize else { throw invalid }
            total += written
        }
    }
}

private struct PackageIDs: Codable {
    var layers: [String: UUID] = [:]
    var frames: [String: UUID] = [:]
    var layerGroups: [String: UUID]?
    var mediaAssets: [String: UUID]?
    var clips: [String: UUID]?
}

extension ProjectStore {
    private func packageBase(_ id: UUID) -> URL {
        root.appendingPathComponent(id.uuidString).appendingPathComponent("exchange", isDirectory: true)
    }

    func copyPackageBase(from id: UUID, to copy: UUID) throws {
        let src = packageBase(id)
        if FileManager.default.fileExists(atPath: src.path) { try FileManager.default.copyItem(at: src, to: packageBase(copy)) }
    }

    func importPackage(_ input: URL) throws -> [UUID] { try importPackageWithLayout(input).drawings }

    func importPackageWithLayout(_ input: URL) throws -> (drawings: [UUID], layout: GalleryStacks) {
        let staging = try PackageIO.temporaryFolder()
        defer { try? FileManager.default.removeItem(at: staging) }
        try PackageIO.unpack(input, to: staging)
        let header = try PackageIO.json(staging.appendingPathComponent("tipstroke.json"))
        guard header["format"] as? String == "TipStroke", header["version"] as? Int == 1,
              let drawings = header["drawings"] as? [String], (1...10_000).contains(drawings.count),
              Set(drawings).count == drawings.count, drawings.allSatisfy(PackageIO.safeID) else { throw PackageIO.invalid }
        let galleryURL = staging.appendingPathComponent("gallery.json")
        let gallery = FileManager.default.fileExists(atPath: galleryURL.path) ? try PackageIO.json(galleryURL) : [:]
        let drafts = ProjectStore(root: staging.appendingPathComponent("installed"))
        var installed: [UUID] = []
        var prepared: [UUID] = []
        do {
            for original in drawings {
                let dir = staging.appendingPathComponent("drawings").appendingPathComponent(original)
                prepared.append(try autoreleasepool { try drafts.importDrawing(dir, original: original, gallery: gallery) })
            }
            try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
            for id in prepared {
                try FileManager.default.moveItem(at: drafts.root.appendingPathComponent(id.uuidString),
                                                 to: root.appendingPathComponent(id.uuidString))
                installed.append(id)
            }
            let mapping = Dictionary(uniqueKeysWithValues: zip(drawings, installed))
            var layout = GalleryStacks(), seen = Set<UUID>()
            for item in gallery["items"] as? [JSONObject] ?? [] {
                if item["kind"] as? String == "drawing", let key = item["id"] as? String, let id = mapping[key], seen.insert(id).inserted {
                    layout.order.append(id)
                } else if item["kind"] as? String == "stack", let members = item["drawingIds"] as? [String] {
                    let members = members.compactMap { mapping[$0] }.filter { seen.insert($0).inserted }
                    if members.isEmpty { continue }
                    let id = UUID()
                    layout.order.append(id); layout.stacks[id] = .init(name: item["name"] as? String ?? "Stack", members: members)
                }
            }
            layout.order += installed.filter { !seen.contains($0) }
            return (installed, layout)
        } catch {
            for id in installed { try? delete(id) }
            throw error
        }
    }

    private func importDrawing(_ directory: URL, original: String, gallery: JSONObject) throws -> UUID {
        let manifest = try PackageIO.json(directory.appendingPathComponent("manifest.json"))
        guard let version = manifest["schemaVersion"] as? Int, (1...12).contains(version),
              let w = manifest["widthPx"] as? Int, let h = manifest["heightPx"] as? Int,
              let name = manifest["name"] as? String, let layers = manifest["layers"] as? [JSONObject],
              (16...8192).contains(w), (16...8192).contains(h), (1...32).contains(layers.count) else {
            throw DrawingError.message("This drawing exceeds the supported canvas or layer limits, or uses a newer format.")
        }
        var ids = PackageIDs(), state = DocumentRecord()
        state.width = w; state.height = h; state.name = name
        state.backgroundOpacity = manifest["backgroundOpacity"] as? Double
        state.backgroundARGB = (manifest["backgroundColor"] as? NSNumber)?.uint32Value
        func identifier(_ object: JSONObject) throws -> String {
            guard let id = object["id"] as? String, PackageIO.safeID(id) else { throw PackageIO.invalid }
            return id
        }
        for layer in layers {
            let key = try identifier(layer)
            guard ids.layers[key] == nil else { throw PackageIO.invalid }
            ids.layers[key] = UUID(uuidString: key) ?? UUID()
        }
        guard Set(ids.layers.values).count == ids.layers.count else { throw PackageIO.invalid }
        var tileCount = 0
        func tileURLs(_ directory: URL, width: Int, height: Int) throws -> [TileKey: URL] {
            guard FileManager.default.fileExists(atPath: directory.path) else { return [:] }
            var result: [TileKey: URL] = [:]
            for file in try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil) {
                let indices = file.deletingPathExtension().lastPathComponent.components(separatedBy: "_")
                guard indices.count == 2, let x = Int(indices[0]), let y = Int(indices[1]),
                      x >= 0, x < (width + 255) / 256, y >= 0, y < (height + 255) / 256 else { throw PackageIO.invalid }
                let key = TileKey(x: x, y: y)
                guard key.fileName == file.lastPathComponent, result[key] == nil else { throw PackageIO.invalid }
                result[key] = file
            }
            return result
        }
        func readTiles(_ directory: URL, width: Int, height: Int) throws -> [TileKey: CGImage] {
            let files = try tileURLs(directory, width: width, height: height)
            tileCount += files.count
            guard tileCount <= RasterDocument.maximumTiles else { throw PackageIO.invalid }
            return try files.mapValues(CelStorage.read)
        }
        var pixels: [UUID: [TileKey: CGImage]] = [:]
        let settingsFile = directory.appendingPathComponent("ios-layer-settings.json")
        let settings = FileManager.default.fileExists(atPath: settingsFile.path) ? try PackageIO.json(settingsFile) : [:]
        state.layers = try layers.map { raw in
            let key = try identifier(raw)
            var layer = LayerRecord()
            guard let native = ids.layers[key], let name = raw["name"] as? String else { throw PackageIO.invalid }
            layer.id = native; layer.name = name; layer.visible = raw["visible"] as? Bool ?? true
            layer.opacity = raw["opacity"] as? Double ?? 1; layer.blendMode = raw["blendMode"] as? String ?? "NORMAL"
            let flags = settings[key] as? JSONObject ?? raw
            layer.locked = flags["iosLocked"] as? Bool ?? false
            layer.alphaLocked = flags["iosAlphaLocked"] as? Bool ?? false
            layer.reference = flags["iosReference"] as? Bool ?? false
            if let group = raw["groupId"] as? String {
                guard PackageIO.safeID(group) else { throw PackageIO.invalid }
                var groups = ids.layerGroups ?? [:]
                let groupID = groups[group] ?? UUID(uuidString: group) ?? UUID()
                groups[group] = groupID; ids.layerGroups = groups; layer.editGroup = groupID
            }
            if let clip = raw["clipSourceId"] as? String {
                guard let target = ids.layers[clip] else { throw PackageIO.invalid }
                layer.clipSource = target
            }
            if var recipe = raw["colorizeMask"] as? JSONObject {
                for field in ["lineArtId", "hintsId"] {
                    guard let ref = recipe[field] as? String, PackageIO.safeID(ref) else { throw PackageIO.invalid }
                    if ids.layers[ref] == nil { ids.layers[ref] = UUID(uuidString: ref) ?? UUID() }
                    recipe[field] = ids.layers[ref]?.uuidString
                }
                layer.colorizeMask = String(data: try JSONSerialization.data(withJSONObject: recipe), encoding: .utf8)
            }
            switch raw["kind"] as? String {
            case "raster":
                pixels[native] = try readTiles(directory.appendingPathComponent("layers/\(key)/tiles"), width: w, height: h)
            case "image":
                guard let width = raw["originalWidthPx"] as? Int, let height = raw["originalHeightPx"] as? Int,
                      (1...4096).contains(width), (1...4096).contains(height) else { throw PackageIO.invalid }
                let file = directory.appendingPathComponent("assets/\(key).source")
                let fileBytes = try file.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
                let cost = (width * height * 4 + fileBytes + RasterDocument.tileBytes - 1) / RasterDocument.tileBytes
                guard fileBytes > 0, tileCount + cost <= RasterDocument.maximumTiles else { throw PackageIO.invalid }
                tileCount += cost
                let photo = try Self.importPhoto(raw, width: width, height: height, source: PhotoSource(file: file, width: width, height: height))
                layer.photo = photo
                pixels[native] = try readTiles(directory.appendingPathComponent("layers/\(key)/mask"), width: width, height: height)
            default: throw PackageIO.invalid
            }
            return layer
        }
        state.selectedLayer = (manifest["selectedLayerId"] as? String).flatMap { ids.layers[$0] } ?? state.layers.last?.id
        var frames: [UUID: [UUID: [TileKey: CGImage]]] = [:]
        var coldFrames: [UUID: CelFiles] = [:]
        if let raw = manifest["animation"] as? JSONObject {
            if let media = raw["media"] as? JSONObject {
                state.media = try Self.importMedia(media, position: (raw["mediaPositionUs"] as? NSNumber)?.int64Value ?? 0,
                                                  from: directory, ids: &ids)
            }
            guard let list = raw["frames"] as? [JSONObject], (1...10_000).contains(list.count) else { throw PackageIO.invalid }
            var timeline = AnimationRecord()
            timeline.backgroundLayers = try Set((raw["backgroundLayerIds"] as? [String] ?? []).map {
                guard let id = ids.layers[$0] else { throw PackageIO.invalid }
                return id
            })
            timeline.fps = raw["fps"] as? Int ?? 12; timeline.playbackMode = raw["playbackMode"] as? String ?? "LOOP"
            timeline.selectedIndex = raw["selectedIndex"] as? Int ?? 0
            guard list.indices.contains(timeline.selectedIndex) else { throw PackageIO.invalid }
            timeline.onionSkin = (raw["onionBefore"] as? Int ?? 1) > 0 || (raw["onionAfter"] as? Int ?? 1) > 0
            timeline.frames = try list.map { item in
                let key = try identifier(item)
                guard ids.frames[key] == nil else { throw PackageIO.invalid }
                var frame = AnimationFrameRecord(); frame.id = UUID(uuidString: key) ?? UUID()
                frame.exposure = item["exposure"] as? Int ?? 1; ids.frames[key] = frame.id
                frame.sourceTimeUs = (item["sourceTimeUs"] as? NSNumber)?.int64Value
                frame.durationUs = (item["durationUs"] as? NSNumber)?.int64Value
                return frame
            }
            guard Set(ids.frames.values).count == ids.frames.count else { throw PackageIO.invalid }
            let maskRoot = directory.appendingPathComponent("ios-photo-masks")
            let flagsURL = maskRoot.appendingPathComponent("frames.json")
            var nativeMaskFrames = Set<String>()
            if FileManager.default.fileExists(atPath: flagsURL.path) {
                guard let frameKeys = try PackageIO.json(flagsURL)["frames"] as? [String], frameKeys.count <= 10_000 else { throw PackageIO.invalid }
                nativeMaskFrames = Set(frameKeys)
            }
            var maskReferences = 0
            for (layerKey, id) in ids.layers {
                guard let photo = state.layers.first(where: { $0.id == id })?.photo else { continue }
                let shared = try tileURLs(directory.appendingPathComponent("layers/\(layerKey)/mask"), width: photo.width, height: photo.height)
                    .mapValues { try LocalMediaFile($0) }
                for (frameKey, frameID) in ids.frames {
                    let selected = frameID == timeline.frames[timeline.selectedIndex].id
                    if selected && !timeline.sharedLayerIDs.contains(id) { continue }
                    if nativeMaskFrames.contains(frameKey) {
                        let path = maskRoot.appendingPathComponent(layerKey).appendingPathComponent(frameKey)
                        if selected {
                            frames[frameID, default: [:]][id] = try readTiles(path, width: photo.width, height: photo.height)
                        } else {
                            let files = try tileURLs(path, width: photo.width, height: photo.height)
                            if !files.isEmpty {
                                coldFrames[frameID, default: [:]][id] = try files.mapValues { url in
                                    try autoreleasepool { _ = try CelStorage.read(url); return try LocalMediaFile(url) }
                                }
                            }
                        }
                    } else if !selected && !timeline.sharedLayerIDs.contains(id) && !shared.isEmpty {
                        maskReferences += shared.count
                        guard maskReferences <= 100_000 else { throw DrawingError.message("This shared animation mask exceeds the iOS cel storage limit.") }
                        coldFrames[frameID, default: [:]][id] = shared
                    }
                }
            }
            let celRoot = directory.appendingPathComponent("animation/cels")
            if FileManager.default.fileExists(atPath: celRoot.path) {
                let selected = timeline.frames[timeline.selectedIndex].id
                var counts: [UUID: Int] = [:]
                for layerDir in try FileManager.default.contentsOfDirectory(at: celRoot, includingPropertiesForKeys: nil) {
                    guard let layer = ids.layers[layerDir.lastPathComponent] else { continue }
                    for celDir in try FileManager.default.contentsOfDirectory(at: layerDir, includingPropertiesForKeys: nil) {
                        guard let frame = ids.frames[celDir.lastPathComponent] else { continue }
                        if frame == selected {
                            if timeline.sharedLayerIDs.contains(layer) {
                                frames[frame, default: [:]][layer] = try readTiles(celDir, width: w, height: h)
                            }
                        } else {
                            let files = try tileURLs(celDir, width: w, height: h)
                            counts[frame, default: 0] += files.count
                            guard counts[frame, default: 0] <= RasterDocument.maximumTiles else { throw PackageIO.invalid }
                            coldFrames[frame, default: [:]][layer] = try files.mapValues { url in
                                try autoreleasepool {
                                    _ = try CelStorage.read(url)
                                    return try LocalMediaFile(url)
                                }
                            }
                        }
                    }
                }
            }
            let frameIndices = Dictionary(uniqueKeysWithValues: timeline.frames.enumerated().map { ($1.id, $0) })
            for index in timeline.frames.indices { timeline.frames[index].photoCels = [:] }
            for layerEntry in raw["imageCels"] as? [JSONObject] ?? [] {
                guard let layerKey = layerEntry["layerId"] as? String, let id = ids.layers[layerKey],
                      let photo = state.layers.first(where: { $0.id == id })?.photo, let source = photo.source,
                      let poses = layerEntry["frames"] as? [JSONObject] else { throw PackageIO.invalid }
                for value in poses {
                    guard let key = value["frameId"] as? String, let frameID = ids.frames[key], let index = frameIndices[frameID],
                          timeline.frames[index].photoCels?[id.uuidString] == nil else { throw PackageIO.invalid }
                    let pose = try Self.importPhoto(value, width: photo.width, height: photo.height, source: source)
                    timeline.frames[index].photoCels?[id.uuidString] = PhotoCelRecord(transform: pose.transform, mesh: pose.mesh, filtered: pose.filtered)
                }
            }
            state.animation = timeline
            state.installPhotoCels(timeline, at: timeline.selectedIndex)
        }
        guard Set(ids.layers.values).count == ids.layers.count else { throw PackageIO.invalid }
        let snapshot = RasterDocument(snapshot: DocumentSnapshot(record: state, pixels: pixels, frames: frames, coldFrames: coldFrames)).snapshot()
        try save(snapshot)
        let replay = directory.appendingPathComponent("ios-progress")
        if FileManager.default.fileExists(atPath: replay.path) {
            _ = try ProcessReplay(folder: replay)
            try FileManager.default.moveItem(at: replay, to: replayFolder(state.id))
        } else {
            let annotated = directory.appendingPathComponent("process-portable.tsr")
            let native = directory.appendingPathComponent("process.tsr")
            let source = FileManager.default.fileExists(atPath: annotated.path) ? annotated : native
            if FileManager.default.fileExists(atPath: source.path) {
                // Preserve the original recording independently even when a legacy native
                // brush command requires re-exporting the package on Android.
                do { try AndroidProcess.importRecording(source, to: replayFolder(state.id)) }
                catch { try AndroidProcess.noteUnavailable(error, in: replayFolder(state.id)) }
            }
        }
        let base = packageBase(state.id)
        try FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        // Keep ancillary files and platform metadata. Native revisions own the editable tile content.
        try FileManager.default.moveItem(at: directory, to: base.appendingPathComponent("original"))
        try JSONEncoder().encode(ids).write(to: base.appendingPathComponent("ids.json"))
        let progress = replayFolder(state.id)
        if FileManager.default.fileExists(atPath: ReplayFiles.indexURL(progress).path) {
            try PackageIO.write(["count": try ReplayFiles.index(progress).count], at: base.appendingPathComponent("process-anchor.json"))
        }
        if let group = (gallery["items"] as? [JSONObject])?.first(where: {
            $0["kind"] as? String == "stack" && ($0["drawingIds"] as? [String])?.contains(original) == true
        }) { try PackageIO.write(group, at: base.appendingPathComponent("group.json")) }
        return state.id
    }
}

extension ProjectStore {
    func exportPackage(_ drawings: [UUID], layout: GalleryStacks? = nil) throws -> URL {
        guard !drawings.isEmpty, Set(drawings).count == drawings.count else { throw PackageIO.invalid }
        let staging = try PackageIO.temporaryFolder()
        defer { try? FileManager.default.removeItem(at: staging) }
        let output = FileManager.default.temporaryDirectory.appendingPathComponent("TipStroke-\(UUID().uuidString).tipstroke")
        try PackageIO.write(["format": "TipStroke", "version": 1, "drawings": drawings.map(\.uuidString)],
                            at: staging.appendingPathComponent("tipstroke.json"))
        var items: [JSONObject] = []
        for id in drawings {
            let base = packageBase(id), group = base.appendingPathComponent("group.json")
            if FileManager.default.fileExists(atPath: group.path) {
                var item = try PackageIO.json(group)
                if let prior = items.firstIndex(where: { $0["kind"] as? String == "stack" && $0["id"] as? String == item["id"] as? String }) {
                    var members = items[prior]["drawingIds"] as? [String] ?? []
                    members.append(id.uuidString); items[prior]["drawingIds"] = members
                } else { item["drawingIds"] = [id.uuidString]; items.append(item) }
            } else { items.append(["kind": "drawing", "id": id.uuidString]) }
            let target = staging.appendingPathComponent("drawings/\(id.uuidString)")
            try autoreleasepool { try exportDrawing(load(id), base: base, to: target) }
        }
        if let layout {
            let selected = Set(drawings)
            items = layout.order.compactMap { id -> JSONObject? in
                if let stack = layout.stacks[id] {
                    let members = stack.members.filter { selected.contains($0) }
                    return members.isEmpty ? nil : ["kind": "stack", "id": id.uuidString, "name": stack.name, "drawingIds": members.map(\.uuidString)]
                }
                return selected.contains(id) ? ["kind": "drawing", "id": id.uuidString] : nil
            }
        }
        try PackageIO.write(["schemaVersion": 1, "items": items], at: staging.appendingPathComponent("gallery.json"))
        do {
            try FileManager.default.zipItem(at: staging, to: output, shouldKeepParent: false)
            return output
        } catch { try? FileManager.default.removeItem(at: output); throw error }
    }

    private func exportDrawing(_ snapshot: DocumentSnapshot, base: URL, to target: URL) throws {
        let fm = FileManager.default, state = snapshot.record
        let original = base.appendingPathComponent("original")
        var ids = PackageIDs(), manifest: JSONObject = [:]
        try fm.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
        if fm.fileExists(atPath: original.path) {
            try fm.copyItem(at: original, to: target)
            manifest = try PackageIO.json(target.appendingPathComponent("manifest.json"))
            ids = try JSONDecoder().decode(PackageIDs.self, from: Data(contentsOf: base.appendingPathComponent("ids.json")))
        } else { try fm.createDirectory(at: target, withIntermediateDirectories: false) }
        let originalLayer = Dictionary(uniqueKeysWithValues: ids.layers.map { ($1, $0) })
        let originalFrame = Dictionary(uniqueKeysWithValues: ids.frames.map { ($1, $0) })
        func layerID(_ id: UUID) -> String { originalLayer[id] ?? id.uuidString }
        func frameID(_ id: UUID) -> String { originalFrame[id] ?? id.uuidString }
        func writeTiles(_ tiles: [TileKey: CGImage], _ relative: String) throws {
            guard !tiles.isEmpty else { return }
            let directory = target.appendingPathComponent(relative)
            try fm.createDirectory(at: directory, withIntermediateDirectories: true)
            for (key, image) in tiles { try Self.writePNG(image, to: directory.appendingPathComponent(key.fileName)) }
        }
        let previous = manifest["layers"] as? [JSONObject] ?? []
        // The current native snapshot is authoritative for raster pixels, including erased tiles.
        for path in ["layers", "animation/cels", "ios-photo-masks"] {
            let dir = target.appendingPathComponent(path)
            if fm.fileExists(atPath: dir.path) { try fm.removeItem(at: dir) }
        }
        manifest["schemaVersion"] = 12; manifest["id"] = state.id.uuidString
        manifest["name"] = state.name; manifest["widthPx"] = state.width; manifest["heightPx"] = state.height
        manifest["modifiedAtMillis"] = Int64(state.modified.timeIntervalSince1970 * 1000)
        manifest["selectedLayerId"] = state.selectedLayer.map(layerID)
        manifest["backgroundColor"] = Int32(bitPattern: state.backgroundARGB ?? 0xffffffff)
        manifest["backgroundOpacity"] = state.backgroundOpacity ?? 1
        manifest["layers"] = try state.layers.map { layer -> JSONObject in
            let id = layerID(layer.id)
            var raw = previous.first(where: { $0["id"] as? String == id }) ?? [:]
            raw["id"] = id; raw["kind"] = "raster"; raw["name"] = layer.name
            raw["visible"] = layer.visible; raw["opacity"] = layer.opacity; raw["blendMode"] = layer.blendMode
            raw["clipSourceId"] = layer.clipSource.map(layerID)
            raw["groupId"] = layer.editGroup.map { id in ids.layerGroups?.first(where: { $0.value == id })?.key ?? id.uuidString }
            raw["iosLocked"] = layer.locked; raw["iosAlphaLocked"] = layer.alphaLocked; raw["iosReference"] = layer.reference
            if let text = layer.colorizeMask {
                guard var recipe = try JSONSerialization.jsonObject(with: Data(text.utf8)) as? JSONObject else { throw PackageIO.invalid }
                for field in ["lineArtId", "hintsId"] {
                    guard let value = recipe[field] as? String, let uuid = UUID(uuidString: value) else { throw PackageIO.invalid }
                    recipe[field] = layerID(uuid)
                }
                raw["colorizeMask"] = recipe
            } else { raw.removeValue(forKey: "colorizeMask") }
            if let photo = layer.photo {
                guard let source = photo.source else { throw PackageIO.invalid }
                raw.removeValue(forKey: "warp")
                raw.merge(Self.exportPhoto(photo)) { _, new in new }
                raw["kind"] = "image"; raw["originalWidthPx"] = photo.width; raw["originalHeightPx"] = photo.height
                let assets = target.appendingPathComponent("assets")
                try fm.createDirectory(at: assets, withIntermediateDirectories: true)
                try source.png.write(to: assets.appendingPathComponent(id + ".source"), options: .atomic)
                try writeTiles(snapshot.pixels[layer.id] ?? [:], "layers/\(id)/mask")
            } else { try writeTiles(snapshot.pixels[layer.id] ?? [:], "layers/\(id)/tiles") }
            return raw
        }
        let flags = state.layers.reduce(into: JSONObject()) { result, layer in
            if layer.locked || layer.alphaLocked || layer.reference {
                result[layerID(layer.id)] = ["iosLocked": layer.locked, "iosAlphaLocked": layer.alphaLocked, "iosReference": layer.reference]
            }
        }
        let settings = target.appendingPathComponent("ios-layer-settings.json")
        if !flags.isEmpty { try PackageIO.write(flags, at: settings) }
        else if fm.fileExists(atPath: settings.path) { try fm.removeItem(at: settings) }
        if let timeline = state.animation {
            var raw = manifest["animation"] as? JSONObject ?? [:]
            let priorFrames = (raw["frames"] as? [JSONObject] ?? []).reduce(into: [String: JSONObject]()) { result, item in
                if let id = item["id"] as? String { result[id] = item }
            }
            let rasterIDs = Set(state.layers.filter { $0.photo == nil }.map(\.id))
            let masks = target.appendingPathComponent("ios-photo-masks")
            if state.layers.contains(where: { $0.photo != nil }) {
                try fm.createDirectory(at: masks, withIntermediateDirectories: true)
                try PackageIO.write(["frames": timeline.frames.map { frameID($0.id) }], at: masks.appendingPathComponent("frames.json"))
            }
            raw["imageCels"] = state.layers.compactMap { layer -> JSONObject? in
                guard let original = layer.photo else { return nil }
                let poses = timeline.frames.compactMap { frame -> JSONObject? in
                    var photo = original
                    if let cels = frame.photoCels {
                        guard let cel = cels[layer.id.uuidString] else { return nil }
                        photo.transform = cel.transform; photo.mesh = cel.mesh; photo.filtered = cel.filtered
                    }
                    var value = Self.exportPhoto(photo); value["frameId"] = frameID(frame.id)
                    return value
                }
                return ["layerId": layerID(layer.id), "frames": poses]
            }
            raw["backgroundLayerIds"] = (timeline.backgroundLayers ?? []).map(layerID).sorted()
            raw["fps"] = timeline.fps; raw["playbackMode"] = timeline.playbackMode; raw["selectedIndex"] = timeline.selectedIndex
            let wasOn = (raw["onionBefore"] as? Int ?? 0) > 0 || (raw["onionAfter"] as? Int ?? 0) > 0
            if wasOn != timeline.onionSkin {
                raw["onionBefore"] = timeline.onionSkin ? 1 : 0; raw["onionAfter"] = timeline.onionSkin ? 1 : 0
            }
            if raw["onionBefore"] == nil { raw["onionBefore"] = 0 }
            if raw["onionAfter"] == nil { raw["onionAfter"] = 0 }
            raw["frames"] = try timeline.frames.map { frame -> JSONObject in
                let id = frameID(frame.id)
                var item = priorFrames[id] ?? [:]
                item["id"] = id; item["exposure"] = frame.exposure
                item["sourceTimeUs"] = frame.sourceTimeUs; item["durationUs"] = frame.durationUs
                if let files = snapshot.coldFrames[frame.id] {
                    for (layer, tiles) in files where !tiles.isEmpty {
                        let base = rasterIDs.contains(layer) ? "animation/cels" : "ios-photo-masks"
                        let dir = target.appendingPathComponent("\(base)/\(layerID(layer))/\(id)")
                        try fm.createDirectory(at: dir, withIntermediateDirectories: true)
                        for (key, file) in tiles {
                            let output = dir.appendingPathComponent(key.fileName)
                            do { try fm.linkItem(at: file.url, to: output) }
                            catch { try fm.copyItem(at: file.url, to: output) }
                        }
                    }
                } else {
                    for (layer, tiles) in snapshot.frames[frame.id] ?? [:] {
                        let base = rasterIDs.contains(layer) ? "animation/cels" : "ios-photo-masks"
                        try writeTiles(tiles, "\(base)/\(layerID(layer))/\(id)")
                    }
                }
                return item
            }
            if let media = state.media {
                raw["media"] = try Self.exportMedia(media, prior: raw["media"] as? JSONObject,
                    canvas: CGSize(width: state.width, height: state.height), to: target, ids: ids)
                raw["mediaPositionUs"] = media.positionUs
            } else { raw.removeValue(forKey: "media"); raw.removeValue(forKey: "mediaPositionUs") }
            manifest["animation"] = raw
        } else { manifest.removeValue(forKey: "animation") }
        let replay = replayFolder(state.id)
        if fm.fileExists(atPath: replay.appendingPathComponent("index.json").path) {
            try fm.copyItem(at: replay, to: target.appendingPathComponent("ios-progress"))
            let tsr = target.appendingPathComponent("process.tsr")
            if fm.fileExists(atPath: tsr.path) {
                let anchor = base.appendingPathComponent("process-anchor.json")
                let count = fm.fileExists(atPath: anchor.path) ? try PackageIO.json(anchor)["count"] as? Int ?? 0 : 0
                // Conversion of an optional future or damaged replay must not block artwork transfer.
                // Its original bytes still occupy the destination unless conversion completes atomically.
                try? AndroidProcess.exportRecording(replay, to: tsr, original: tsr, after: count)
            } else { try AndroidProcess.exportRecording(replay, to: tsr) }
        }
        try PackageIO.write(manifest, at: target.appendingPathComponent("manifest.json"))
        try Self.writePNG(Self.thumbnail(snapshot), to: target.appendingPathComponent("thumbnail.png"))
    }
}

extension GalleryController: UIDocumentPickerDelegate {
    func chooseProjectPackage() {
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.data], asCopy: true)
        picker.delegate = self
        present(picker, animated: true)
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard let url = urls.first else { return }
        ProjectStore.shared.queue.async {
            let result = Result { try ProjectStore.shared.importPackageWithLayout(url) }
            DispatchQueue.main.async {
                switch result {
                case .success(let imported):
                    self.layout.order.insert(contentsOf: imported.layout.order, at: 0)
                    self.layout.stacks.merge(imported.layout.stacks) { _, new in new }
                    self.saveLayout()
                    self.reload()
                case .failure(let error): self.showError(error)
                }
            }
        }
    }
}

extension ProjectStore {
    static func importPhoto(_ raw: JSONObject, width: Int, height: Int, source: PhotoSource) throws -> PhotoLayerRecord {
        guard let x = raw["centerX"] as? Double, let y = raw["centerY"] as? Double,
              let scale = raw["scale"] as? Double, scale.isFinite, scale > 0 else { throw PackageIO.invalid }
        let angle = (raw["rotationDegrees"] as? Double ?? 0) * .pi / 180
        let sx = scale * (raw["stretchX"] as? Double ?? 1), sy = scale * (raw["stretchY"] as? Double ?? 1)
        let transform = CGAffineTransform(translationX: x, y: y).rotated(by: angle)
            .scaledBy(x: sx, y: sy).translatedBy(x: -CGFloat(width) / 2, y: -CGFloat(height) / 2)
        var photo = PhotoLayerRecord(width: width, height: height, transform: transform, source: source)
        photo.filtered = raw["filtered"] as? Bool ?? true
        if let warp = raw["warp"] {
            guard let values = warp as? [Double], values.count == 578 else { throw PackageIO.invalid }
            photo.mesh = stride(from: 0, to: 578, by: 2).map { CGPoint(x: values[$0], y: values[$0 + 1]) }
        }
        try photo.validate()
        return photo
    }

    static func exportPhoto(_ photo: PhotoLayerRecord) -> JSONObject {
        let t = photo.transform, width = CGFloat(photo.width), height = CGFloat(photo.height)
        let center = CGPoint(x: width / 2, y: height / 2).applying(t)
        let angle = atan2(t.b, t.a), sx = hypot(t.a, t.b), sy = (t.a * t.d - t.b * t.c) / sx
        var raw: JSONObject = ["centerX": center.x, "centerY": center.y, "rotationDegrees": angle * 180 / .pi,
                               "scale": 1.0, "stretchX": sx, "stretchY": sy, "filtered": photo.filtered ?? true]
        // Rotation plus independent scales cannot express shear. Keep its exact affine
        // contribution in the same normalized 17×17 mesh understood by both renderers.
        if photo.mesh != nil || abs(t.a * t.c + t.b * t.d) > 1e-9 {
            let inverse = CGAffineTransform(translationX: center.x, y: center.y).rotated(by: angle)
                .scaledBy(x: sx * width, y: sy * height).translatedBy(x: -0.5, y: -0.5).inverted()
            raw["warp"] = (0..<289).flatMap { i -> [Double] in
                let p = photo.geometry.vertex(i).applying(inverse)
                return [Double(p.x), Double(p.y)]
            }
        }
        return raw
    }
}

private extension ProjectStore {
    static func importMedia(_ media: JSONObject, position: Int64, from directory: URL, ids: inout PackageIDs) throws -> MediaTrack {
        let sequence = media["kind"] as? String == "sequence"
        let assets = sequence ? media["assets"] as? [String: JSONObject] : ["original": media]
        guard let assets, (1...1000).contains(assets.count) else { throw PackageIO.invalid }
        var assetMap: [String: UUID] = [:], clipMap: [String: UUID] = [:]
        var records: [MediaAssetRecord] = []
        let typed = try PackageIO.temporaryFolder()
        defer { try? PackageIO.fm.removeItem(at: typed) }
        for (id, info) in assets {
            guard PackageIO.safeID(id), id.utf8.count <= 100,
                  let kind = info["kind"] as? String, ["video", "gif", "image"].contains(kind),
                  let duration = (info["durationUs"] as? NSNumber)?.int64Value, (1...86_400_000_000).contains(duration) else { throw PackageIO.invalid }
            let mapped = UUID(uuidString: id) ?? UUID()
            assetMap[id] = mapped
            var file = directory.appendingPathComponent(id == "original" ? "assets/media/timeline.source" : "assets/media/clips/\(id).source")
            if id == "original", !PackageIO.fm.fileExists(atPath: file.path) { file = directory.appendingPathComponent("assets/timeline.source") }
            let ext = info["iosExtension"] as? String ?? (kind == "video" ? "mp4" : kind == "gif" ? "gif" : "png")
            guard ext.range(of: "^[a-z0-9]{1,10}$", options: .regularExpression) != nil else { throw PackageIO.invalid }
            let input = typed.appendingPathComponent(mapped.uuidString).appendingPathExtension(ext)
            try PackageIO.fm.linkItem(at: file, to: input)
            let source = try LocalMediaFile(input)
            let record = MediaAssetRecord(id: mapped, name: info["iosName"] as? String ?? id,
                kind: kind, durationUs: duration, frameRate: info["frameRate"] as? Double ?? (kind == "gif" ? 50 : 30),
                fileExtension: ext, source: source)
            // Reject unplayable assets before publishing any part of the package.
            _ = try mediaSize(record)
            records.append(record)
        }
        let clips: [MediaClipRecord]
        if sequence {
            guard let list = media["edit"] as? [JSONObject], (1...1000).contains(list.count) else { throw PackageIO.invalid }
            clips = try list.map { raw in
                guard let key = raw["id"] as? String, PackageIO.safeID(key), clipMap[key] == nil,
                      let assetID = raw["assetId"] as? String, let mapped = assetMap[assetID],
                      let name = raw["name"] as? String, let start = (raw["sourceInUs"] as? NSNumber)?.int64Value,
                      let duration = (raw["durationUs"] as? NSNumber)?.int64Value, let keys = raw["keyframes"] as? [JSONObject],
                      keys.count <= 1000 else { throw PackageIO.invalid }
                let id = UUID(uuidString: key) ?? UUID(); clipMap[key] = id
                return MediaClipRecord(id: id, assetID: mapped, name: name, sourceInUs: start,
                    durationUs: duration, transitionUs: (raw["transitionUs"] as? NSNumber)?.int64Value ?? 0,
                    keyframes: try JSONDecoder().decode([MediaPoseKey].self, from: JSONSerialization.data(withJSONObject: keys)))
            }
        } else {
            guard let first = records.first else { throw PackageIO.invalid }
            clips = [MediaClipRecord(assetID: first.id, name: first.name, durationUs: first.durationUs)]
        }
        let track = MediaTrack(assets: records, clips: clips, positionUs: position)
        let plan = try track.plan()
        guard position >= 0, position < plan.durationUs,
              (media["durationUs"] as? NSNumber)?.int64Value == plan.durationUs,
              Set(assetMap.values).count == assetMap.count, Set(clipMap.values).count == clipMap.count else { throw PackageIO.invalid }
        ids.mediaAssets = assetMap; ids.clips = clipMap
        return track
    }

    static func mediaSize(_ asset: MediaAssetRecord) throws -> CGSize {
        guard let url = asset.source?.url else { throw PackageIO.invalid }
        let size: CGSize
        if asset.kind == "video" {
            guard let track = AVURLAsset(url: url).tracks(withMediaType: .video).first else {
                throw DrawingError.message("This video codec cannot be opened on this device.")
            }
            let rect = CGRect(origin: .zero, size: track.naturalSize).applying(track.preferredTransform)
            size = rect.size
        } else {
            guard let reader = CGImageSourceCreateWithURL(url as CFURL, nil),
                  let props = CGImageSourceCopyPropertiesAtIndex(reader, 0, nil) as? [CFString: Any],
                  let width = props[kCGImagePropertyPixelWidth] as? Int,
                  let height = props[kCGImagePropertyPixelHeight] as? Int else { throw PackageIO.invalid }
            size = CGSize(width: width, height: height)
        }
        guard size.width >= 1, size.width <= 8192, size.height >= 1, size.height <= 8192, size.width * size.height <= 16_777_216 else {
            throw DrawingError.message("This clip exceeds the portable video dimensions.")
        }
        return size
    }

    static func exportMedia(_ track: MediaTrack, prior: JSONObject?, canvas: CGSize, to directory: URL, ids: PackageIDs) throws -> JSONObject {
        let plan = try track.plan()
        var assetMap: [UUID: String] = [:]
        for (key, id) in ids.mediaAssets ?? [:] { assetMap[id] = key }
        let original = track.assets.first?.id
        var assets: [String: JSONObject] = [:]
        let movieDir = directory.appendingPathComponent("assets/media")
        if PackageIO.fm.fileExists(atPath: movieDir.path) { try PackageIO.fm.removeItem(at: movieDir) }
        let clipsDir = movieDir.appendingPathComponent("clips")
        try PackageIO.fm.createDirectory(at: clipsDir, withIntermediateDirectories: true)
        for asset in track.assets {
            let id = assetMap[asset.id] ?? ((asset.id == original && !assetMap.values.contains("original")) ? "original" : asset.id.uuidString)
            assetMap[asset.id] = id
            guard let file = asset.source?.url else { throw PackageIO.invalid }
            let target = id == "original" ? movieDir.appendingPathComponent("timeline.source") : clipsDir.appendingPathComponent(id + ".source")
            do { try PackageIO.fm.linkItem(at: file, to: target) } catch { try PackageIO.fm.copyItem(at: file, to: target) }
            let size = try mediaSize(asset)
            let originalInfo = (prior?["assets"] as? [String: JSONObject])?[id] ?? (id == "original" && prior?["kind"] as? String != "sequence" ? prior : nil) ?? [:]
            var info = originalInfo
            info.merge(["kind": asset.kind, "width": Int(size.width), "height": Int(size.height),
                          "durationUs": asset.durationUs, "frameRate": min(120, max(1, asset.frameRate)), "canvasScale": originalInfo["canvasScale"] as? Double ?? 1,
                        "iosExtension": asset.fileExtension, "iosName": asset.name]) { _, new in new }
            assets[id] = info
        }
        let oldClips = Dictionary(uniqueKeysWithValues: (ids.clips ?? [:]).map { ($1, $0) })
        let clips = try track.clips.map { clip -> JSONObject in
            guard let asset = assetMap[clip.assetID] else { throw PackageIO.invalid }
            return ["id": oldClips[clip.id] ?? clip.id.uuidString, "assetId": asset, "name": clip.name,
                    "sourceInUs": clip.sourceInUs, "durationUs": clip.durationUs, "transitionUs": clip.transitionUs,
                    "keyframes": try JSONSerialization.jsonObject(with: JSONEncoder().encode(clip.keyframes))]
        }
        return ["kind": "sequence", "width": prior?["width"] as? Int ?? Int(canvas.width),
                "height": prior?["height"] as? Int ?? Int(canvas.height), "durationUs": plan.durationUs,
                "frameRate": prior?["frameRate"] as? Double ?? (track.assets.first?.frameRate ?? 30),
                "canvasScale": prior?["canvasScale"] as? Double ?? 1, "assets": assets, "edit": clips]
    }
}
