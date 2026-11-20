import UIKit
import ImageIO
import Compression

/// TipStroke-process-1/2/3 are DataInputStream containers of independently gzipped actions.
private struct ReplayBytes {
    let data: Data
    var at = 0
    mutating func take(_ count: Int) throws -> Data {
        guard count >= 0, count <= data.count - at else { throw AndroidProcess.invalid }
        defer { at += count }
        return data.subdata(in: at..<(at + count))
    }
    mutating func byte() throws -> Int {
        guard at < data.count else { throw AndroidProcess.invalid }
        defer { at += 1 }; return Int(data[at])
    }
    mutating func integer() throws -> Int {
        let a = try byte(), b = try byte(), c = try byte(), d = try byte()
        return Int(Int32(bitPattern: UInt32(a << 24 | b << 16 | c << 8 | d)))
    }
    mutating func text() throws -> String {
        let length = try byte() << 8 | byte()
        let raw = try take(length)
        // Java modified UTF-8 encodes supplementary characters as paired UTF-16 units.
        var units: [UInt16] = [], i = 0
        while i < raw.count {
            let a = raw[i]; i += 1
            if a < 128 { units.append(UInt16(a)) }
            else if a & 0xe0 == 0xc0, i < raw.count, raw[i] & 0xc0 == 0x80 {
                units.append(UInt16(a & 31) << 6 | UInt16(raw[i] & 63)); i += 1
            } else if a & 0xf0 == 0xe0, i + 1 < raw.count, raw[i] & 0xc0 == 0x80, raw[i+1] & 0xc0 == 0x80 {
                units.append(UInt16(a & 15) << 12 | UInt16(raw[i] & 63) << 6 | UInt16(raw[i+1] & 63)); i += 2
            } else { throw AndroidProcess.invalid }
        }
        return String(decoding: units, as: UTF16.self)
    }
}

private extension Data {
    mutating func appendInt(_ number: Int) {
        var n = Int32(number).bigEndian
        Swift.withUnsafeBytes(of: &n) { append(contentsOf: $0) }
    }
    mutating func appendASCII(_ string: String) throws {
        let bytes = Data(string.utf8)
        guard bytes.count < 65536, bytes.allSatisfy({ $0 > 0 && $0 < 128 }) else { throw AndroidProcess.invalid }
        append(UInt8(bytes.count >> 8)); append(UInt8(bytes.count & 255)); append(bytes)
    }
    mutating func little(_ value: UInt32) {
        var n = value.littleEndian; Swift.withUnsafeBytes(of: &n) { append(contentsOf: $0) }
    }
}

enum AndroidProcess {
    static let invalid = DrawingError.message("Invalid or unsupported Android drawing-progress recording.")
    private static let crcTable: [UInt32] = (0..<256).map {
        var value = UInt32($0)
        for _ in 0..<8 { value = value & 1 == 0 ? value >> 1 : (value >> 1) ^ 0xedb88320 }
        return value
    }
    private static func crc(_ data: Data) -> UInt32 {
        var value = UInt32.max
        for byte in data { value = (value >> 8) ^ crcTable[Int((value ^ UInt32(byte)) & 255)] }
        return value ^ UInt32.max
    }
    private static func gzip(_ plain: Data) throws -> Data {
        var compressed = [UInt8](repeating: 0, count: plain.count + plain.count / 8 + 128)
        let size = compressed.withUnsafeMutableBufferPointer { dst in plain.withUnsafeBytes { raw in
            compression_encode_buffer(dst.baseAddress!, dst.count, raw.bindMemory(to: UInt8.self).baseAddress!, plain.count, nil, COMPRESSION_ZLIB)
        } }
        guard size > 0 else { throw invalid }
        var result = Data([31, 139, 8, 0, 0, 0, 0, 0, 0, 255])
        result.append(contentsOf: compressed.prefix(size)); result.little(crc(plain)); result.little(UInt32(plain.count))
        return result
    }
    private static func inflate(_ gzip: Data) throws -> Data {
        guard gzip.count >= 18, gzip.prefix(4) == Data([31, 139, 8, 0]) else { throw invalid }
        func little(_ at: Int) -> UInt32 {
            UInt32(gzip[at]) | UInt32(gzip[at+1]) << 8 | UInt32(gzip[at+2]) << 16 | UInt32(gzip[at+3]) << 24
        }
        let count = Int(little(gzip.count - 4))
        guard count > 0, count <= 64 * 1024 * 1024 else { throw invalid }
        var output = Data(count: count + 1)
        let length = output.withUnsafeMutableBytes { dst in gzip.withUnsafeBytes { src in
            compression_decode_buffer(dst.bindMemory(to: UInt8.self).baseAddress!, count + 1,
                src.bindMemory(to: UInt8.self).baseAddress! + 10, gzip.count - 18, nil, COMPRESSION_ZLIB)
        } }
        guard length == count else { throw invalid }
        output.removeLast()
        guard crc(output) == little(gzip.count - 8) else { throw invalid }
        return output
    }

    static func exportRecording(_ folder: URL, to url: URL, original: URL? = nil, after: Int = 0) throws {
        let index = try ReplayFiles.index(folder)
        guard (0...index.count).contains(after) else { throw invalid }
        var actions: [Data] = [], total = 0, truncated = false
        if let original {
            guard after < index.count else { return }
            let length = try original.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
            guard length > 0, length <= ReplayFiles.limit + 200_000 else { return }
            var stream = ReplayBytes(data: try Data(contentsOf: original, options: .mappedIfSafe))
            // A newer unknown format or resolution remains intact, with new iOS edits
            // retained separately in ios-progress.
            guard ["TipStroke-process-1", "TipStroke-process-2", "TipStroke-process-3"].contains(try stream.text()),
                  try stream.integer() == index.width, try stream.integer() == index.height else { return }
            _ = try stream.text()
            let count = try stream.integer(); guard (0...ReplayFiles.maxSteps).contains(count) else { return }
            for _ in 0..<count {
                let bytes = try stream.integer()
                guard bytes > 0, bytes <= ReplayFiles.limit - total else { return }
                actions.append(try stream.take(bytes)); total += bytes
            }
            guard stream.at == stream.data.count else { return }
        }
        var previous: (UInt32, Double)?
        for i in after..<index.count {
            let step = try PropertyListDecoder().decode(ReplayStep.self, from: Data(contentsOf: ReplayFiles.step(folder, i)))
            var next: [Data] = []
            if i == after && original != nil {
                // Preserve the source action stream intact, then establish the iOS raster baseline.
                next.append(try gzip(Data([4])))
            }
            if step.reset && i > after {
                var clear = Data([5]); try clear.appendASCII("ios-process"); next.append(try gzip(clear))
            }
            if previous?.0 != step.paper || previous?.1 != step.opacity {
                let meta: JSONObject = ["layers": [["id": "ios-process", "visible": true, "opacity": 1.0, "blendMode": "NORMAL"]],
                                        "background": Int32(bitPattern: step.paper), "opacity": step.opacity]
                var state = Data([0]); try state.appendASCII(String(decoding: JSONSerialization.data(withJSONObject: meta), as: UTF8.self))
                next.append(try gzip(state)); previous = (step.paper, step.opacity)
            }
            if !step.patches.isEmpty {
                var data = Data([2]); try data.appendASCII("ios-process"); data.appendInt(step.patches.count)
                for patch in step.patches {
                    data.appendInt(patch.tile.x); data.appendInt(patch.tile.y); data.appendInt(patch.png?.count ?? 0)
                    if let png = patch.png { data.append(png) }
                }
                next.append(try gzip(data))
            }
            total += next.reduce(0) { $0 + $1.count }
            if total > ReplayFiles.limit || actions.count + next.count > ReplayFiles.maxSteps { truncated = true; break }
            actions += next
        }
        var out = Data()
        try out.appendASCII("TipStroke-process-3"); out.appendInt(index.width); out.appendInt(index.height)
        // The synthetic replay store is independent of the imported editable layers.
        // Appending directly on Android would lose the unchanged tiles at the first edit.
        try out.appendASCII(truncated ? "Recording stopped at the portable archive limit." : "iOS portable replay")
        out.appendInt(actions.count)
        for action in actions { out.appendInt(action.count); out.append(action) }
        try out.write(to: url, options: .atomic)
    }

    static func noteUnavailable(_ error: Error, in folder: URL) throws {
        // Opening a damaged or older recording must not discard editable artwork.
        // The original bytes are retained in the portable project. Start iOS capture
        // at the imported baseline and expose the reason in the progress viewer.
        try? ReplayFiles.fm.removeItem(at: folder)
        try ReplayFiles.fm.createDirectory(at: folder, withIntermediateDirectories: true)
        try Data(error.localizedDescription.prefix(512).utf8).write(to: folder.appendingPathComponent("import-note.txt"))
    }

    static func importRecording(_ file: URL, to folder: URL) throws {
        let size = try file.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
        guard (1...(ReplayFiles.limit + 200_000)).contains(size) else { throw invalid }
        var stream = ReplayBytes(data: try Data(contentsOf: file, options: .mappedIfSafe))
        guard ["TipStroke-process-1", "TipStroke-process-2", "TipStroke-process-3"].contains(try stream.text()) else { throw invalid }
        let width = try stream.integer(), height = try stream.integer(), reason = try stream.text(), count = try stream.integer()
        guard (16...4096).contains(width), (16...4096).contains(height), (1...ReplayFiles.maxSteps).contains(count) else { throw invalid }
        var index = ReplayIndex(width: width, height: height)
        try ReplayFiles.fm.createDirectory(at: folder, withIntermediateDirectories: true)
        let scene = AndroidReplayScene(width: width, height: height)
        var total = 0
        for _ in 0..<count {
            let action: Data = try autoreleasepool {
                let length = try stream.integer()
                guard length > 0, length <= ReplayFiles.limit - total else { throw invalid }; total += length
                var input = ReplayBytes(data: try inflate(stream.take(length)))
                let step = try scene.apply(&input)
                guard input.at == input.data.count else { throw invalid }
                let encoder = PropertyListEncoder(); encoder.outputFormat = .binary; return try encoder.encode(step)
            }
            guard action.count <= ReplayFiles.limit - index.bytes else {
                index.stopped = "The portable preview reached 32 MiB. The complete original recording is retained."
                break
            }
            try action.write(to: ReplayFiles.step(folder, index.count), options: .atomic)
            index.bytes += action.count; index.count += 1
        }
        guard index.count > 0, index.stopped != nil || stream.at == stream.data.count else { throw invalid }
        // Continue capturing iOS edits from the imported baseline, even when the old capture had stopped.
        if !reason.isEmpty { try Data(reason.prefix(512).utf8).write(to: folder.appendingPathComponent("import-note.txt")) }
        try ReplayFiles.write(index, folder)
    }
}

private final class AndroidReplayScene {
    private let width: Int, height: Int
    private var metadata: JSONObject = ["background": -1, "opacity": 1.0, "layers": []]
    private var ids: [String: UUID] = [:], images: [String: PhotoSource] = [:]
    private var stores: [String: [TileKey: CGImage]] = [:]
    init(width: Int, height: Int) { self.width = width; self.height = height }

    private func uuid(_ id: String) -> UUID {
        if let known = ids[id] { return known }
        let value = UUID(); ids[id] = value; return value
    }
    private func readImage(_ input: inout ReplayBytes, bytes: Int, tile: Bool) throws -> CGImage {
        guard bytes > 0, bytes <= (tile ? 1_048_576 : ReplayFiles.limit),
              let source = CGImageSourceCreateWithData(try input.take(bytes) as CFData, nil),
              let props = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let w = props[kCGImagePropertyPixelWidth] as? Int, let h = props[kCGImagePropertyPixelHeight] as? Int,
              (1...4096).contains(w), (1...4096).contains(h), !tile || (w == 256 && h == 256),
              let bitmap = CGImageSourceCreateImageAtIndex(source, 0, [kCGImageSourceShouldCacheImmediately: true] as CFDictionary) else {
            throw AndroidProcess.invalid
        }
        return bitmap
    }

    func apply(_ input: inout ReplayBytes) throws -> ReplayStep {
        var reset = false, dirty = Set<TileKey>(), dirtyStore: String?
        switch try input.byte() {
        case 0:
            guard let object = try JSONSerialization.jsonObject(with: Data(input.text().utf8)) as? JSONObject,
                  let layers = object["layers"] as? [JSONObject], layers.count <= 32 else { throw AndroidProcess.invalid }
            metadata = object; reset = true
            var keep = Set(object["stores"] as? [String] ?? [])
            keep.formUnion(layers.compactMap { $0["id"] as? String })
            stores = stores.filter { keep.contains($0.key) || ($0.key.hasSuffix(":mask") && keep.contains(String($0.key.dropLast(5)))) }
            images = images.filter { keep.contains($0.key) }
        case 1:
            throw DrawingError.message("This recording uses Android brush commands. Export the project again with an updated Android app for portable playback.")
        case 2:
            let id = try input.text(), count = try input.integer()
            guard (0...1024).contains(count) else { throw AndroidProcess.invalid }
            dirtyStore = id
            for _ in 0..<count {
                let x = try input.integer(), y = try input.integer(), bytes = try input.integer()
                guard (0..<32).contains(x), (0..<32).contains(y) else { throw AndroidProcess.invalid }
                let key = TileKey(x: x, y: y)
                stores[id, default: [:]][key] = bytes == 0 ? nil : try readImage(&input, bytes: bytes, tile: true)
                dirty.insert(key)
                let memory = stores.values.reduce(0) { $0 + $1.count * RasterDocument.tileBytes } + images.values.reduce(0) { $0 + $1.bytes }
                guard memory <= 128 * 1024 * 1024 else { throw DrawingError.message("This recording exceeds the replay memory budget.") }
            }
        case 3:
            let id = try input.text(), length = try input.integer()
            images[id] = try PhotoSource(image: readImage(&input, bytes: length, tile: false))
            guard images.values.reduce(0, { $0 + $1.bytes }) <= 128 * 1024 * 1024 else { throw AndroidProcess.invalid }
            reset = true
        case 4: stores.removeAll(); images.removeAll(); reset = true
        case 5: stores.removeValue(forKey: try input.text()); reset = true
        default: throw AndroidProcess.invalid
        }

        var record = DocumentRecord(); record.width = width; record.height = height
        var pixels: [UUID: [TileKey: CGImage]] = [:]
        record.layers = try (metadata["layers"] as? [JSONObject] ?? []).compactMap { raw in
            guard let id = raw["id"] as? String else { throw AndroidProcess.invalid }
            var layer = LayerRecord(); layer.id = uuid(id); layer.visible = raw["visible"] as? Bool ?? true
            layer.opacity = raw["opacity"] as? Double ?? 1; layer.blendMode = raw["blendMode"] as? String ?? "NORMAL"
            layer.clipSource = (raw["clipSourceId"] as? String).map(uuid)
            guard layer.opacity.isFinite, (0...1).contains(layer.opacity), RasterDocument.blendModes[layer.blendMode] != nil else {
                throw AndroidProcess.invalid
            }
            if raw["image"] as? Bool == true {
                guard let source = images[id] else { return nil }
                var pose = raw; pose["centerX"] = raw["x"]; pose["centerY"] = raw["y"]; pose["rotationDegrees"] = raw["rotation"]
                let photo = try ProjectStore.importPhoto(pose, width: source.image.width, height: source.image.height, source: source)
                layer.photo = photo
                pixels[layer.id] = stores[id + ":mask"] ?? [:]
                if reset || dirtyStore == id + ":mask" { dirty.formUnion(TileKey.intersecting(photo.bounds, width: width, height: height)) }
            } else {
                let store = raw["store"] as? String ?? id
                let tiles = stores[store] ?? [:]
                pixels[layer.id] = tiles
                if reset { dirty.formUnion(tiles.keys) }
            }
            return layer
        }
        let snapshot = DocumentSnapshot(record: record, pixels: pixels)
        let patches: [ReplayPatch] = try dirty.filter { $0.x < (width+255)/256 && $0.y < (height+255)/256 }.map { key in
            try autoreleasepool {
                let context = try RasterDocument.context(width: 256, height: 256)
                context.translateBy(x: -key.rect.minX, y: -key.rect.minY)
                try RasterDocument.composite(snapshot, in: context, visible: key.rect)
                guard let image = context.makeImage() else { throw AndroidProcess.invalid }
                return ReplayPatch(tile: key, png: try ReplayFiles.png(image))
            }
        }
        let bg = (metadata["background"] as? NSNumber)?.uint32Value ?? 0xffffffff
        let alpha = metadata["opacity"] as? Double ?? 1
        guard alpha.isFinite, (0...1).contains(alpha) else { throw AndroidProcess.invalid }
        return ReplayStep(reset: reset, paper: bg, opacity: alpha, patches: patches)
    }
}
