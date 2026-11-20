import UIKit
import ImageIO
import UniformTypeIdentifiers
import ZIPFoundation

/// Android project packages contain independent PNG tiles and the original manifest.
/// Keep platform-specific manifest fields with imported drawings so exporting an edit
/// preserves metadata (group IDs, color usage, gallery orientation and process history).
private typealias JSONObject = [String: Any]

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
}

extension ProjectStore {
    private func packageBase(_ id: UUID) -> URL {
        root.appendingPathComponent(id.uuidString).appendingPathComponent("exchange", isDirectory: true)
    }

    func copyPackageBase(from id: UUID, to copy: UUID) throws {
        let src = packageBase(id)
        if FileManager.default.fileExists(atPath: src.path) { try FileManager.default.copyItem(at: src, to: packageBase(copy)) }
    }

    func importPackage(_ input: URL) throws -> [UUID] {
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
            return installed
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
              (16...4096).contains(w), (16...4096).contains(h), (1...32).contains(layers.count) else {
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
        func readTiles(_ url: URL, width: Int, height: Int) throws -> [TileKey: CGImage] {
            guard FileManager.default.fileExists(atPath: url.path) else { return [:] }
            var result: [TileKey: CGImage] = [:]
            for file in try FileManager.default.contentsOfDirectory(at: url, includingPropertiesForKeys: nil) {
                let indices = file.deletingPathExtension().lastPathComponent.components(separatedBy: "_")
                guard file.pathExtension == "png", indices.count == 2, let x = Int(indices[0]), let y = Int(indices[1]),
                      x >= 0, x < (width + 255) / 256, y >= 0, y < (height + 255) / 256,
                      tileCount < RasterDocument.maximumTiles else { throw PackageIO.invalid }
                let key = TileKey(x: x, y: y)
                guard key.fileName == file.lastPathComponent, result[key] == nil,
                      let source = CGImageSourceCreateWithURL(file as CFURL, nil),
                      let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
                      properties[kCGImagePropertyPixelWidth] as? Int == 256,
                      properties[kCGImagePropertyPixelHeight] as? Int == 256,
                      let image = CGImageSourceCreateImageAtIndex(source, 0, [kCGImageSourceShouldCacheImmediately: true] as CFDictionary)
                else { throw PackageIO.invalid }
                result[key] = image; tileCount += 1
            }
            return result
        }
        var pixels: [UUID: [TileKey: CGImage]] = [:]
        state.layers = try layers.map { raw in
            let key = try identifier(raw)
            var layer = LayerRecord()
            guard let native = ids.layers[key], let name = raw["name"] as? String else { throw PackageIO.invalid }
            layer.id = native; layer.name = name; layer.visible = raw["visible"] as? Bool ?? true
            layer.opacity = raw["opacity"] as? Double ?? 1; layer.blendMode = raw["blendMode"] as? String ?? "NORMAL"
            layer.locked = raw["iosLocked"] as? Bool ?? false
            layer.alphaLocked = raw["iosAlphaLocked"] as? Bool ?? false
            layer.reference = raw["iosReference"] as? Bool ?? false
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
        if let raw = manifest["animation"] as? JSONObject {
            guard raw["media"] == nil || raw["media"] is NSNull,
                  (raw["imageCels"] as? [JSONObject] ?? []).allSatisfy({ ($0["frames"] as? [JSONObject])?.isEmpty == true }) else {
                throw DrawingError.message("This animation contains media or animated image poses that cannot yet be exchanged losslessly.")
            }
            guard let list = raw["frames"] as? [JSONObject], (1...240).contains(list.count) else { throw PackageIO.invalid }
            var timeline = AnimationRecord()
            timeline.backgroundLayers = try Set((raw["backgroundLayerIds"] as? [String] ?? []).map {
                guard let id = ids.layers[$0] else { throw PackageIO.invalid }
                return id
            })
            timeline.fps = raw["fps"] as? Int ?? 12; timeline.playbackMode = raw["playbackMode"] as? String ?? "LOOP"
            timeline.selectedIndex = raw["selectedIndex"] as? Int ?? 0
            guard list.indices.contains(timeline.selectedIndex) else { throw PackageIO.invalid }
            timeline.onionSkin = (raw["onionBefore"] as? Int ?? 1) > 0 || (raw["onionAfter"] as? Int ?? 1) > 0
            timeline.frames = try list.enumerated().map { index, item in
                let key = try identifier(item)
                guard ids.frames[key] == nil else { throw PackageIO.invalid }
                var frame = AnimationFrameRecord(); frame.id = UUID(uuidString: key) ?? UUID()
                frame.exposure = item["exposure"] as? Int ?? 1; ids.frames[key] = frame.id
                for (layer, native) in ids.layers {
                    // The selected frame's visible pixels live in layers/<id>/tiles; a
                    // promoted background can also have an inactive cel at that frame.
                    if index != timeline.selectedIndex || timeline.sharedLayerIDs.contains(native) {
                        let tiles = try readTiles(directory.appendingPathComponent("animation/cels/\(layer)/\(key)"), width: w, height: h)
                        if !tiles.isEmpty { frames[frame.id, default: [:]][native] = tiles }
                    }
                }
                return frame
            }
            guard Set(ids.frames.values).count == ids.frames.count else { throw PackageIO.invalid }
            state.animation = timeline
        }
        guard Set(ids.layers.values).count == ids.layers.count else { throw PackageIO.invalid }
        let snapshot = RasterDocument(snapshot: DocumentSnapshot(record: state, pixels: pixels, frames: frames)).snapshot()
        try save(snapshot)
        let replay = directory.appendingPathComponent("ios-progress")
        if FileManager.default.fileExists(atPath: replay.path) {
            _ = try ProcessReplay(folder: replay)
            try FileManager.default.moveItem(at: replay, to: replayFolder(state.id))
        }
        let base = packageBase(state.id)
        try FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        // Keep ancillary files and platform metadata. Native revisions own the editable tile content.
        try FileManager.default.moveItem(at: directory, to: base.appendingPathComponent("original"))
        try JSONEncoder().encode(ids).write(to: base.appendingPathComponent("ids.json"))
        if let group = (gallery["items"] as? [JSONObject])?.first(where: {
            $0["kind"] as? String == "stack" && ($0["drawingIds"] as? [String])?.contains(original) == true
        }) { try PackageIO.write(group, at: base.appendingPathComponent("group.json")) }
        return state.id
    }
}

extension ProjectStore {
    func exportPackage(_ drawings: [UUID]) throws -> URL {
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
        for path in ["layers", "animation/cels"] {
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
        if let timeline = state.animation {
            var raw = manifest["animation"] as? JSONObject ?? [:]
            let priorFrames = raw["frames"] as? [JSONObject] ?? []
            raw["backgroundLayerIds"] = (timeline.backgroundLayers ?? []).map(layerID).sorted()
            raw["fps"] = timeline.fps; raw["playbackMode"] = timeline.playbackMode; raw["selectedIndex"] = timeline.selectedIndex
            let wasOn = (raw["onionBefore"] as? Int ?? 0) > 0 || (raw["onionAfter"] as? Int ?? 0) > 0
            if wasOn != timeline.onionSkin {
                raw["onionBefore"] = timeline.onionSkin ? 1 : 0; raw["onionAfter"] = timeline.onionSkin ? 1 : 0
            }
            if raw["onionBefore"] == nil { raw["onionBefore"] = 0 }
            if raw["onionAfter"] == nil { raw["onionAfter"] = 0 }
            raw["frames"] = try timeline.frames.enumerated().map { index, frame -> JSONObject in
                let id = frameID(frame.id)
                var item = priorFrames.first(where: { $0["id"] as? String == id }) ?? [:]
                item["id"] = id; item["exposure"] = frame.exposure
                let cels = snapshot.frames[frame.id] ?? [:]
                for (layer, tiles) in cels where state.layers.contains(where: { $0.id == layer && $0.photo == nil }) { try writeTiles(tiles, "animation/cels/\(layerID(layer))/\(id)") }
                return item
            }
            manifest["animation"] = raw
        } else { manifest.removeValue(forKey: "animation") }
        let replay = replayFolder(state.id)
        if fm.fileExists(atPath: replay.appendingPathComponent("index.json").path) {
            try fm.copyItem(at: replay, to: target.appendingPathComponent("ios-progress"))
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
            let result = Result { try ProjectStore.shared.importPackage(url) }
            DispatchQueue.main.async {
                switch result {
                case .success: self.reload()
                case .failure(let error): self.showError(error)
                }
            }
        }
    }
}

private extension ProjectStore {
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
