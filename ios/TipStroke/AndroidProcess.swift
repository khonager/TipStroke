import UIKit
import TipStrokeShared
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
    mutating func number() throws -> CGFloat {
        let value = Float(bitPattern: UInt32(truncatingIfNeeded: try integer()))
        guard value.isFinite else { throw AndroidProcess.invalid }
        return CGFloat(value)
    }
    mutating func time() throws -> Double {
        let hi = try integer(), lo = UInt32(truncatingIfNeeded: try integer())
        return Double(hi) * 4294967296 + Double(lo)
    }
    mutating func count(_ max: Int) throws -> Int {
        let n = try integer()
        guard (0...max).contains(n) else { throw AndroidProcess.invalid }
        return n
    }
    mutating func flag() throws -> Bool {
        let value = try byte(); guard value < 2 else { throw AndroidProcess.invalid }; return value == 1
    }
    mutating func point() throws -> CGPoint {
        let x = try number(), y = try number()
        guard abs(x) <= 32768, abs(y) <= 32768 else { throw AndroidProcess.invalid }
        return CGPoint(x: x, y: y)
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
        guard (16...8192).contains(width), (16...8192).contains(height), (1...ReplayFiles.maxSteps).contains(count) else { throw invalid }
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
            let id = try input.text(), count = try input.count(250_000)
            guard count > 0 else { throw AndroidProcess.invalid }
            let doc = RasterDocument(name: "Replay", width: width, height: height)
            let base = doc.activeLayer
            doc.pixels[base] = stores[id] ?? [:]
            let overlay = RasterDocument(name: "Overlay", width: width, height: height)
            for _ in 0..<count {
                let command = try LegacyReplayPaint(input: &input)
                let target = command.overlay ? overlay : doc
                let rect = try command.draw(in: target)
                dirty.formUnion(TileKey.intersecting(rect, width: width, height: height))
            }
            var tiles = doc.pixels[base] ?? [:]
            for (key, top) in overlay.pixels[overlay.activeLayer] ?? [:] {
                let ctx = try RasterDocument.context(width: 256, height: 256)
                let box = CGRect(x: 0, y: 0, width: 256, height: 256)
                if let old = tiles[key] { RasterDocument.draw(old, in: ctx, rect: box) }
                RasterDocument.draw(top, in: ctx, rect: box)
                guard let tile = ctx.makeImage() else { throw AndroidProcess.invalid }
                tiles[key] = tile
            }
            stores[id] = tiles; dirtyStore = id
            guard stores.values.reduce(0, { $0 + $1.count * RasterDocument.tileBytes }) + images.values.reduce(0, { $0 + $1.bytes }) <= 128 * 1024 * 1024 else { throw AndroidProcess.invalid }
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

/// Decoder for the frozen DataOutputStream ReplayCodec.writePaint v1 layout.
private struct LegacyReplayPaint {
    struct Sample {
        var point: CGPoint
        var pressure: CGFloat
        var tilt: CGFloat
        var angle: CGFloat
        var seconds: Double
        var opacityPressure: CGFloat
    }
    let overlay: Bool
    let capStart: Bool, capEnd: Bool, skipFirst: Bool
    let brush: NativeBrush
    var size: CGFloat
    let opacity: CGFloat
    var color: UIColor
    let erases: Bool
    let pixel: String
    let selection: CGPath?
    var samples: [Sample] = []

    init(input: inout ReplayBytes) throws {
        guard try input.integer() == 1 else { throw AndroidProcess.invalid }
        overlay = try input.flag(); capStart = try input.flag(); capEnd = try input.flag(); skipFirst = try input.flag()
        var fields: JSONObject = ["format":"tipstroke-brush", "version":2, "author":"", "description":""]
        fields["schemaVersion"] = try input.integer()
        for key in ["id","name","engine"] { fields[key] = try input.text() }
        for key in ["size","opacity","hardness","spacing","stabilization","pressureSizeStart","pressureSizeEnd",
                    "pressureSizeExponent","pressureOpacityStart","pressureOpacityEnd","pressureOpacityExponent",
                    "speedTaper","pointSize","tiltSensitivity","shadeSize","shadeOpacity","grain"] {
            fields[key] = try input.number()
        }
        fields["tiltMode"] = try input.text(); fields["shadeStart"] = try input.number(); fields["shadeTransition"] = try input.number()
        let json = try JSONSerialization.data(withJSONObject: fields)
        guard let preset = NativeBrush.companion.fromPackage(data: String(decoding: json, as: UTF8.self)) else { throw AndroidProcess.invalid }
        brush = preset
        size = try input.number(); opacity = try input.number()
        guard size > 0, size <= 65536, (0...1).contains(opacity) else { throw AndroidProcess.invalid }
        let channels = try (0..<4).map { _ in try input.number() }
        guard channels.allSatisfy({ (0...1).contains($0) }) else { throw AndroidProcess.invalid }
        color = UIColor(red: channels[0], green: channels[1], blue: channels[2], alpha: channels[3])
        let blend = try input.text()
        guard ["PAINT","ERASE"].contains(blend) else { throw AndroidProcess.invalid }
        erases = blend == "ERASE"; pixel = try input.text()
        guard ["","PENCIL","LINE","DITHER"].contains(pixel) else { throw AndroidProcess.invalid }
        let vertices = try input.count(100_000)
        if vertices > 0 {
            let path = CGMutablePath()
            for i in 0..<vertices {
                let p = try input.point()
                if i == 0 { path.move(to: p) } else { path.addLine(to: p) }
            }
            path.closeSubpath(); selection = path
        } else { selection = nil }
        let count = try input.count(250_000)
        guard count > 0 else { throw AndroidProcess.invalid }
        for _ in 0..<count {
            _ = try input.integer()
            let p = try input.point(), pressure = try input.number(), tilt = try input.number(), angle = try input.number()
            let time = try input.time() / 1e9
            _ = try input.integer(); _ = try input.text()
            samples.append(Sample(point: p, pressure: pressure, tilt: tilt, angle: angle, seconds: time, opacityPressure: try input.number()))
        }
        if try input.flag() {
            guard try input.text() == "androidx-ink-1.1.0-alpha08" else { throw AndroidProcess.invalid }
            let argb = UInt32(truncatingIfNeeded: try input.integer())
            color = UIColor(red: CGFloat((argb >> 16) & 255)/255, green: CGFloat((argb >> 8) & 255)/255,
                            blue: CGFloat(argb & 255)/255, alpha: CGFloat(argb >> 24)/255)
            size = try input.number(); _ = try input.number(); _ = try input.integer()
            guard size > 0, size <= 65536, try input.number() == 0 else { throw AndroidProcess.invalid }
            let n = try input.count(250_000)
            var ink: [Sample] = []
            for i in 0..<n {
                _ = try input.integer()
                let p = try input.point(), time = try input.time() / 1000
                _ = try input.number()
                let pressure = try input.number(), tilt = try input.number(), angle = try input.number()
                var source = samples[min(samples.count - 1, i * samples.count / n)]
                source.point = p; source.seconds = time
                if (0...1).contains(pressure) { source.pressure = pressure }
                if tilt >= 0 { source.tilt = tilt }
                if angle >= 0 { source.angle = angle }
                ink.append(source)
            }
            if !ink.isEmpty { samples = ink }
        }
    }

    func draw(in doc: RasterDocument) throws -> CGRect {
        doc.selection = selection.map { CanvasSelection(path: $0) }
        guard doc.beginStroke() else { throw AndroidProcess.invalid }
        let coverage = StrokeCoverage(), graphite = GraphiteRenderer()
        let points = pixel == "LINE" ? [samples[0], samples[samples.count - 1]] : samples
        var previous: Sample?
        for (i, s) in points.enumerated() {
            defer { previous = s }
            if overlay && ((i == 0 && !capStart && !(points.count == 1 && capEnd)) || (i == 1 && skipFirst)) { continue }
            let start = previous?.point ?? s.point
            let velocity = hypot(s.point.x-start.x, s.point.y-start.y) / max(0.001, s.seconds - (previous?.seconds ?? s.seconds))
            let speed = 1 - CGFloat(brush.speedTaper) * min(0.85, velocity / 2500)
            let radius = max(0.25, size * speed * CGFloat(brush.sizeResponse(pressure: Float(s.pressure))) / 2)
            let alpha = opacity * CGFloat(brush.opacityResponse(pressure: Float(s.opacityPressure)))
            if !pixel.isEmpty {
                let width = max(1, min(32, Int(size.rounded())))
                let offset = CGFloat((width - 1) / 2)
                func grid(_ p: CGPoint) -> CGPoint { CGPoint(x: p.x.rounded() - offset, y: p.y.rounded() - offset) }
                try doc.paintPixelLine(from: grid(start), to: grid(s.point), size: width,
                    color: color.withAlphaComponent(opacity * color.cgColor.alpha), dither: pixel == "DITHER", erasing: erases)
            } else if brush.engine == "PENCIL" && !erases {
                _ = try graphite.paint(document: doc, brush: brush, point: s.point, size: size, pressure: s.pressure,
                    tilt: Float(s.tilt), angle: s.angle, speed: speed, color: color, opacity: opacity)
            } else {
                _ = try coverage.paint(document: doc, from: start, to: s.point, radius: radius,
                    alpha: alpha, hardness: erases || brush.engine == "AIRBRUSH" ? CGFloat(brush.hardness) : 1,
                    color: color, erasing: erases)
            }
        }
        let changed = doc.strokePreviewBounds
        doc.endStroke(cancelled: false)
        return changed
    }
}
