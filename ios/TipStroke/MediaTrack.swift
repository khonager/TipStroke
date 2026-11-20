import UIKit
import AVFoundation
import ImageIO
import UniformTypeIdentifiers
import TipStrokeShared

/// A private hard link pins the original bytes across atomic saves, undo, and gallery duplication.
final class LocalMediaFile {
    let url: URL
    init(_ input: URL) throws {
        url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathExtension(input.pathExtension)
        do { try FileManager.default.linkItem(at: input, to: url) }
        catch { try FileManager.default.copyItem(at: input, to: url) }
    }
    deinit {
        let file = url
        DispatchQueue.global(qos: .utility).async { try? FileManager.default.removeItem(at: file) }
    }
}

struct MediaAssetRecord: Codable, Equatable {
    let id: UUID
    let name: String
    let kind: String
    let durationUs: Int64
    let frameRate: Double
    let fileExtension: String
    var source: LocalMediaFile?
    enum CodingKeys: String, CodingKey { case id, name, kind, durationUs, frameRate, fileExtension }
    static func == (a: Self, b: Self) -> Bool { a.id == b.id && a.durationUs == b.durationUs && a.kind == b.kind }
    var filename: String { id.uuidString + "." + fileExtension }
}

struct MediaPoseKey: Codable, Equatable {
    var timeUs: Int64
    var x: Float = 0, y: Float = 0, scale: Float = 1, rotation: Float = 0, opacity: Float = 1
    var easing = "LINEAR"
}
struct MediaClipRecord: Codable, Equatable {
    var id = UUID()
    var assetID: UUID
    var name: String
    var sourceInUs: Int64 = 0
    var durationUs: Int64
    var transitionUs: Int64 = 0
    var keyframes: [MediaPoseKey] = []
}
struct MediaTrack: Codable, Equatable {
    var editID = UUID()
    var assets: [MediaAssetRecord]
    var clips: [MediaClipRecord]
    var positionUs: Int64 = 0
    func plan() throws -> NativeVideoPlan {
        guard assets.count <= 1000, Set(assets.map(\.id)).count == assets.count,
              assets.allSatisfy({ ["gif", "video", "image"].contains($0.kind) && (1...86_400_000_000).contains($0.durationUs) &&
                $0.frameRate.isFinite && $0.frameRate > 0 && $0.fileExtension.range(of: "^[a-z0-9]{1,10}$", options: .regularExpression) != nil }),
              clips.allSatisfy({ c in assets.contains { $0.id == c.assetID && c.sourceInUs >= 0 && c.durationUs > 0 && c.sourceInUs <= $0.durationUs - c.durationUs } })
        else { throw DrawingError.message("Invalid media track.") }
        let json = try JSONEncoder().encode(clips)
        return try NativeVideoPlan.companion.decode(json: String(decoding: json, as: UTF8.self))
    }
    mutating func normalizeTransitions() {
        for i in clips.indices {
            clips[i].transitionUs = i == 0 ? 0 : min(clips[i].transitionUs, clips[i].durationUs / 2, clips[i-1].durationUs / 2)
        }
    }
}

/// Worker-confined decoders. At most the two overlapping clips are sampled at one time.
final class MediaFrameReader {
    private let track: MediaTrack
    let plan: NativeVideoPlan
    private enum Decoder {
        case movie(AVAssetImageGenerator)
        case gif(CGImageSource, [Int64])
        case still(CGImage)
    }
    private var decoders: [UUID: Decoder] = [:]
    init(track: MediaTrack) throws { self.track = track; plan = try track.plan() }

    static func inspect(_ input: URL) throws -> MediaAssetRecord {
        guard let size = try input.resourceValues(forKeys: [.fileSizeKey]).fileSize, size > 0, size <= 512 * 1024 * 1024 else {
            throw DrawingError.message("Import a media file up to 512 MiB.")
        }
        let file = try LocalMediaFile(input)
        let ext = input.pathExtension.lowercased()
        let name = String(input.deletingPathExtension().lastPathComponent.prefix(100))
        if let gif = CGImageSourceCreateWithURL(file.url as CFURL, nil), CGImageSourceGetType(gif) as String? == UTType.gif.identifier {
            let ends = try gifEnds(gif)
            return MediaAssetRecord(id: UUID(), name: name, kind: "gif", durationUs: ends.last!,
                frameRate: min(120, Double(ends.count) * 1_000_000 / Double(ends.last!)), fileExtension: "gif", source: file)
        }
        if let image = CGImageSourceCreateWithURL(file.url as CFURL, nil), CGImageSourceGetCount(image) > 0 {
            return MediaAssetRecord(id: UUID(), name: name, kind: "image", durationUs: 5_000_000,
                frameRate: 30, fileExtension: ext, source: file)
        }
        let asset = AVURLAsset(url: file.url)
        guard let video = asset.tracks(withMediaType: .video).first, asset.duration.seconds.isFinite,
              asset.duration.seconds > 0, asset.duration.seconds <= 86400,
              ext.range(of: "^[a-z0-9]{1,10}$", options: .regularExpression) != nil else { throw DrawingError.message("Choose a readable movie or animated GIF.") }
        return MediaAssetRecord(id: UUID(), name: name, kind: "video", durationUs: max(1, Int64(asset.duration.seconds * 1_000_000)),
            frameRate: max(1, min(120, Double(video.nominalFrameRate))), fileExtension: ext, source: file)
    }

    private static func gifEnds(_ source: CGImageSource) throws -> [Int64] {
        let count = CGImageSourceGetCount(source)
        guard (1...36000).contains(count) else { throw DrawingError.message("Unsupported GIF frame count.") }
        var end: Int64 = 0
        return try (0..<count).map {
            let props = CGImageSourceCopyPropertiesAtIndex(source, $0, nil) as? [CFString: Any]
            let timing = props?[kCGImagePropertyGIFDictionary] as? [CFString: Any]
            let delay = (timing?[kCGImagePropertyGIFUnclampedDelayTime] ?? timing?[kCGImagePropertyGIFDelayTime]) as? Double ?? 0.1
            guard delay.isFinite, delay >= 0, delay <= 86400 else { throw DrawingError.message("Invalid GIF timing.") }
            end += max(10_000, Int64(delay * 1_000_000))
            guard end <= 86_400_000_000 else { throw DrawingError.message("The GIF exceeds 24 hours.") }
            return end
        }
    }

    private func image(_ id: UUID, time: Int64, size: CGSize) throws -> CGImage {
        guard let asset = track.assets.first(where: { $0.id == id }), let url = asset.source?.url else { throw DrawingError.message("Missing source media.") }
        if decoders[id] == nil {
            if decoders.count >= 2 { decoders.removeAll() }
            if asset.kind == "gif" {
                guard let source = CGImageSourceCreateWithURL(url as CFURL, nil) else { throw DrawingError.message("Cannot read GIF.") }
                decoders[id] = .gif(source, try Self.gifEnds(source))
            } else if asset.kind == "image" {
                guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
                      let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                        kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceCreateThumbnailWithTransform: true,
                        kCGImageSourceThumbnailMaxPixelSize: max(size.width, size.height)
                      ] as CFDictionary) else { throw DrawingError.message("Could not decode the still image.") }
                decoders[id] = .still(image)
            } else {
                let generator = AVAssetImageGenerator(asset: AVURLAsset(url: url))
                generator.appliesPreferredTrackTransform = true
                generator.requestedTimeToleranceBefore = .zero; generator.requestedTimeToleranceAfter = .zero
                decoders[id] = .movie(generator)
            }
        }
        switch decoders[id]! {
        case .still(let image): return image
        case .movie(let generator):
            generator.maximumSize = size
            return try generator.copyCGImage(at: CMTime(value: time, timescale: 1_000_000), actualTime: nil)
        case .gif(let source, let ends):
            var low = 0, high = ends.count
            while low < high { let middle = (low + high) / 2; if ends[middle] <= time { low = middle + 1 } else { high = middle } }
            guard let result = CGImageSourceCreateThumbnailAtIndex(source, min(low, ends.count - 1), [
                kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceThumbnailMaxPixelSize: max(size.width, size.height)
            ] as CFDictionary) else { throw DrawingError.message("Could not decode GIF frame.") }
            return result
        }
    }

    func draw(time: Int64, canvas: CGRect, in context: CGContext) throws {
        let samples = plan.samples(timeUs: time)
        if samples.count > 1 { context.beginTransparencyLayer(auxiliaryInfo: nil) }
        defer { if samples.count > 1 { context.endTransparencyLayer() } }
        for (i, sample) in samples.enumerated() {
            guard let id = UUID(uuidString: sample.assetId) else { throw DrawingError.message("Invalid clip asset.") }
            let image = try image(id, time: sample.sourceUs, size: canvas.size)
            let pose = sample.pose
            let scale = min(canvas.width / CGFloat(image.width), canvas.height / CGFloat(image.height)) * CGFloat(pose.scale)
            let size = CGSize(width: CGFloat(image.width) * scale, height: CGFloat(image.height) * scale)
            context.saveGState()
            if i == 1 { context.setBlendMode(.plusLighter) }
            context.setAlpha(CGFloat(pose.opacity * sample.weight))
            context.translateBy(x: canvas.midX + canvas.width * CGFloat(pose.x), y: canvas.midY + canvas.height * CGFloat(pose.y))
            context.rotate(by: CGFloat(pose.rotation) * .pi / 180)
            RasterDocument.draw(image, in: context, rect: CGRect(x: -size.width / 2, y: -size.height / 2, width: size.width, height: size.height))
            context.restoreGState()
        }
    }
}

/// Keeps a single decode in flight while scrubbing. A newer request replaces the pending one.
final class MediaBackdrop {
    let layer = CALayer()
    private(set) var image: CGImage?
    private let queue = DispatchQueue(label: "dev.tipstroke.media-preview", qos: .userInitiated)
    private var decoder: MediaFrameReader?
    private var decodedTrack: UUID?
    private var requested: (UUID, Int64, CGSize)?
    private var pending: (MediaTrack, CGSize)?
    private var busy = false
    var onError: ((Error) -> Void)?
    init() { layer.zPosition = -1; layer.actions = ["contents": NSNull(), "bounds": NSNull(), "position": NSNull()] }
    func update(_ track: MediaTrack?, bounds: CGRect) {
        layer.frame = bounds
        guard let track else {
            if requested != nil { requested = nil; pending = nil; image = nil; layer.contents = nil; queue.async { self.decoder = nil; self.decodedTrack = nil } }
            return
        }
        let ratio = min(1, 1280 / max(bounds.width, bounds.height, 1))
        let size = CGSize(width: max(1, (bounds.width * ratio).rounded()), height: max(1, (bounds.height * ratio).rounded()))
        let key = (track.editID, track.positionUs, size)
        if let requested, requested == key { return }
        requested = key; pending = (track, size)
        start()
    }
    private func start() {
        guard !busy, let (track, size) = pending else { return }
        busy = true; pending = nil
        queue.async {
            let result = Result { () throws -> CGImage in
                if self.decodedTrack != track.editID {
                    self.decoder = try MediaFrameReader(track: track)
                    self.decodedTrack = track.editID
                }
                let output = try RasterDocument.context(width: Int(size.width), height: Int(size.height))
                try self.decoder?.draw(time: track.positionUs, canvas: CGRect(origin: .zero, size: size), in: output)
                guard let image = output.makeImage() else { throw DrawingError.message("Could not prepare media preview.") }
                return image
            }
            DispatchQueue.main.async {
                self.busy = false
                if let key = self.requested, key.0 == track.editID && (key.1 == track.positionUs || self.pending != nil) {
                    switch result {
                    case .success(let image): self.image = image; self.layer.contents = image
                    case .failure(let error): if self.pending == nil { self.onError?(error) }
                    }
                }
                self.start()
            }
        }
    }
}

extension EditorController {
    func importMedia() {
        viewport.canvas.cancelStroke()
        importingMedia = true
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.movie, .image], asCopy: true)
        picker.delegate = self
        present(picker, animated: true)
    }
    func addMediaFile(_ url: URL) {
        view.isUserInteractionEnabled = false
        ProjectStore.shared.queue.async {
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            let result = Result { try MediaFrameReader.inspect(url) }
            DispatchQueue.main.async {
                self.view.isUserInteractionEnabled = true
                do {
                    let asset = try result.get()
                    var track = self.document.record.media ?? MediaTrack(assets: [], clips: [])
                    track.assets.append(asset)
                    track.clips.append(MediaClipRecord(assetID: asset.id, name: asset.name, durationUs: asset.durationUs))
                    let plan = try track.plan()
                    track.positionUs = plan.start(index: Int32(track.clips.count - 1))
                    track.editID = UUID()
                    self.document.enableAnimation()
                    self.document.beginMetadataEdit()
                    if self.document.record.media == nil, var animation = self.document.record.animation {
                        var start: Int64 = 0
                        for i in animation.frames.indices {
                            animation.frames[i].sourceTimeUs = start
                            start += Int64(animation.frames[i].exposure) * 1_000_000 / Int64(animation.fps)
                        }
                        self.document.record.animation = animation
                    }
                    self.document.record.media = track
                    self.document.endMetadataEdit()
                    self.changed()
                    self.showMediaTimeline()
                } catch { self.showError(error) }
            }
        }
    }
    func showMediaTimeline() {
        guard let track = document.record.media else { return }
        do {
            let controller = try MediaTrackController(editor: self, track: track)
            let navigation = UINavigationController(rootViewController: controller)
            navigation.modalPresentationStyle = .pageSheet
            navigation.sheetPresentationController?.detents = [.medium(), .large()]
            present(navigation, animated: true)
        } catch { showError(error) }
    }
}

extension AnimationRecord {
    func coverageIndex(at time: Int64) -> Int? {
        var index: Int?, latest = Int64.min
        for i in frames.indices {
            if let start = frames[i].sourceTimeUs, start <= time, start >= latest { index = i; latest = start }
        }
        guard let index, let start = frames[index].sourceTimeUs else { return nil }
        let duration = frames[index].durationUs ?? Int64(frames[index].exposure) * 1_000_000 / Int64(fps)
        return time - start < duration ? index : nil
    }
}
extension RasterDocument {
    func seekMedia(_ time: Int64) {
        guard record.media != nil else { return }
        record.media?.positionUs = time
        if let animation = record.animation, let index = animation.coverageIndex(at: time), index != animation.selectedIndex {
            selectFrame(index)
        }
    }
    func ensureDrawingCel() throws {
        guard let track = record.media, let animation = record.animation,
              !animation.sharedLayerIDs.contains(activeLayer) else { return }
        if let covered = animation.coverageIndex(at: track.positionUs) {
            if covered != animation.selectedIndex {
                guard coldFrames[animation.frames[covered].id] == nil else {
                    throw DrawingError.message("The drawing at this time is still loading.")
                }
                pendingCelSelection = nil
                installFrame(covered)
            }
            return
        }
        try addFrame(copy: false, sourceTimeUs: track.positionUs)
    }
}

final class MediaTrackController: UITableViewController {
    private weak var editor: EditorController?
    private var track: MediaTrack
    private var plan: NativeVideoPlan
    private let slider = UISlider()
    private let clock = UILabel()
    private var playback: CADisplayLink?
    private var beganAt: CFTimeInterval = 0
    private var beganUs: Int64 = 0

    init(editor: EditorController, track: MediaTrack) throws {
        self.editor = editor; self.track = track; plan = try track.plan()
        super.init(style: .insetGrouped)
    }
    required init?(coder: NSCoder) { fatalError("Use init(editor:track:)") }
    override func viewDidLoad() {
        super.viewDidLoad(); title = "Media timeline"
        navigationItem.rightBarButtonItem = UIBarButtonItem(systemItem: .done, primaryAction: UIAction { [weak self] _ in self?.dismiss(animated: true) })
        navigationItem.leftBarButtonItem = UIBarButtonItem(systemItem: .add, primaryAction: UIAction { [weak self] _ in
            self?.dismiss(animated: true) { [weak editor = self?.editor] in editor?.importMedia() }
        })
        tableView.register(UITableViewCell.self, forCellReuseIdentifier: "clip")
        let play = UIButton(type: .system, primaryAction: UIAction(title: "Play / pause") { [weak self] _ in self?.toggle() })
        let prev = UIButton(type: .system, primaryAction: UIAction(title: "Previous drawing") { [weak self] _ in self?.jump(-1) })
        let next = UIButton(type: .system, primaryAction: UIAction(title: "Next drawing") { [weak self] _ in self?.jump(1) })
        let row = UIStackView(arrangedSubviews: [prev, play, next]); row.distribution = .equalSpacing
        clock.textAlignment = .center
        slider.accessibilityLabel = "Source time"; slider.addTarget(self, action: #selector(scrub), for: .valueChanged)
        let header = UIStackView(arrangedSubviews: [clock, slider, row]); header.axis = .vertical
        header.isLayoutMarginsRelativeArrangement = true; header.layoutMargins = UIEdgeInsets(top: 8, left: 18, bottom: 8, right: 18)
        header.frame = CGRect(x: 0, y: 0, width: view.bounds.width, height: 125)
        tableView.tableHeaderView = header; seek(track.positionUs)
    }
    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated); stop(); editor?.changed()
    }
    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { track.clips.count }
    override func tableView(_ tableView: UITableView, cellForRowAt path: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "clip", for: path)
        let clip = track.clips[path.row]; var info = cell.defaultContentConfiguration()
        info.text = clip.name; info.secondaryText = String(format: "%.2f s · %d keys", Double(clip.durationUs) / 1_000_000, clip.keyframes.count)
        cell.contentConfiguration = info; cell.accessoryType = .disclosureIndicator; return cell
    }
    private func seek(_ value: Int64) {
        let t = min(plan.durationUs - 1, max(0, value))
        track.positionUs = t; editor?.document.seekMedia(t); editor?.viewport.canvas.refresh()
        slider.value = Float(Double(t) / Double(plan.durationUs))
        clock.text = String(format: "%.2f / %.2f s", Double(t) / 1_000_000, Double(plan.durationUs) / 1_000_000)
    }
    @objc private func scrub() { stop(); seek(Int64(Double(slider.value) * Double(plan.durationUs))) }
    private func jump(_ direction: Int) {
        stop()
        let points = editor?.document.record.animation?.frames.compactMap(\.sourceTimeUs).sorted() ?? []
        if direction < 0 { seek(points.last { $0 < track.positionUs } ?? 0) }
        else { seek(points.first { $0 > track.positionUs } ?? plan.durationUs - 1) }
    }
    private func stop() { playback?.invalidate(); playback = nil }
    private func toggle() {
        if playback != nil { stop(); return }
        beganUs = track.positionUs >= plan.durationUs - 1 ? 0 : track.positionUs
        beganAt = CACurrentMediaTime()
        let link = CADisplayLink(target: self, selector: #selector(tick))
        link.preferredFramesPerSecond = min(60, max(1, Int(track.assets.map(\.frameRate).max() ?? 30)))
        link.add(to: .main, forMode: .common); playback = link
    }
    @objc private func tick() {
        let time = beganUs + Int64((CACurrentMediaTime() - beganAt) * 1_000_000)
        seek(time); if time >= plan.durationUs - 1 { stop() }
    }
    private func apply(_ edit: (inout MediaTrack) throws -> Void) {
        guard let document = editor?.document else { return }
        do {
            var updated = track; try edit(&updated); updated.normalizeTransitions(); let newPlan = try updated.plan()
            updated.editID = UUID(); updated.positionUs = min(track.positionUs, newPlan.durationUs - 1)
            document.beginMetadataEdit(); document.record.media = updated; document.endMetadataEdit()
            track = updated; plan = newPlan; seek(track.positionUs); tableView.reloadData(); editor?.changed()
        } catch { showError(error) }
    }
    override func tableView(_ tableView: UITableView, didSelectRowAt path: IndexPath) {
        stop()
        let i = path.row, clip = track.clips[i], start = plan.start(index: Int32(i))
        if track.positionUs < start || track.positionUs >= start + clip.durationUs { seek(start) }
        let local = track.positionUs - start
        let menu = UIAlertController(title: clip.name, message: "Edits use the current playhead.", preferredStyle: .actionSheet)
        func action(_ name: String, _ body: @escaping () -> Void) { menu.addAction(UIAlertAction(title: name, style: .default) { _ in body() }) }
        action("Trim source range and crossfade…") { [weak self] in self?.trim(i) }
        action("Set transform keyframe…") { [weak self] in self?.keyframe(i, local) }
        if clip.keyframes.contains(where: { $0.timeUs == local }) { action("Remove keyframe") { [weak self] in self?.apply { $0.clips[i].keyframes.removeAll { $0.timeUs == local } } } }
        if local > 0 && local < clip.durationUs {
            action("Split at playhead") { [weak self] in self?.apply { track in
                var later = track.clips[i]; later.id = UUID(); later.sourceInUs += local; later.durationUs -= local; later.transitionUs = 0
                for k in later.keyframes.indices { later.keyframes[k].timeUs -= local }
                track.clips[i].durationUs = local; track.clips.insert(later, at: i + 1)
            } }
        }
        if i > 0 { action("Move earlier") { [weak self] in self?.apply { $0.clips.swapAt(i, i - 1) } } }
        if i + 1 < track.clips.count { action("Move later") { [weak self] in self?.apply { $0.clips.swapAt(i, i + 1) } } }
        if track.clips.count > 1 { action("Remove clip") { [weak self] in self?.apply { track in
            track.clips.remove(at: i); let used = Set(track.clips.map(\.assetID)); track.assets.removeAll { !used.contains($0.id) }
        } } }
        menu.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        menu.popoverPresentationController?.sourceView = tableView; menu.popoverPresentationController?.sourceRect = tableView.rectForRow(at: path)
        present(menu, animated: true)
    }
    private func fields(title: String, values: [(String, String)], apply: @escaping ([String]) -> Void) {
        let dialog = UIAlertController(title: title, message: values.map(\.0).joined(separator: " · "), preferredStyle: .alert)
        for (name, value) in values { dialog.addTextField { $0.placeholder = name; $0.accessibilityLabel = name; $0.text = value } }
        dialog.addAction(UIAlertAction(title: "Apply", style: .default) { [weak dialog] _ in apply(dialog?.textFields?.map { $0.text ?? "" } ?? []) })
        dialog.addAction(UIAlertAction(title: "Cancel", style: .cancel)); present(dialog, animated: true)
    }
    private func trim(_ i: Int) {
        let c = track.clips[i]
        fields(title: "Clip timing", values: [("In seconds", String(Double(c.sourceInUs)/1e6)), ("Out seconds", String(Double(c.sourceInUs+c.durationUs)/1e6)),
            ("Crossfade seconds", String(Double(c.transitionUs)/1e6))]) { [weak self] text in
            let us = text.compactMap(Double.init).filter { $0.isFinite && (0...86400).contains($0) }.map { Int64($0 * 1e6) }
            guard us.count == 3, us[1] > us[0] else { self?.showError(DrawingError.message("Enter a valid source range and fade.")); return }
            self?.apply { track in
                let shift = us[0] - track.clips[i].sourceInUs
                for k in track.clips[i].keyframes.indices { track.clips[i].keyframes[k].timeUs -= shift }
                track.clips[i].sourceInUs = us[0]; track.clips[i].durationUs = us[1] - us[0]; track.clips[i].transitionUs = us[2]
            }
        }
    }
    private func keyframe(_ i: Int, _ local: Int64) {
        let key = track.clips[i].keyframes.first { $0.timeUs == local } ?? MediaPoseKey(timeUs: local)
        fields(title: "Transform keyframe", values: [("X (canvas widths)", String(key.x)), ("Y (canvas heights)", String(key.y)),
            ("Scale", String(key.scale)), ("Degrees", String(key.rotation)), ("Opacity", String(key.opacity)), ("LINEAR / SMOOTH / HOLD", key.easing)]) { [weak self] text in
            let n = text.prefix(5).compactMap(Float.init)
            guard text.count == 6, n.count == 5 else { return }
            self?.apply { track in
                track.clips[i].keyframes.removeAll { $0.timeUs == local }
                track.clips[i].keyframes.append(MediaPoseKey(timeUs: local, x: n[0], y: n[1], scale: n[2], rotation: n[3], opacity: n[4], easing: text[5].uppercased()))
                track.clips[i].keyframes.sort { $0.timeUs < $1.timeUs }
            }
        }
    }
}

/// Exposures render once for pure animation. A media track samples the entire edited duration.
struct ExportSample { let frame: Int?; let startUs: Int64; let durationUs: Int64; let ticks: Int }
struct AnimationRenderPlan {
    let samples: [ExportSample]
    let fps: Double
    let durationUs: Int64
    let reader: MediaFrameReader?
    let snapshot: DocumentSnapshot
    init(_ snapshot: DocumentSnapshot, smooth: Bool) throws {
        self.snapshot = snapshot
        guard let a = snapshot.record.animation else { throw DrawingError.message("Create an animation first.") }
        if let track = snapshot.record.media {
            let reader = try MediaFrameReader(track: track); self.reader = reader
            let rate = smooth ? min(60, track.assets.map(\.frameRate).max() ?? 30) : Double(a.fps)
            let length = reader.plan.durationUs
            let count = Int(ceil(Double(length) * rate / 1e6))
            guard (1...36_000).contains(count) else { throw DrawingError.message("This animation exceeds the export frame limit.") }
            fps = rate; durationUs = length
            samples = (0..<count).map { i in
                let t = Int64(Double(i) / rate * 1e6), end = min(length, Int64(Double(i + 1) / rate * 1e6))
                return ExportSample(frame: a.coverageIndex(at: t), startUs: t, durationUs: end-t, ticks: 1)
            }
        } else {
            reader = nil; fps = Double(a.fps)
            var tick: Int64 = 0
            samples = a.shared.segments().map { segment in
                let start = tick * 1_000_000 / Int64(a.fps)
                tick += Int64(segment.ticks)
                return ExportSample(frame: Int(segment.index), startUs: start, durationUs: tick * 1_000_000 / Int64(a.fps) - start, ticks: Int(segment.ticks))
            }
            durationUs = tick * 1_000_000 / Int64(a.fps)
        }
    }
    func draw(_ sample: ExportSample, in context: CGContext) throws {
        var drawing = snapshot
        if let i = sample.frame, let frame = snapshot.record.animation?.frames[i] { drawing = try snapshot.atFrame(frame.id) }
        drawing.record.media?.positionUs = sample.startUs
        let rect = CGRect(x: 0, y: 0, width: snapshot.record.width, height: snapshot.record.height)
        try reader?.draw(time: sample.startUs, canvas: rect, in: context)
        try RasterDocument.composite(drawing, in: context, visible: rect)
    }
    func image(_ sample: ExportSample) throws -> CGImage {
        let context = try RasterDocument.context(width: snapshot.record.width, height: snapshot.record.height)
        let rect = CGRect(x: 0, y: 0, width: snapshot.record.width, height: snapshot.record.height)
        context.setFillColor(snapshot.record.paperColor.cgColor); context.fill(rect)
        try draw(sample, in: context)
        guard let image = context.makeImage() else { throw DrawingError.message("Could not render this animation frame.") }
        return image
    }
}
