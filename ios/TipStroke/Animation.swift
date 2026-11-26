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
           animation.neighbors(of: index).contains(where: { coldFrames[animation.frames[$0].id] != nil }) {
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

final class TimelineController: UICollectionViewController {
    let document: RasterDocument
    var onChange: (() -> Void)?
    var onClose: (() -> Void)?
    var onImportMedia: (() -> Void)?
    var onRecordMotion: (() -> Void)?
    var onRecordDrawing: (() -> Void)?
    private var timer: Timer?
    let scrubber = UISlider()
    private let previews = NSCache<NSUUID, UIImage>()
    private var previewRevision: UInt64?
    private var loadingPreview = false
    private var playbackTick: Int64 = 0
    private var playbackStart: TimeInterval = 0
    init(document: RasterDocument) {
        self.document = document
        let layout = UICollectionViewFlowLayout()
        layout.scrollDirection = .horizontal
        layout.itemSize = CGSize(width: 72, height: 54)
        layout.sectionInset = UIEdgeInsets(top: 4, left: 8, bottom: 4, right: 8)
        super.init(collectionViewLayout: layout)
    }
    required init?(coder: NSCoder) { fatalError("Use init(document:)") }
    override func viewDidLoad() {
        super.viewDidLoad()
        title = "Animation"
        navigationItem.rightBarButtonItems = [UIBarButtonItem(title: "Done", primaryAction: UIAction { [weak self] _ in self?.onClose?() }),
            UIBarButtonItem(title: "Play", primaryAction: UIAction { [weak self] _ in self?.togglePlayback() })]
        let add = UIBarButtonItem(systemItem: .add, primaryAction: UIAction { [weak self] _ in self?.add(copy: false) })
        let options = UIBarButtonItem(image: UIImage(systemName: "ellipsis"), menu: UIMenu(children: [
            UIDeferredMenuElement.uncached { [weak self] done in done(self?.timelineOptions() ?? []) }
        ]))
        options.accessibilityLabel = "Animation options"
        let duplicate = UIBarButtonItem(image: UIImage(systemName: "plus.square.on.square"), primaryAction: UIAction { [weak self] _ in self?.add(copy: true) })
        duplicate.accessibilityLabel = "Duplicate selected animation frame"
        navigationItem.leftBarButtonItems = [add, duplicate, options]
        collectionView.backgroundColor = UIColor(white: 0.13, alpha: 1)
        collectionView.register(UICollectionViewCell.self, forCellWithReuseIdentifier: "frame")
        previews.countLimit = 40
        scrubber.accessibilityLabel = "Timeline scrubber"
        scrubber.frame.size = CGSize(width: 240, height: 32)
        scrubber.addAction(UIAction { [weak self] _ in
            guard let self, let a = self.document.record.animation else { return }
            self.stopPlayback()
            let next = Int(a.shared.frameAt(tick: Int64(self.scrubber.value.rounded())))
            if next != a.selectedIndex { self.document.selectTimelineFrame(next); self.changed() }
        }, for: .valueChanged)
        toolbarItems = [UIBarButtonItem(customView: scrubber)]
        collectionView.addGestureRecognizer(UILongPressGestureRecognizer(target: self, action: #selector(reorder(_:))))
    }
    @objc private func reorder(_ press: UILongPressGestureRecognizer) {
        let point = press.location(in: collectionView)
        switch press.state {
        case .began:
            guard document.record.media == nil, let path = collectionView.indexPathForItem(at: point) else { return }
            stopPlayback()
            collectionView.beginInteractiveMovementForItem(at: path)
        case .changed: collectionView.updateInteractiveMovementTargetPosition(point)
        case .ended: collectionView.endInteractiveMovement()
        default: collectionView.cancelInteractiveMovement()
        }
    }
    override func collectionView(_ collectionView: UICollectionView, moveItemAt source: IndexPath, to target: IndexPath) {
        document.selectFrame(source.item) {
            self.document.moveFrame(target.item - source.item)
            self.changed()
        }
    }
    private func recordingActions() -> [UIAction] {
        guard document.record.media == nil else { return [] }
        return [("Record drawing live", onRecordDrawing), ("Record photo motion", onRecordMotion)].map { name, action in
            UIAction(title: name) { [weak self] _ in self?.onClose?(); action?() }
        }
    }
    override func viewDidDisappear(_ animated: Bool) { super.viewDidDisappear(animated); stopPlayback() }
    deinit { timer?.invalidate() }
    func refreshFrames() {
        guard isViewLoaded, let animation = document.record.animation else { return }
        if previewRevision != document.revision {
            previews.removeAllObjects()
            previewRevision = document.revision
        }
        collectionView.reloadData()
        scrubber.maximumValue = Float(max(1, animation.frames.reduce(0) { $0 + $1.exposure } - 1))
        if !scrubber.isTracking { scrubber.value = Float(animation.frames.prefix(animation.selectedIndex).reduce(0) { $0 + $1.exposure }) }
    }
    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        loadVisiblePreview()
    }
    private func loadVisiblePreview() {
        guard !loadingPreview, view.window != nil, let animation = document.record.animation else { return }
        let missing = collectionView.indexPathsForVisibleItems.first { path in
            animation.frames.indices.contains(path.item) && previews.object(forKey: animation.frames[path.item].id as NSUUID) == nil
        }
        guard let missing else { return }
        let id = animation.frames[missing.item].id, snapshot = document.snapshot(), revision = document.revision
        loadingPreview = true
        ProjectStore.shared.queue.async { [weak self] in
            let thumb = try? ProjectStore.thumbnail(snapshot.atFrame(id))
            DispatchQueue.main.async {
                guard let self else { return }
                self.loadingPreview = false
                guard self.document.revision == revision, self.view.window != nil else { return }
                self.previews.setObject(thumb.map { UIImage(cgImage: $0) } ?? UIImage(), forKey: id as NSUUID)
                if self.document.record.animation?.frames.indices.contains(missing.item) == true {
                    self.collectionView.reloadItems(at: [missing])
                }
                self.loadVisiblePreview()
            }
        }
    }
    func pausePlayback() { stopPlayback() }
    private func changed() { onChange?() }
    override func collectionView(_ collectionView: UICollectionView, numberOfItemsInSection section: Int) -> Int {
        document.record.animation?.frames.count ?? 0
    }
    override func collectionView(_ collectionView: UICollectionView, cellForItemAt path: IndexPath) -> UICollectionViewCell {
        let cell = collectionView.dequeueReusableCell(withReuseIdentifier: "frame", for: path)
        guard let animation = document.record.animation else { return cell }
        var content = UIListContentConfiguration.cell()
        content.text = String(path.item + 1)
        content.secondaryText = "× " + String(animation.frames[path.item].exposure)
        content.image = previews.object(forKey: animation.frames[path.item].id as NSUUID)
        content.imageProperties.maximumSize = CGSize(width: 32, height: 32)
        content.directionalLayoutMargins = NSDirectionalEdgeInsets(top: 2, leading: 2, bottom: 2, trailing: 2)
        cell.contentConfiguration = content
        cell.backgroundColor = animation.selectedIndex == path.item ? .systemIndigo : .secondarySystemBackground
        cell.layer.cornerRadius = 8
        return cell
    }
    override func collectionView(_ collectionView: UICollectionView, didSelectItemAt path: IndexPath) {
        stopPlayback(); document.selectTimelineFrame(path.item); changed()
    }
    private func add(copy: Bool) {
        stopPlayback()
        do { try document.addFrame(copy: copy); changed() } catch { showError(error) }
    }
    private func deleteCurrent() {
        stopPlayback()
        guard let animation = document.record.animation else { return }
        let next = min(animation.selectedIndex + 1, animation.frames.count - 1)
        document.prepareFrame(next == animation.selectedIndex ? max(0, next - 1) : next) {
            do { try self.document.deleteFrame(); self.changed() } catch { self.showError(error) }
        }
    }
    private func timelineOptions() -> [UIMenuElement] {
        guard let a = document.record.animation else { return [] }
        let i = a.selectedIndex
        func action(_ name: String, enabled: Bool = true, _ perform: @escaping () -> Void) -> UIAction {
            UIAction(title: name, attributes: enabled ? [] : .disabled) { [weak self] _ in self?.stopPlayback(); perform() }
        }
        let frame = UIMenu(title: "Frame actions", children: [
            action("Delete frame", enabled: a.frames.count > 1) { self.deleteCurrent() },
            action("Move frame left", enabled: i > 0) { self.document.moveFrame(-1); self.changed() },
            action("Move frame right", enabled: i + 1 < a.frames.count) { self.document.moveFrame(1); self.changed() },
        ] + [-1, 1].map { delta in
            action("Hold " + (delta < 0 ? "−" : "+") + " (\(a.frames[i].exposure))") {
                self.updateAnimation { value in value.frames[value.selectedIndex].exposure = min(600, max(1, value.frames[value.selectedIndex].exposure + delta)) }
            }
        })
        var layers: [UIAction] = [action(a.sharedLayerIDs.contains(document.activeLayer) ? "Make animated cel" : "Use as shared background") {
            self.document.toggleSharedBackground(); self.changed()
        }]
        if document.activePhoto == nil {
            for wholeCel in [true, false] where wholeCel || document.selection != nil {
                for cut in [false, true] {
                    layers.append(action((cut ? "Move " : "Copy ") + (wholeCel ? "cel" : "selection") + " to new layer") {
                        do { try self.document.transferSelection(cut: cut, wholeCel: wholeCel); self.changed() }
                        catch { self.showError(error) }
                    })
                }
            }
        }
        let playback = UIMenu(title: "Playback", children: [action("Frame rate · \(a.fps) fps") { self.chooseFps() }] +
            ["LOOP", "PING_PONG", "ONCE"].map { mode in
                UIAction(title: mode.replacingOccurrences(of: "_", with: " ").capitalized,
                         state: a.playbackMode == mode ? .on : .off) { _ in self.stopPlayback(); self.updateAnimation { $0.playbackMode = mode } }
            })
        var onions: [UIAction] = []
        for delta in [-1, 1] {
            let sign = delta < 0 ? "−" : "+"
            onions.append(action("Previous " + sign + " (\(a.previousCount))") {
                self.updateOnion(before: a.previousCount + delta, after: a.nextCount, opacity: a.ghostOpacity)
            })
            onions.append(action("Next " + sign + " (\(a.nextCount))") {
                self.updateOnion(before: a.previousCount, after: a.nextCount + delta, opacity: a.ghostOpacity)
            })
            onions.append(action("Opacity " + sign + " (\(Int((100 * a.ghostOpacity).rounded()))%)") {
                self.updateOnion(before: a.previousCount, after: a.nextCount, opacity: a.ghostOpacity + Double(delta) * 0.1)
            })
        }
        var items: [UIMenuElement] = [action(navigationController?.isToolbarHidden == true ? "Show scrubber" : "Hide scrubber") {
            guard let nav = self.navigationController else { return }
            nav.setToolbarHidden(!nav.isToolbarHidden, animated: false)
            self.refreshFrames()
            nav.parent?.view.setNeedsLayout()
        }, action("Add photo / video") { self.onImportMedia?() }, frame,
            UIMenu(title: "Selected layer", children: layers), playback, UIMenu(title: "Onion skins", children: onions)]
        items.append(contentsOf: recordingActions())
        if document.record.media != nil {
            items.append(action("Set drawing coverage…") { self.editCoverage(frame: a.frames[i], fps: a.fps) })
        }
        items.append(UIMenu(title: "Reduce resolution", children: [2, 4].map { divisor in
            action("Reduce to \(100 / divisor)%…") { self.confirmReduction(divisor) }
        }))
        return items
    }
    private func updateAnimation(_ update: (inout AnimationRecord) -> Void) {
        guard var animation = document.record.animation else { return }
        document.beginMetadataEdit()
        update(&animation)
        document.record.animation = animation
        document.endMetadataEdit()
        changed()
    }
    private func updateOnion(before: Int, after: Int, opacity: Double) {
        updateAnimation {
            $0.onionBefore = max(0, min(6, before)); $0.onionAfter = max(0, min(6, after))
            $0.onionSkin = ($0.onionBefore ?? 0) + ($0.onionAfter ?? 0) > 0
            $0.onionOpacity = max(0.05, min(1, opacity))
        }
        if let i = document.record.animation?.selectedIndex { document.prepareFrame(i) { [weak self] in self?.document.onCelReady?() } }
    }
    private func chooseFps() {
        let alert = UIAlertController(title: "Frames per second", message: "1–60 fps", preferredStyle: .alert)
        alert.addTextField { field in field.keyboardType = .numberPad; field.text = String(self.document.record.animation?.fps ?? 12) }
        alert.addAction(UIAlertAction(title: "Set", style: .default) { [weak self, weak alert] _ in
            guard let fps = Int(alert?.textFields?.first?.text ?? ""), (1...60).contains(fps) else { return }
            self?.updateAnimation { $0.fps = fps }
        })
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        present(alert, animated: true)
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
