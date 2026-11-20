import UIKit
import ImageIO
import UniformTypeIdentifiers
import AVFoundation

private struct ReplayPatch: Codable {
    let tile: TileKey
    let png: Data?
}
private struct ReplayStep: Codable {
    let reset: Bool
    let paper: UInt32
    let opacity: Double
    let patches: [ReplayPatch]
}
private struct ReplayIndex: Codable {
    var version = 1
    let width: Int
    let height: Int
    var count = 0
    var bytes = 0
    var stopped: String?
}

private enum ReplayFiles {
    static let limit = 32 * 1024 * 1024
    static let maxSteps = 30_000
    static let fm = FileManager.default
    static func step(_ folder: URL, _ index: Int) -> URL {
        folder.appendingPathComponent(String(format: "%05d.action", index))
    }
    static func indexURL(_ folder: URL) -> URL { folder.appendingPathComponent("index.json") }
    static func index(_ folder: URL) throws -> ReplayIndex {
        let url = indexURL(folder)
        guard (try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0) <= 4096 else {
            throw DrawingError.message("Invalid drawing progress index.")
        }
        let value = try JSONDecoder().decode(ReplayIndex.self, from: Data(contentsOf: url))
        guard value.version == 1, (16...8192).contains(value.width), (16...8192).contains(value.height),
              (0...maxSteps).contains(value.count), (0...limit).contains(value.bytes) else {
            throw DrawingError.message("Unsupported drawing progress recording.")
        }
        return value
    }
    static func write(_ index: ReplayIndex, _ folder: URL) throws {
        try JSONEncoder().encode(index).write(to: indexURL(folder), options: .atomic)
    }
    static func png(_ image: CGImage) throws -> Data {
        let data = NSMutableData()
        guard let writer = CGImageDestinationCreateWithData(data as CFMutableData, UTType.png.identifier as CFString, 1, nil) else {
            throw DrawingError.message("Could not record drawing progress.")
        }
        CGImageDestinationAddImage(writer, image, nil)
        guard CGImageDestinationFinalize(writer) else { throw DrawingError.message("Could not encode a recording tile.") }
        return data as Data
    }
}

extension ProjectStore {
    func replayFolder(_ id: UUID) -> URL { root.appendingPathComponent(id.uuidString).appendingPathComponent("progress") }
}

/// Main-thread comparison of immutable tile references. The serial worker encodes only changed tiles.
final class ProcessRecorder {
    private let store: ProjectStore
    private let folder: URL
    private var last: DocumentSnapshot?
    private var pending = 0
    private var accepting = true
    private var index: ReplayIndex?
    private var failed = false // worker-owned; preserve a contiguous prefix
    init(store: ProjectStore, id: UUID) { self.store = store; folder = store.replayFolder(id) }

    func capture(_ document: RasterDocument) {
        guard accepting else { return }
        let current = DocumentSnapshot(record: document.record, pixels: document.pixels)
        let size = current.record
        if let last, last.record.width != size.width || last.record.height != size.height {
            stop("Recording stopped when the canvas was resized."); return
        }
        let keys = changedTiles(previous: last, current: current)
        let paperChanged = last?.record.backgroundARGB != size.backgroundARGB || last?.record.backgroundOpacity != size.backgroundOpacity
        guard last == nil || paperChanged || !keys.isEmpty else { return }
        last = current
        let cost = max(256, keys.count * RasterDocument.tileBytes)
        guard pending + cost <= 128 * 1024 * 1024 else { stop("Recording stopped at its encoding queue limit."); return }
        pending += cost
        store.queue.async {
            defer { DispatchQueue.main.async { self.pending -= cost } }
            guard !self.failed else { return }
            do {
                try self.open(width: size.width, height: size.height)
                guard var index = self.index, index.stopped == nil else { return }
                guard index.width == size.width, index.height == size.height else {
                    self.markStopped("Recording stopped when the canvas was resized."); return
                }
                let reset = self.needsBaseline
                // Reset the replay state when reopening, including after an interrupted app session.
                let selectedKeys = keys
                self.needsBaseline = false
                var patches: [ReplayPatch] = []
                var payloadBytes = 0
                for key in selectedKeys {
                    try autoreleasepool {
                        let context = try RasterDocument.context(width: 256, height: 256)
                        context.translateBy(x: -key.rect.minX, y: -key.rect.minY)
                        try RasterDocument.composite(current, in: context, visible: key.rect)
                        guard let image = context.makeImage(), let bytes = context.data?.assumingMemoryBound(to: UInt8.self) else {
                            throw DrawingError.message("Could not capture a recording tile.")
                        }
                        let empty = !(0..<(256 * 256)).contains { bytes[$0 * 4 + 3] != 0 }
                        let data = empty ? nil : try ReplayFiles.png(image)
                        payloadBytes += data?.count ?? 0
                        guard index.bytes + payloadBytes <= ReplayFiles.limit else { throw DrawingError.message("Recording reached 32 MiB.") }
                        patches.append(ReplayPatch(tile: key, png: data))
                    }
                }
                let step = ReplayStep(reset: reset, paper: size.backgroundARGB ?? 0xffffffff, opacity: size.backgroundOpacity ?? 1, patches: patches)
                let encoder = PropertyListEncoder(); encoder.outputFormat = .binary
                let encoded = try encoder.encode(step)
                guard index.count < ReplayFiles.maxSteps, index.bytes + encoded.count <= ReplayFiles.limit else {
                    self.markStopped("Recording reached its storage limit."); return
                }
                try encoded.write(to: ReplayFiles.step(self.folder, index.count), options: .atomic)
                index.count += 1; index.bytes += encoded.count
                try ReplayFiles.write(index, self.folder); self.index = index
            } catch { self.markStopped(error.localizedDescription) }
        }
    }
    private var needsBaseline = true
    private func open(width: Int, height: Int) throws {
        guard index == nil else { return }
        try ReplayFiles.fm.createDirectory(at: folder, withIntermediateDirectories: true)
        index = ReplayFiles.fm.fileExists(atPath: ReplayFiles.indexURL(folder).path) ? try ReplayFiles.index(folder) : ReplayIndex(width: width, height: height)
        if index?.stopped != nil {
            failed = true
            DispatchQueue.main.async { self.accepting = false; self.last = nil }
        }
    }
    private func stop(_ reason: String) {
        accepting = false; last = nil
        store.queue.async { self.markStopped(reason) }
    }
    private func markStopped(_ reason: String) {
        failed = true
        if var index, index.stopped == nil {
            index.stopped = reason; self.index = index
            try? ReplayFiles.write(index, folder)
        }
        DispatchQueue.main.async { self.accepting = false; self.last = nil }
    }

    private func changedTiles(previous: DocumentSnapshot?, current: DocumentSnapshot) -> Set<TileKey> {
        // Geometry or compositing changes can move content beyond its source tile.
        // Visiting at most the document's visible tile grid on metadata edits also
        // covers clipped layers, transformed image sources and removed layers.
        if previous?.record.layers != current.record.layers {
            func sceneKeys(_ scene: DocumentSnapshot) -> Set<TileKey> {
                var keys = Set<TileKey>()
                for layer in scene.record.layers where layer.visible {
                    if let photo = layer.photo {
                        keys.formUnion(TileKey.intersecting(photo.bounds, width: scene.record.width, height: scene.record.height))
                    } else if let tiles = scene.pixels[layer.id] {
                        keys.formUnion(tiles.keys)
                    }
                }
                return keys
            }
            var keys = sceneKeys(current)
            if let previous { keys.formUnion(sceneKeys(previous)) }
            return keys
        }
        var dirty = Set<TileKey>()
        for id in Set(current.pixels.keys).union(previous?.pixels.keys.map { $0 } ?? []) {
            let old = previous?.pixels[id] ?? [:], new = current.pixels[id] ?? [:]
            let keys = Set(new.keys).union(old.keys).filter { old[$0] !== new[$0] }
            guard !keys.isEmpty else { continue }
            if let photo = current.record.layers.first(where: { $0.id == id })?.photo {
                for key in keys {
                    photo.geometry.pieces(in: photo.bounds, source: key.rect) { pose, _, part in
                        dirty.formUnion(TileKey.intersecting(part.intersection(key.rect).applying(pose),
                            width: current.record.width, height: current.record.height))
                    }
                }
            } else { dirty.formUnion(keys) }
        }
        return dirty
    }
}

final class ProcessReplay {
    private let index: ReplayIndex
    private var steps: [ReplayStep] = []
    let folder: URL
    var count: Int { steps.count }
    var stopped: String? { index.stopped }
    init(folder: URL) throws {
        self.folder = folder
        index = try ReplayFiles.index(folder)
        var bytes = 0
        for i in 0..<index.count {
            let file = ReplayFiles.step(folder, i)
            let length = try file.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
            guard length > 0, length <= ReplayFiles.limit - bytes else { throw DrawingError.message("Invalid recording step size.") }
            bytes += length
            let step = try PropertyListDecoder().decode(ReplayStep.self, from: Data(contentsOf: file))
            guard step.opacity.isFinite, (0...1).contains(step.opacity), step.patches.count <= 256,
                  step.patches.allSatisfy({ patch in
                      patch.tile.x >= 0 && patch.tile.y >= 0 && patch.tile.x < (index.width + 255) / 256 && patch.tile.y < (index.height + 255) / 256
                  }) else { throw DrawingError.message("Invalid recording tile.") }
            steps.append(step)
        }
        guard bytes == index.bytes, !steps.isEmpty else { throw DrawingError.message("No drawing progress is available yet.") }
    }
    // Single worker per playback/export. Moving forward visits only intervening steps.
    private var cursor = -1
    private var tiles: [TileKey: Data] = [:]
    func render(_ number: Int, maxEdge: Int) throws -> CGImage {
        guard steps.indices.contains(number) else { throw DrawingError.message("Invalid recording position.") }
        if number < cursor { tiles.removeAll(); cursor = -1 }
        while cursor < number {
            cursor += 1
            if steps[cursor].reset { tiles.removeAll() }
            for patch in steps[cursor].patches { tiles[patch.tile] = patch.png }
        }
        let step = steps[number]
        let scale = min(1, Double(maxEdge) / Double(max(index.width, index.height)))
        let w = max(1, Int(Double(index.width) * scale)), h = max(1, Int(Double(index.height) * scale))
        let context = try RasterDocument.context(width: w, height: h)
        context.scaleBy(x: CGFloat(w) / CGFloat(index.width), y: CGFloat(h) / CGFloat(index.height))
        let paper = UIColor(red: CGFloat(step.paper >> 16 & 255) / 255, green: CGFloat(step.paper >> 8 & 255) / 255,
                            blue: CGFloat(step.paper & 255) / 255, alpha: CGFloat(step.opacity))
        context.setFillColor(paper.cgColor); context.fill(CGRect(x: 0, y: 0, width: index.width, height: index.height))
        for (key, data) in tiles {
            try autoreleasepool {
                guard let src = CGImageSourceCreateWithData(data as CFData, nil),
                      let props = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any],
                      props[kCGImagePropertyPixelWidth] as? Int == 256, props[kCGImagePropertyPixelHeight] as? Int == 256,
                      let image = CGImageSourceCreateImageAtIndex(src, 0, nil) else { throw DrawingError.message("Invalid recording image.") }
                RasterDocument.draw(image, in: context, rect: key.rect)
            }
        }
        guard let result = context.makeImage() else { throw DrawingError.message("Could not render drawing progress.") }
        return result
    }
    func exportGIF() throws -> URL {
        let url = ReplayFiles.fm.temporaryDirectory.appendingPathComponent("Drawing-progress-\(UUID().uuidString).gif")
        let frames = min(count, 600)
        guard let dest = CGImageDestinationCreateWithURL(url as CFURL, UTType.gif.identifier as CFString, frames, nil) else {
            throw DrawingError.message("Could not create the GIF.")
        }
        do {
            CGImageDestinationSetProperties(dest, [kCGImagePropertyGIFDictionary: [kCGImagePropertyGIFLoopCount: 0]] as CFDictionary)
            for i in 0..<frames {
                try autoreleasepool {
                    let step = frames == 1 ? 0 : i * (count - 1) / (frames - 1)
                    let image = try render(step, maxEdge: 1024)
                    CGImageDestinationAddImage(dest, image, [kCGImagePropertyGIFDictionary: [kCGImagePropertyGIFDelayTime: 1.0 / 12]] as CFDictionary)
                }
            }
            guard CGImageDestinationFinalize(dest) else { throw DrawingError.message("Could not finish the GIF.") }
            return url
        } catch { try? ReplayFiles.fm.removeItem(at: url); throw error }
    }
}

extension ProcessReplay {
    func exportMovie(completion: @escaping (Result<URL, Error>) -> Void) {
        let url = ReplayFiles.fm.temporaryDirectory.appendingPathComponent("Drawing-progress-\(UUID().uuidString).mp4")
        do {
            let frames = min(count, 900)
            let first = try render(0, maxEdge: 1280)
            let width = (first.width + 1) & ~1, height = (first.height + 1) & ~1
            let writer = try AVAssetWriter(outputURL: url, fileType: .mp4)
            let input = AVAssetWriterInput(mediaType: .video, outputSettings: [
                AVVideoCodecKey: AVVideoCodecType.h264, AVVideoWidthKey: width, AVVideoHeightKey: height
            ])
            let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input, sourcePixelBufferAttributes: [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
                kCVPixelBufferWidthKey as String: width, kCVPixelBufferHeightKey as String: height,
                kCVPixelBufferCGBitmapContextCompatibilityKey as String: true
            ])
            guard writer.canAdd(input) else { throw DrawingError.message("Cannot encode this replay size.") }
            writer.add(input)
            guard writer.startWriting() else { throw writer.error ?? DrawingError.message("Could not start video export.") }
            writer.startSession(atSourceTime: .zero)
            var i = 0, finished = false
            let queue = DispatchQueue(label: "dev.tipstroke.process.movie", qos: .userInitiated)
            input.requestMediaDataWhenReady(on: queue) {
                guard !finished else { return }
                do {
                    while input.isReadyForMoreMediaData && i < frames {
                        try autoreleasepool {
                            var buffer: CVPixelBuffer?
                            guard let pool = adaptor.pixelBufferPool, CVPixelBufferPoolCreatePixelBuffer(nil, pool, &buffer) == kCVReturnSuccess,
                                  let buffer else { throw DrawingError.message("Could not allocate video frame.") }
                            CVPixelBufferLockBaseAddress(buffer, [])
                            defer { CVPixelBufferUnlockBaseAddress(buffer, []) }
                            guard let ctx = CGContext(data: CVPixelBufferGetBaseAddress(buffer), width: width, height: height, bitsPerComponent: 8,
                                bytesPerRow: CVPixelBufferGetBytesPerRow(buffer), space: CGColorSpace(name: CGColorSpace.sRGB)!,
                                bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue) else {
                                throw DrawingError.message("Could not render video frame.")
                            }
                            let step = frames == 1 ? 0 : i * (self.count - 1) / (frames - 1)
                            let image = i == 0 ? first : try self.render(step, maxEdge: 1280)
                            let rect = CGRect(x: 0, y: 0, width: width, height: height)
                            ctx.setFillColor(UIColor.white.cgColor); ctx.fill(rect); ctx.draw(image, in: rect)
                            guard adaptor.append(buffer, withPresentationTime: CMTime(value: Int64(i), timescale: 24)) else {
                                throw writer.error ?? DrawingError.message("Video encoding failed.")
                            }
                            i += 1
                        }
                    }
                    if i == frames {
                        finished = true
                        writer.endSession(atSourceTime: CMTime(value: Int64(frames), timescale: 24))
                        input.markAsFinished()
                        writer.finishWriting {
                            if writer.status == .completed { completion(.success(url)) }
                            else { try? ReplayFiles.fm.removeItem(at: url); completion(.failure(writer.error ?? DrawingError.message("Could not finish video."))) }
                        }
                    }
                } catch {
                    finished = true; writer.cancelWriting(); try? ReplayFiles.fm.removeItem(at: url); completion(.failure(error))
                }
            }
        } catch { try? ReplayFiles.fm.removeItem(at: url); completion(.failure(error)) }
    }
}

final class ProcessReplayController: UIViewController {
    private let folder: URL
    private let loadingQueue = DispatchQueue(label: "dev.tipstroke.process.preview", qos: .userInitiated)
    private var replay: ProcessReplay?
    private let preview = UIImageView(), slider = UISlider(), status = UILabel(), play = UIButton(type: .system)
    private var busy = false, wanted = 0, playing = false
    private let share = UIBarButtonItem()
    init(folder: URL) { self.folder = folder; super.init(nibName: nil, bundle: nil) }
    required init?(coder: NSCoder) { fatalError("Use init(folder:)") }

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "Drawing progress"; view.backgroundColor = .systemBackground
        navigationItem.leftBarButtonItem = UIBarButtonItem(systemItem: .done, primaryAction: UIAction { [weak self] _ in self?.dismiss(animated: true) })
        share.image = UIImage(systemName: "square.and.arrow.up"); share.accessibilityLabel = "Export drawing progress"; share.isEnabled = false
        share.menu = UIMenu(children: [
            UIAction(title: "Share progress GIF") { [weak self] _ in self?.export(movie: false) },
            UIAction(title: "Share progress MP4") { [weak self] _ in self?.export(movie: true) }
        ])
        navigationItem.rightBarButtonItem = share
        preview.contentMode = .scaleAspectFit
        preview.accessibilityLabel = "Drawing progress preview"
        status.numberOfLines = 0; status.font = .preferredFont(forTextStyle: .caption1)
        status.text = "Loading drawing progress…"
        play.setTitle("Play", for: .normal); play.isEnabled = false; slider.isEnabled = false
        slider.accessibilityLabel = "Drawing progress position"
        play.addAction(UIAction { [weak self] _ in self?.togglePlay() }, for: .touchUpInside)
        slider.addAction(UIAction { [weak self] _ in
            guard let self else { return }; self.setPlaying(false); self.show(Int(self.slider.value.rounded()))
        }, for: .valueChanged)
        let stack = UIStackView(arrangedSubviews: [preview, status, slider, play]); stack.axis = .vertical; stack.spacing = 12
        stack.translatesAutoresizingMaskIntoConstraints = false; view.addSubview(stack)
        let safe = view.safeAreaLayoutGuide
        NSLayoutConstraint.activate([stack.topAnchor.constraint(equalTo: safe.topAnchor, constant: 12),
            stack.bottomAnchor.constraint(equalTo: safe.bottomAnchor, constant: -12),
            stack.leadingAnchor.constraint(equalTo: safe.leadingAnchor, constant: 16), stack.trailingAnchor.constraint(equalTo: safe.trailingAnchor, constant: -16)])
        // Ordered after all outstanding artwork observations captured before opening the sheet.
        ProjectStore.shared.queue.async {
            let result = Result { try ProcessReplay(folder: self.folder) }
            DispatchQueue.main.async {
                do {
                    let replay = try result.get(); self.replay = replay
                    self.slider.maximumValue = Float(replay.count - 1)
                    self.share.isEnabled = true; self.play.isEnabled = replay.count > 1; self.slider.isEnabled = replay.count > 1
                    self.show(0)
                } catch { self.status.text = error.localizedDescription }
            }
        }
    }
    override func viewWillDisappear(_ animated: Bool) { super.viewWillDisappear(animated); setPlaying(false) }
    private func setPlaying(_ value: Bool) {
        playing = value; play.setTitle(value ? "Pause" : "Play", for: .normal)
    }
    private func togglePlay() {
        guard let replay else { return }
        setPlaying(!playing)
        if playing { show(wanted + 1 >= replay.count ? 0 : wanted + 1) }
    }
    private func show(_ index: Int) {
        guard let replay else { return }
        wanted = max(0, min(replay.count - 1, index)); slider.value = Float(wanted)
        guard !busy else { return }
        busy = true; let requested = wanted
        loadingQueue.async {
            let result = Result { UIImage(cgImage: try replay.render(requested, maxEdge: 800)) }
            DispatchQueue.main.async {
                self.busy = false
                do {
                    let image = try result.get()
                    if self.wanted != requested { self.show(self.wanted); return }
                    self.preview.image = image
                    self.status.text = ["Step \(requested + 1) of \(replay.count)", replay.stopped].compactMap { $0 }.joined(separator: "\n")
                    if requested == replay.count - 1 { self.setPlaying(false) }
                    else if self.playing {
                        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0 / 12) { [weak self] in
                            guard let self, self.playing, self.wanted == requested else { return }
                            self.show(requested + 1)
                        }
                    }
                } catch { self.setPlaying(false); self.status.text = error.localizedDescription }
            }
        }
    }
    private func export(movie: Bool) {
        setPlaying(false); share.isEnabled = false
        let done: (Result<URL, Error>) -> Void = { [weak self] result in
            DispatchQueue.main.async {
                guard let self, self.viewIfLoaded?.window != nil else {
                    if case let .success(url) = result { try? FileManager.default.removeItem(at: url) }
                    return
                }
                self.share.isEnabled = true
                do {
                    let url = try result.get()
                    let sheet = UIActivityViewController(activityItems: [url], applicationActivities: nil)
                    sheet.popoverPresentationController?.barButtonItem = self.share
                    sheet.completionWithItemsHandler = { _, _, _, _ in try? FileManager.default.removeItem(at: url) }
                    self.present(sheet, animated: true)
                } catch { self.showError(error) }
            }
        }
        loadingQueue.async {
            do {
                let export = try ProcessReplay(folder: self.folder)
                if movie { export.exportMovie(completion: done) } else { done(.success(try export.exportGIF())) }
            } catch { done(.failure(error)) }
        }
    }
}
