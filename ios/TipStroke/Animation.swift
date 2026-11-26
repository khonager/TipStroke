import UIKit
import ImageIO
import UniformTypeIdentifiers
import TipStrokeShared

extension AnimationRecord {
    var sharedLayerIDs: Set<UUID> { backgroundLayers ?? [] }
}

extension DocumentSnapshot {
    func pixelsForFrame(_ id: UUID) throws -> [UUID: [TileKey: CGImage]] {
        let backgrounds = record.animation?.sharedLayerIDs ?? []
        let shared = pixels.filter { backgrounds.contains($0.key) }
        let cel = try coldFrames[id].map(CelStorage.decode) ?? frames[id] ?? [:]
        return cel.merging(shared) { _, shared in shared }
    }
}

extension RasterDocument {
    private func storeCurrentCel(_ animation: inout AnimationRecord) {
        record.capturePhotoCels(&animation)
        let id = animation.frames[animation.selectedIndex].id
        let hidden = framePixels[id]?.filter { animation.sharedLayerIDs.contains($0.key) } ?? [:]
        framePixels[id] = hidden.merging(pixels.filter { !animation.sharedLayerIDs.contains($0.key) }) { _, current in current }
    }
    private func installCel(_ animation: AnimationRecord) {
        installPhotoCels(animation)
        let id = animation.frames[animation.selectedIndex].id
        let shared = pixels.filter { animation.sharedLayerIDs.contains($0.key) }
        pixels = (framePixels[id] ?? [:]).filter { !animation.sharedLayerIDs.contains($0.key) }.merging(shared) { _, current in current }
    }
    func toggleSharedBackground() {
        guard var animation = record.animation else { return }
        beginMetadataEdit()
        storeCurrentCel(&animation)
        var ids = animation.sharedLayerIDs
        if ids.contains(activeLayer) { ids.remove(activeLayer) } else { ids.insert(activeLayer) }
        animation.backgroundLayers = ids
        if let i = record.layers.firstIndex(where: { $0.id == activeLayer }), record.layers[i].photo != nil {
            if ids.contains(activeLayer) { record.layers[i].photoCelActive = true }
            else { installPhotoCels(animation) }
        }
        record.animation = animation
        endMetadataEdit()
    }

    func enableAnimation() {
        guard record.animation == nil else { return }
        beginMetadataEdit()
        var animation = AnimationRecord()
        record.capturePhotoCels(&animation)
        record.animation = animation
        framePixels[animation.frames[0].id] = pixels
        endMetadataEdit()
    }
    func selectFrame(_ index: Int, completion: @escaping () -> Void = {}) {
        guard let frames = record.animation?.frames, frames.indices.contains(index) else { return }
        let id = frames[index].id
        prepareFrame(index) { [weak self] in
            guard let self, let destination = self.record.animation?.frames.firstIndex(where: { $0.id == id }) else { return }
            self.installFrame(destination); completion(); self.onCelReady?()
        }
    }
    func selectTimelineFrame(_ index: Int) {
        guard let animation = record.animation, animation.frames.indices.contains(index) else { return }
        if let start = animation.frames[index].sourceTimeUs { record.media?.positionUs = start }
        selectFrame(index)
    }

    func retimeDrawing(id: UUID, start: Int64, duration: Int64) throws {
        guard let track = record.media, var animation = record.animation,
              let index = animation.frames.firstIndex(where: { $0.id == id }) else { return }
        let end = try track.plan().durationUs
        guard start >= 0, start < end, duration > 0, duration <= end - start,
              !animation.frames.contains(where: { $0.id != id && $0.sourceTimeUs == start }) else {
            throw DrawingError.message("Choose a distinct start time and a duration within the media timeline.")
        }
        beginMetadataEdit()
        let selected = animation.frames[animation.selectedIndex].id
        animation.frames[index].sourceTimeUs = start
        animation.frames[index].durationUs = duration
        animation.frames.sort { ($0.sourceTimeUs ?? 0) < ($1.sourceTimeUs ?? 0) }
        animation.selectedIndex = animation.frames.firstIndex { $0.id == selected }!
        record.animation = animation
        if selected == id { record.media?.positionUs = start }
        endMetadataEdit()
    }
    func installFrame(_ index: Int) {
        guard var animation = record.animation, animation.frames.indices.contains(index) else { return }
        endStroke(cancelled: true)
        storeCurrentCel(&animation)
        animation.selectedIndex = index
        installCel(animation)
        record.animation = animation; selection = nil
        archiveInactiveCels()
    }
    func restoreFrameForHistory(_ id: UUID?) {
        guard let id, let index = record.animation?.frames.firstIndex(where: { $0.id == id }) else { return }
        // The history caller warmed the target before removing the entry. Apply its
        // pixel delta synchronously even if onion neighbors still need reading.
        if index != record.animation?.selectedIndex { installFrame(index) }
        if let animation = record.animation, animation.onionSkin,
           [index - 1, index + 1].contains(where: { animation.frames.indices.contains($0) && coldFrames[animation.frames[$0].id] != nil }) {
            prepareFrame(index) { [weak self] in self?.onCelReady?() }
        }
    }
    func addFrame(copy: Bool, sourceTimeUs: Int64? = nil) throws {
        enableAnimation()
        guard var animation = record.animation, animation.frames.count < 10_000 else {
            throw DrawingError.message("This timeline has reached its 10,000-frame limit.")
        }
        guard !copy || residentTileCount + pixels.values.reduce(0, { $0 + $1.count }) <= Self.maximumTiles else {
            throw DrawingError.message("This frame copy exceeds the painting memory budget.")
        }
        beginMetadataEdit()
        storeCurrentCel(&animation)
        var frame = AnimationFrameRecord()
        frame.photoCels = copy ? (animation.frames[animation.selectedIndex].photoCels ?? [:]) : [:]
        if let track = record.media { frame.sourceTimeUs = sourceTimeUs ?? track.positionUs }
        let next = copy ? pixels.filter { !animation.sharedLayerIDs.contains($0.key) } : [:]
        animation.selectedIndex += 1
        animation.frames.insert(frame, at: animation.selectedIndex)
        if record.media != nil {
            animation.frames.sort { ($0.sourceTimeUs ?? 0) < ($1.sourceTimeUs ?? 0) }
            animation.selectedIndex = animation.frames.firstIndex { $0.id == frame.id }!
        }
        framePixels[frame.id] = next; installCel(animation); record.animation = animation
        selection = nil; endMetadataEdit(); archiveInactiveCels()
    }
    func deleteFrame() throws {
        guard var animation = record.animation, animation.frames.count > 1 else { return }
        guard pixels.values.reduce(0, { $0 + $1.count }) * Self.tileBytes + 256 <= Self.historyBudget else {
            throw DrawingError.message("This frame is too large to retain in undo history.")
        }
        beginMetadataEdit()
        let nextIndex = animation.selectedIndex == animation.frames.count - 1 ? animation.selectedIndex - 1 : animation.selectedIndex + 1
        guard coldFrames[animation.frames[nextIndex].id] == nil else { endMetadataEdit(); throw DrawingError.message("The adjacent frame is loading.") }
        let removed = animation.frames.remove(at: animation.selectedIndex)
        framePixels[removed.id] = nil; coldFrames[removed.id] = nil
        animation.selectedIndex = min(animation.selectedIndex, animation.frames.count - 1)
        installCel(animation)
        record.animation = animation; selection = nil; endMetadataEdit()
    }
    func moveFrame(_ offset: Int) {
        guard var animation = record.animation else { return }
        let destination = animation.selectedIndex + offset
        guard animation.frames.indices.contains(destination) else { return }
        beginMetadataEdit()
        let frame = animation.frames.remove(at: animation.selectedIndex)
        animation.frames.insert(frame, at: destination); animation.selectedIndex = destination
        record.animation = animation; endMetadataEdit()
    }
}

extension ProjectStore {
    static func reduceResolution(_ source: DocumentSnapshot, divisor: Int) throws -> DocumentSnapshot {
        guard [2, 4].contains(divisor) else { throw DrawingError.message("Choose 50% or 25%.") }
        let width = (source.record.width + divisor - 1) / divisor
        let height = (source.record.height + divisor - 1) / divisor
        guard min(width, height) >= 16 else { throw DrawingError.message("The canvas must remain at least 16 pixels on each side.") }
        let photoIDs = Set(source.record.layers.filter { $0.photo != nil }.map(\.id))
        func reduce(_ layers: [UUID: [TileKey: CGImage]]) throws -> [UUID: [TileKey: CGImage]] {
            var reduced = layers
            for (id, tiles) in layers where !photoIDs.contains(id) {
                let groups = Dictionary(grouping: tiles.keys) { TileKey(x: $0.x / divisor, y: $0.y / divisor) }
                var output: [TileKey: CGImage] = [:]
                for (target, keys) in groups {
                    try autoreleasepool {
                        let side = TileKey.side * divisor
                        let input = try RasterDocument.context(width: side, height: side)
                        input.translateBy(x: -target.rect.minX * CGFloat(divisor), y: -target.rect.minY * CGFloat(divisor))
                        input.clip(to: CGRect(x: 0, y: 0, width: source.record.width, height: source.record.height))
                        for key in keys {
                            if let tile = tiles[key] { RasterDocument.draw(tile, in: input, rect: key.rect) }
                        }
                        let context = try RasterDocument.context(width: 256, height: 256)
                        context.interpolationQuality = .high
                        guard let region = input.makeImage() else { throw DrawingError.message("Could not read the source tiles.") }
                        RasterDocument.draw(region, in: context, rect: CGRect(x: 0, y: 0, width: 256, height: 256))
                        guard let tile = context.makeImage() else { throw DrawingError.message("Could not reduce the drawing.") }
                        output[target] = tile
                    }
                }
                reduced[id] = output
            }
            return reduced
        }
        var result = source
        result.record.width = width; result.record.height = height
        let scale = CGAffineTransform(scaleX: 1 / CGFloat(divisor), y: 1 / CGFloat(divisor))
        for i in result.record.layers.indices {
            if var photo = result.record.layers[i].photo {
                photo.transform = photo.transform.concatenating(scale)
                result.record.layers[i].photo = photo
            }
        }
        if var animation = result.record.animation {
            for i in animation.frames.indices {
                animation.frames[i].photoCels = animation.frames[i].photoCels?.mapValues { cel in
                    var reduced = cel; reduced.transform = reduced.transform.concatenating(scale)
                    return reduced
                }
            }
            result.record.animation = animation
        }
        result.pixels = try reduce(source.pixels)
        result.frames = try source.frames.mapValues(reduce)
        result.coldFrames = try source.coldFrames.mapValues { files in
            try autoreleasepool { try CelStorage.encode(reduce(CelStorage.decode(files))) }
        }
        // Regenerate sparse tile indexes for the new canvas before validating or publishing it.
        result = RasterDocument(snapshot: result).snapshot()
        try result.record.validate()
        return result
    }

    static func render(_ snapshot: DocumentSnapshot, transparent: Bool = false) throws -> CGImage {
        let context = try RasterDocument.context(width: snapshot.record.width, height: snapshot.record.height)
        let bounds = CGRect(x: 0, y: 0, width: snapshot.record.width, height: snapshot.record.height)
        if !transparent {
            context.setFillColor(snapshot.record.paperColor.cgColor); context.fill(bounds)
        }
        try RasterDocument.composite(snapshot, in: context, visible: bounds)
        guard let image = context.makeImage() else { throw DrawingError.message("Could not render the drawing.") }
        return image
    }
    func exportPNGSequence(_ snapshot: DocumentSnapshot, smooth: Bool = false) throws -> URL {
        guard let animation = snapshot.record.animation else { throw DrawingError.message("Create an animation first.") }
        let plan = try AnimationRenderPlan(snapshot, smooth: smooth)
        let segments = plan.samples
        let count = segments.reduce(0) { $0 + Int($1.ticks) }
        guard (1...36000).contains(count) else { throw DrawingError.message("Animation is too long to export.") }
        let files = FileManager.default
        let folder = files.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try files.createDirectory(at: folder, withIntermediateDirectories: false)
        defer { try? files.removeItem(at: folder) }
        let timing: [String: Any] = ["fps": plan.fps, "durationUs": plan.durationUs,
            "playbackMode": animation.playbackMode, "frameCount": count]
        try JSONSerialization.data(withJSONObject: timing).write(to: folder.appendingPathComponent("timing.json"))
        var tick = 1
        for segment in segments {
            try autoreleasepool {
                let image = try plan.image(segment)
                var first: URL?
                for _ in 0..<Int(segment.ticks) {
                    let url = folder.appendingPathComponent(String(format: "frame_%05d.png", tick))
                    if let first { try files.linkItem(at: first, to: url) }
                    else { try Self.writePNG(image, to: url); first = url }
                    tick += 1
                }
            }
        }
        let output = files.temporaryDirectory.appendingPathComponent("TipStroke-\(UUID().uuidString).zip")
        var coordinationError: NSError?
        var copyResult: Result<Void, Error>?
        NSFileCoordinator().coordinate(readingItemAt: folder, options: .forUploading, error: &coordinationError) { archive in
            copyResult = Result { try files.copyItem(at: archive, to: output) }
        }
        do {
            if let coordinationError { throw coordinationError }
            guard let copyResult else { throw DrawingError.message("Could not package animation frames.") }
            try copyResult.get()
            return output
        } catch {
            try? files.removeItem(at: output)
            throw error
        }
    }
    func exportGIF(_ snapshot: DocumentSnapshot, smooth: Bool = false) throws -> URL {
        guard let animation = snapshot.record.animation else { throw DrawingError.message("Create an animation first.") }
        let plan = try AnimationRenderPlan(snapshot, smooth: smooth)
        let order = plan.samples
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("TipStroke-\(UUID().uuidString).gif")
        var finished = false
        defer { if !finished { try? FileManager.default.removeItem(at: url) } }
        guard let destination = CGImageDestinationCreateWithURL(url as CFURL, UTType.gif.identifier as CFString, order.count, nil) else {
            throw DrawingError.message("Could not create the animation export.")
        }
        if animation.playbackMode != "ONCE" {
            CGImageDestinationSetProperties(destination, [kCGImagePropertyGIFDictionary: [kCGImagePropertyGIFLoopCount: 0]] as CFDictionary)
        }
        for segment in order {
            try autoreleasepool {
                let image = try plan.image(segment)
                let delay = Double(segment.durationUs) / 1e6
                CGImageDestinationAddImage(destination, image, [kCGImagePropertyGIFDictionary: [kCGImagePropertyGIFDelayTime: delay, kCGImagePropertyGIFUnclampedDelayTime: delay]] as CFDictionary)
            }
        }
        guard CGImageDestinationFinalize(destination) else { throw DrawingError.message("Could not finish the animation export.") }
        finished = true
        return url
    }
}

extension DocumentRecord {
    var paperColor: UIColor {
        let color = backgroundARGB ?? 0xffffffff
        return UIColor(red: CGFloat((color >> 16) & 255) / 255, green: CGFloat((color >> 8) & 255) / 255,
                       blue: CGFloat(color & 255) / 255, alpha: CGFloat(backgroundOpacity ?? 1))
    }
}

final class TimelineController: UITableViewController {
    let document: RasterDocument
    var onChange: (() -> Void)?
    var onRecordMotion: (() -> Void)?
    var onRecordDrawing: (() -> Void)?
    private var timer: Timer?
    private var playbackTick: Int64 = 0
    private var playbackStart: TimeInterval = 0
    init(document: RasterDocument) { self.document = document; super.init(style: .insetGrouped) }
    required init?(coder: NSCoder) { fatalError("Use init(document:)") }
    override func viewDidLoad() {
        super.viewDidLoad(); title = "Animation"
        navigationItem.rightBarButtonItems = [
            sheetDoneButton(),
            UIBarButtonItem(title: "Play", primaryAction: UIAction { [weak self] _ in self?.togglePlayback() })
        ]
        navigationItem.leftBarButtonItem = UIBarButtonItem(title: "Settings", primaryAction: UIAction { [weak self] _ in self?.settings() })
        tableView.register(UITableViewCell.self, forCellReuseIdentifier: "frame")
    }
    override func viewDidDisappear(_ animated: Bool) { super.viewDidDisappear(animated); stopPlayback() }
    deinit { timer?.invalidate() }
    private func changed() { tableView.reloadData(); onChange?() }
    override func numberOfSections(in tableView: UITableView) -> Int { 2 }
    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { section == 0 ? (document.record.media == nil ? 4 : 2) : document.record.animation?.frames.count ?? 0 }
    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "frame", for: indexPath)
        var content = cell.defaultContentConfiguration()
        if indexPath.section == 0 { content.text = ["Add blank frame", "Duplicate current frame", "Record drawing live", "Record photo motion"][indexPath.row] }
        else if let animation = document.record.animation {
            content.text = "Frame \(indexPath.row + 1) · \(animation.frames[indexPath.row].exposure) ticks"
            content.image = UIImage(systemName: animation.selectedIndex == indexPath.row ? "checkmark.circle.fill" : "circle")
        }
        cell.contentConfiguration = content; return cell
    }
    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        stopPlayback()
        do {
            if indexPath.section == 0 && indexPath.row >= 2 {
                let record = indexPath.row == 2 ? onRecordDrawing : onRecordMotion
                dismiss(animated: true) { record?() }; return
            }
            if indexPath.section == 0 { try document.addFrame(copy: indexPath.row == 1) }
            else { document.selectTimelineFrame(indexPath.row) }
            changed()
        } catch { showError(error) }
    }
    override func tableView(_ tableView: UITableView, trailingSwipeActionsConfigurationForRowAt indexPath: IndexPath) -> UISwipeActionsConfiguration? {
        guard indexPath.section == 1 else { return nil }
        return UISwipeActionsConfiguration(actions: [UIContextualAction(style: .destructive, title: "Delete") { [weak self] _, _, done in
            guard let self else { done(false); return }; self.stopPlayback()
            let remove = {
                do { try self.document.deleteFrame(); self.changed(); done(true) }
                catch { self.showError(error); done(false) }
            }
            self.document.selectFrame(indexPath.row) {
                let target = min(indexPath.row + 1, (self.document.record.animation?.frames.count ?? 1) - 1)
                self.document.prepareFrame(target == indexPath.row ? max(0, target-1) : target, then: remove)
            }
        }])
    }
    private func settings() {
        stopPlayback()
        guard let animation = document.record.animation else { return }
        let alert = UIAlertController(title: "Animation", message: "Frames per second (1–60), current hold (1–600), and playback: LOOP, PING_PONG, ONCE", preferredStyle: .alert)
        for value in [String(animation.fps), String(animation.frames[animation.selectedIndex].exposure), animation.playbackMode] { alert.addTextField { $0.text = value } }
        alert.addAction(UIAlertAction(title: animation.onionSkin ? "Disable onion skin" : "Enable onion skin", style: .default) { [weak self] _ in
            guard let self else { return }; self.document.beginMetadataEdit(); self.document.record.animation?.onionSkin.toggle(); self.document.endMetadataEdit(); self.changed()
        })
        alert.addAction(UIAlertAction(title: "Apply", style: .default) { [weak self, weak alert] _ in
            guard let self, let fields = alert?.textFields, let fps = Int(fields[0].text ?? ""), let hold = Int(fields[1].text ?? ""),
                  (1...60).contains(fps), (1...600).contains(hold), let mode = fields[2].text?.uppercased(), ["LOOP", "PING_PONG", "ONCE"].contains(mode) else { return }
            self.document.beginMetadataEdit(); self.document.record.animation?.fps = fps
            self.document.record.animation?.frames[animation.selectedIndex].exposure = hold
            self.document.record.animation?.playbackMode = mode; self.document.endMetadataEdit(); self.changed()
        })
        let shared = animation.sharedLayerIDs.contains(document.activeLayer)
        alert.addAction(UIAlertAction(title: shared ? "Animate selected layer separately" : "Share selected layer across frames", style: .default) { [weak self] _ in
            self?.document.toggleSharedBackground(); self?.changed()
        })
        for divisor in [2, 4] {
            alert.addAction(UIAlertAction(title: "Reduce resolution to \(100 / divisor)%…", style: .default) { [weak self] _ in self?.confirmReduction(divisor) })
        }
        if document.record.media != nil {
            let frame = animation.frames[animation.selectedIndex]
            alert.addAction(UIAlertAction(title: "Set drawing coverage…", style: .default) { [weak self] _ in
                self?.editCoverage(frame: frame, fps: animation.fps)
            })
        } else {
            alert.addAction(UIAlertAction(title: "Move frame earlier", style: .default) { [weak self] _ in self?.document.moveFrame(-1); self?.changed() })
            alert.addAction(UIAlertAction(title: "Move frame later", style: .default) { [weak self] _ in self?.document.moveFrame(1); self?.changed() })
        }
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel)); present(alert, animated: true)
    }
    private func editCoverage(frame: AnimationFrameRecord, fps: Int) {
        let alert = UIAlertController(title: "Drawing coverage", message: "Enter the start time and duration in seconds.", preferredStyle: .alert)
        let duration = frame.durationUs ?? Int64(frame.exposure) * 1_000_000 / Int64(fps)
        for (label, us) in [("Start", frame.sourceTimeUs ?? 0), ("Duration", duration)] {
            alert.addTextField { field in
                field.placeholder = label; field.accessibilityLabel = label
                field.keyboardType = .decimalPad; field.text = String(Double(us) / 1_000_000)
            }
        }
        alert.addAction(UIAlertAction(title: "Apply", style: .default) { [weak self, weak alert] _ in
            guard let self, let fields = alert?.textFields else { return }
            let times = fields.compactMap { Double($0.text ?? "") }.compactMap { Int64(exactly: ($0 * 1_000_000).rounded()) }
            do {
                guard times.count == 2 else { throw DrawingError.message("Enter a valid time in seconds.") }
                try self.document.retimeDrawing(id: frame.id, start: times[0], duration: times[1]); self.changed()
            } catch { self.showError(error) }
        })
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        present(alert, animated: true)
    }
    private func confirmReduction(_ divisor: Int) {
        let alert = UIAlertController(title: "Reduce all frames to \(100 / divisor)%?",
            message: "The drawing will keep its layers and timeline. Resampling clears undo history.", preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        alert.addAction(UIAlertAction(title: "Reduce", style: .destructive) { [weak self] _ in
            guard let self else { return }
            let snapshot = self.document.snapshot(), revision = self.document.revision
            self.navigationController?.view.isUserInteractionEnabled = false
            self.navigationController?.isModalInPresentation = true
            ProjectStore.shared.queue.async {
                let result = Result { try ProjectStore.reduceResolution(snapshot, divisor: divisor) }
                DispatchQueue.main.async {
                    self.navigationController?.view.isUserInteractionEnabled = true
                    self.navigationController?.isModalInPresentation = false
                    do { try self.document.installReduction(result.get(), expectedRevision: revision); self.changed() }
                    catch { self.showError(error) }
                }
            }
        })
        present(alert, animated: true)
    }
    private func stopPlayback() { timer?.invalidate(); timer = nil; navigationItem.rightBarButtonItems?.last?.title = "Play" }
    private func togglePlayback() {
        if timer != nil { stopPlayback(); return }
        guard let animation = document.record.animation else { return }
        playbackTick = Int64(animation.frames.prefix(animation.selectedIndex).reduce(0) { $0 + $1.exposure })
        playbackStart = ProcessInfo.processInfo.systemUptime
        navigationItem.rightBarButtonItems?.last?.title = "Stop"
        let timeline = animation.shared
        timer = Timer.scheduledTimer(withTimeInterval: 1.0 / Double(animation.fps), repeats: true) { [weak self] _ in
            guard let self else { return }
            let elapsed = ProcessInfo.processInfo.systemUptime - self.playbackStart
            let tick = self.playbackTick + Int64(elapsed * Double(animation.fps))
            let next = Int(timeline.frameAt(tick: tick))
            if !self.document.loadingCel, self.document.record.animation?.selectedIndex != next { self.document.selectTimelineFrame(next); self.changed() }
            if animation.playbackMode == "ONCE" && tick >= Int64(timeline.totalTicks) { self.stopPlayback() }
        }
    }
}

extension AnimationRecord {
    var shared: NativeTimeline {
        NativeTimeline(exposures: frames.map { String($0.exposure) }.joined(separator: ","), fps: Int32(fps), mode: playbackMode)
    }
}
