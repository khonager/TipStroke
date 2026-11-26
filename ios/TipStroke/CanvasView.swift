import UIKit
import TipStrokeShared

final class CanvasView: UIView, UIGestureRecognizerDelegate {
    enum Tool: String, CaseIterable {
        case pencil = "Pencil", ink = "Ink", airbrush = "Airbrush", eraser = "Eraser"
        var brushID: String {
            switch self { case .pencil: return "pencil-v1"; case .airbrush: return "airbrush-v1"; default: return "ink-v1" }
        }
    }
    var previewSnapshot: DocumentSnapshot?
    weak var viewport: CanvasViewport?
    private let mediaBackdrop = MediaBackdrop()
    private var backdropPieces: [CALayer] = []
    private var backdropHoles: Set<TileKey> = []
    private var backdropBounds = CGRect.null
    private var blendsWithBackdrop: Bool {
        (previewSnapshot?.record ?? document.record).layers.contains { $0.visible && $0.opacity > 0 && $0.blendMode != "NORMAL" }
    }
    let document: RasterDocument
    private var selectedMode: CanvasMode = .paint
    private var fingerSmudge = false
    var mode: CanvasMode {
        get { fingerSmudge ? .smudge : selectedMode }
        set { cancelStroke(); selectedMode = newValue }
    }
    private var holdGesture: UILongPressGestureRecognizer!
    private var commandDrag: UIPanGestureRecognizer!
    private var dragCommand: FingerCommand = .draw
    private var holdCommand: FingerCommand = .pick
    var onFingerHistory: ((FingerCommand) -> Void)?
    var filledShapes = false
    var quickShapes = true
    var elasticLine = false
    var perspectiveMode = 0
    var vanishingPoint1: CGPoint?
    var vanishingPoint2: CGPoint?
    var placingVanishingPoint = 0 { didSet { updateInteractionHint() } }
    private var magneticRuler: NativePerspectiveRuler?
    var placingMirrorCenter = false { didSet { updateInteractionHint() } }
    var shadowPull = false { didSet { updateInteractionHint() } }
    var onInteractionHint: ((String?) -> Void)?
    private func updateInteractionHint() {
        let hint: String? = liveDrawing != nil ? "Recording drawing · Stop" : shadowPull ? "Shadow pull: drag selected paint · Cancel" : placingMirrorCenter ? "Touch to place mirror center · Cancel" : placingVanishingPoint > 0 ? "Touch to place vanishing point \(placingVanishingPoint) · Cancel" : nil
        onInteractionHint?(hint)
    }
    private var shadowTiles: [TileKey: CGImage] = [:]
    private var elasticShape: NativeShape?
    private var bendAnchor = CGPoint.zero
    var onFill: ((CGPoint) -> Void)?
    private let hoverLayer = CAShapeLayer()
    private let outlineLayer = CAShapeLayer()
    private var gesturePoints: [CGPoint] = []
    private var shapeTracker = NativeShapeTracker()
    private var prefetchingID: UUID?
    private var waitingForCelSince: TimeInterval?
    private var recordingLink: CADisplayLink?
    private var liveDrawing: (start: TimeInterval, index: Int, layer: UUID)?
    func startLiveDrawing() throws {
        guard document.record.media == nil, let a = document.record.animation else { return }
        cancelStroke()
        guard document.addLayer() else { throw DrawingError.message("The drawing has reached its layer limit.") }
        document.record.layers[document.record.layers.count - 1].name = "Live drawing"
        liveDrawing = (ProcessInfo.processInfo.systemUptime, a.selectedIndex, document.activeLayer)
        prefetchNextRecordingCel()
        recordingLink?.invalidate()
        let link = CADisplayLink(target: self, selector: #selector(advanceIdleRecording))
        link.preferredFramesPerSecond = a.fps
        link.add(to: .main, forMode: .common); recordingLink = link; updateInteractionHint()
    }
    func stopLiveDrawing() {
        guard liveDrawing != nil else { return }
        recordingLink?.invalidate(); recordingLink = nil; liveDrawing = nil; waitingForCelSince = nil; prefetchingID = nil
        if pointer != nil {
            document.endStroke(cancelled: false); document.finishRecordingGesture(cancelled: false)
            pointer = nil; previous = nil
        }
        updateInteractionHint(); onCommit?()
    }
    @objc private func advanceIdleRecording() {
        guard pointer == nil else { return }
        do { try advanceRecording(at: ProcessInfo.processInfo.systemUptime, resumeStroke: false) }
        catch { stopLiveDrawing(); onError?(error) }
    }
    private func prefetchNextRecordingCel() {
        guard let session = liveDrawing, let a = document.record.animation,
              a.frames.indices.contains(a.selectedIndex + 1) else { return }
        let id = a.frames[a.selectedIndex + 1].id
        guard document.coldFrames[id] != nil, prefetchingID != id else { return }
        prefetchingID = id
        document.prefetchRecordingFrame(id) { [weak self] result in
            guard let self, self.liveDrawing?.layer == session.layer, self.prefetchingID == id else { return }
            self.prefetchingID = nil
            if case .failure(let error) = result { self.stopLiveDrawing(); self.onError?(error); return }
            if let paused = self.waitingForCelSince {
                self.liveDrawing?.start += max(0, ProcessInfo.processInfo.systemUptime - paused)
                self.waitingForCelSince = nil
            }
        }
    }
    private func advanceRecording(at time: TimeInterval, resumeStroke: Bool) throws {
        guard let session = liveDrawing, let a = document.record.animation else { return }
        guard document.activeLayer == session.layer, mode == .paint else { stopLiveDrawing(); return }
        let target = session.index + Int(max(0, time - session.start) * Double(a.fps))
        guard target > a.selectedIndex else { return }
        var readyTarget = target
        let lastExisting = min(target, a.frames.count - 1)
        if lastExisting > a.selectedIndex,
           let cold = ((a.selectedIndex+1)...lastExisting).first(where: { document.coldFrames[a.frames[$0].id] != nil }) {
            readyTarget = cold - 1
            if waitingForCelSince == nil { waitingForCelSince = ProcessInfo.processInfo.systemUptime }
        }
        guard readyTarget > a.selectedIndex else { prefetchNextRecordingCel(); return }
        if resumeStroke { document.endStroke(cancelled: false) }
        for i in (a.selectedIndex+1)...readyTarget {
            let carry = document.pixels[session.layer]
            if i < (document.record.animation?.frames.count ?? 0) {
                document.installFrame(i)
            } else { try document.addFrame(copy: false) }
            document.pixels[session.layer] = carry
        }
        if resumeStroke && !document.beginStroke() { throw DrawingError.message("Cannot continue recording on this layer.") }
        refresh()
        prefetchNextRecordingCel()
    }
    private var holdWork: DispatchWorkItem?
    private var fitted = false
    private var previewLink: CADisplayLink?
    private var previewPending = false
    private var liveFill: Bool { mode == .lassoFill }
    @objc private func renderLivePreview() {
        guard previewPending, pointer != nil else { return }
        previewPending = false
        let restoresPreview = mode != .colorPush
        if restoresPreview { document.resetStrokePreview() }
        do {
            if mode == .colorPush, let p = pointer?.location(in: self) {
                try document.pushColor(from: previous ?? gesturePoints[0], to: p, radius: strokeSize / 2, strength: strokeOpacity)
                previous = p
            } else if shadowPull {
                let p = pointer?.location(in: self) ?? gesturePoints[0]
                try document.paintShadow(shadowTiles, offset: CGPoint(x: p.x - gesturePoints[0].x, y: p.y - gesturePoints[0].y))
            } else if let elasticShape {
                let delta = (pointer?.location(in: self) ?? bendAnchor)
                let points = elasticShape.bend(x: Float(delta.x - bendAnchor.x), y: Float(delta.y - bendAnchor.y)).points.map { CGPoint(x: CGFloat($0.x), y: CGFloat($0.y)) }
                try paintFitted(points)
            } else {
                try document.paintPath(mode.outline(gesturePoints), color: strokeColor.withAlphaComponent(strokeOpacity), width: 0, filled: true)
            }
            if restoresPreview { refresh(document.strokePreviewBounds) }
            else if let previous { refresh(CGRect(x: previous.x, y: previous.y, width: 1, height: 1).insetBy(dx: -min(96, strokeSize / 2) - 2, dy: -min(96, strokeSize / 2) - 2)) }
        } catch { cancelStroke(); onError?(error) }
    }
    var tool: Tool = .ink
    var brush = NativeBrush.companion.builtIn(id: "ink-v1") {
        didSet {
            do { stampRenderer = brush.usesStamps && !brush.usesSweptTip ? try NativeStampRenderer(brush: brush) : nil }
            catch { stampRenderer = nil; onError?(error) }
        }
    }
    private let graphite = GraphiteRenderer()
    private let roundStroke = StrokeCoverage()
    private var stampRenderer: NativeStampRenderer?
    private var stampPath: NativeStampPath?
    var brushColor: UIColor = .black
    var pixelErasing = false
    var brushSize: CGFloat = 8
    var sizeInScreenPoints = UserDefaults.standard.bool(forKey: "brush.screenPoints")
    var supportsScreenSize: Bool { ![.pixel, .pixelLine, .dither, .smudge].contains(mode) }
    var resolvedBrushSize: CGFloat {
        sizeInScreenPoints && supportsScreenSize ? brushSize / max(0.001, viewport?.zoomScale ?? 1) : brushSize
    }
    var brushOpacity: CGFloat = 1
    var fingerDrawing = true
    var onCommit: (() -> Void)?
    var onPickColor: ((UIColor) -> Void)?
    var onError: ((Error) -> Void)?
    var isDrawing: Bool { pointer != nil }
    private var displayError: String?
    private var displayTiles: [TileKey: CALayer] = [:]
    private var pointer: UITouch?
    private var previous: CGPoint?
    private var previousTimestamp: TimeInterval = -1
    private var strokeBrush = NativeBrush.companion.builtIn(id: "ink-v1")
    private var strokeTool: Tool = .ink
    private var strokeColor = UIColor.black
    private var strokeSize: CGFloat = 8
    private var strokeOpacity: CGFloat = 1

    init(document: RasterDocument) {
        self.document = document
        super.init(frame: document.bounds)
        backgroundColor = .white
        outlineLayer.strokeColor = UIColor.systemOrange.cgColor
        outlineLayer.fillColor = UIColor.clear.cgColor; outlineLayer.lineWidth = 1
        outlineLayer.lineDashPattern = [5, 4]; outlineLayer.zPosition = 10_000
        layer.addSublayer(mediaBackdrop.layer)
        mediaBackdrop.onFrame = { [weak self] in
            guard let self, self.blendsWithBackdrop else { return }
            self.refresh()
        }
        mediaBackdrop.onError = { [weak self] error in self?.onError?(error) }
        layer.addSublayer(outlineLayer)
        hoverLayer.zPosition = 10_001
        hoverLayer.strokeColor = UIColor.label.cgColor
        hoverLayer.fillColor = nil
        hoverLayer.shadowColor = UIColor.systemBackground.cgColor
        hoverLayer.shadowOpacity = 1; hoverLayer.shadowRadius = 1; hoverLayer.shadowOffset = .zero
        hoverLayer.actions = ["path": NSNull(), "hidden": NSNull()]
        layer.addSublayer(hoverLayer)
        let hover = UIHoverGestureRecognizer(target: self, action: #selector(hoverPencil(_:)))
        hover.allowedTouchTypes = [NSNumber(value: UITouch.TouchType.pencil.rawValue)]
        addGestureRecognizer(hover)
        isMultipleTouchEnabled = true
        isOpaque = true
        clipsToBounds = true
        isAccessibilityElement = true
        accessibilityLabel = "Drawing canvas"
        accessibilityIdentifier = document.record.id.uuidString
        let picker = UILongPressGestureRecognizer(target: self, action: #selector(pickColor(_:)))
        holdGesture = picker
        picker.minimumPressDuration = 0.55
        picker.cancelsTouchesInView = false
        picker.delegate = self
        picker.allowedTouchTypes = [NSNumber(value: UITouch.TouchType.direct.rawValue)]
        addGestureRecognizer(picker)
        commandDrag = UIPanGestureRecognizer(target: self, action: #selector(runDrag(_:)))
        commandDrag.maximumNumberOfTouches = 1
        commandDrag.allowedTouchTypes = picker.allowedTouchTypes
        addGestureRecognizer(commandDrag)
        let three = UITapGestureRecognizer(target: self, action: #selector(tapHistory(_:)))
        three.numberOfTouchesRequired = 3; three.allowedTouchTypes = picker.allowedTouchTypes
        let two = UITapGestureRecognizer(target: self, action: #selector(tapHistory(_:)))
        two.numberOfTouchesRequired = 2; two.allowedTouchTypes = picker.allowedTouchTypes
        two.require(toFail: three)
        addGestureRecognizer(two); addGestureRecognizer(three)
        accessibilityHint = "Draw with one finger or Apple Pencil. Use two fingers to pan and pinch to zoom."
    }
    required init?(coder: NSCoder) { fatalError("Use init(document:)") }
    @objc private func hoverPencil(_ gesture: UIHoverGestureRecognizer) {
        let point = gesture.location(in: self)
        guard pointer == nil, bounds.contains(point), gesture.state == .began || gesture.state == .changed else {
            hoverLayer.path = nil
            return
        }
        let width = max(1, resolvedBrushSize)
        let height = brush.usesStamps ? width * CGFloat(brush.aspectRatio) : width
        let oval = UIBezierPath(ovalIn: CGRect(x: -width / 2, y: -height / 2, width: width, height: height))
        var pose = CGAffineTransform(translationX: point.x, y: point.y)
        if #available(iOS 17.5, *) { pose = pose.rotated(by: gesture.rollAngle) }
        oval.apply(pose)
        hoverLayer.lineWidth = 1 / max(0.01, viewport?.zoomScale ?? 1)
        hoverLayer.path = oval.cgPath
    }
    // One native layer per visible tile; never allocate a document-sized UIView backing bitmap.
    func refresh(_ dirty: CGRect? = nil) {
        mediaBackdrop.update(document.record.media, bounds: bounds)
        if dirty?.isNull != true { finishFillReveal() }
        let snapshot = previewSnapshot ?? DocumentSnapshot(record: document.record, pixels: document.pixels)
        let blend = blendsWithBackdrop
        let paper = snapshot.record.paperColor
        let partialBackdrop = blend && paper.cgColor.alpha < 1 && (paper.cgColor.alpha > 0 || mediaBackdrop.image != nil)
        backgroundColor = partialBackdrop ? .clear : paper
        isOpaque = paper.cgColor.alpha == 1
        mediaBackdrop.layer.isHidden = partialBackdrop
        let visible = viewport.map { convert($0.bounds, from: $0) } ?? bounds
        let keys = Set(TileKey.intersecting(visible, width: document.record.width, height: document.record.height))
        CATransaction.begin(); CATransaction.setDisableActions(true)
        defer { CATransaction.commit() }
        for key in Array(displayTiles.keys) where !keys.contains(key) {
            displayTiles.removeValue(forKey: key)?.removeFromSuperlayer()
        }
        let dirtyRegions = dirty.map { rect in rect.isNull ? [] : document.mirrorTransforms.map { rect.applying($0) } }
        for key in keys {
            let existed = displayTiles[key] != nil
            if existed, let dirtyRegions, !dirtyRegions.contains(where: { $0.intersects(key.rect) }) { continue }
            let onion = document.record.animation?.onionSkin == true
            guard onion || snapshot.record.layers.contains(where: { $0.visible && $0.photoCelActive && (snapshot.pixels[$0.id]?[key] != nil || $0.photo?.bounds.intersects(key.rect) == true) }) else {
                displayTiles.removeValue(forKey: key)?.removeFromSuperlayer(); continue
            }
            do {
                let context = try RasterDocument.context(width: 256, height: 256)
                context.translateBy(x: -key.rect.minX, y: -key.rect.minY)
                if blend {
                    context.setFillColor(paper.cgColor)
                    context.fill(key.rect.intersection(bounds))
                    if let media = mediaBackdrop.image { RasterDocument.draw(media, in: context, rect: bounds) }
                }
                if let animation = document.record.animation, animation.onionSkin {
                    for index in animation.neighbors(of: animation.selectedIndex) {
                        let frame = animation.frames[index].id
                        guard document.coldFrames[frame] == nil else { continue }
                        var ghost = snapshot
                        ghost.record.installPhotoCels(animation, at: index)
                        ghost.pixels = (document.framePixels[frame] ?? [:]).merging(snapshot.pixels.filter {
                            animation.sharedLayerIDs.contains($0.key)
                        }) { _, shared in shared }
                        context.saveGState(); context.setAlpha(animation.ghostOpacity); context.beginTransparencyLayer(auxiliaryInfo: nil)
                        try RasterDocument.composite(ghost, in: context, visible: key.rect, excluding: animation.sharedLayerIDs)
                        context.endTransparencyLayer(); context.restoreGState()
                    }
                }
                try RasterDocument.composite(snapshot, in: context, visible: key.rect)
                guard let image = context.makeImage() else { throw DrawingError.message("Could not display this tile.") }
                let tile = displayTiles[key] ?? CALayer()
                tile.frame = key.rect; tile.contents = image; tile.contentsScale = 1
                tile.magnificationFilter = .nearest
                if !existed { layer.addSublayer(tile); displayTiles[key] = tile }
            } catch {
                if displayError != error.localizedDescription {
                    displayError = error.localizedDescription
                    onError?(error)
                }
                return
            }
        }
        if dirty?.isNull != true { displayError = nil }
        updateBackdropPieces(needed: partialBackdrop, paper: paper, refreshContents: dirty == nil)
    }

    // Blended tiles already contain the translucent paper and media. Cover only the gaps
    // behind those tiles, sharing the decoded media image across rectangular layers.
    private func updateBackdropPieces(needed: Bool, paper: UIColor, refreshContents: Bool) {
        guard needed else {
            if !backdropBounds.isNull {
                backdropPieces.forEach { $0.removeFromSuperlayer() }
                backdropPieces.removeAll(); backdropHoles.removeAll(); backdropBounds = .null
            }
            return
        }
        let holes = Set(displayTiles.keys)
        if holes != backdropHoles || backdropBounds != bounds {
            backdropHoles = holes; backdropBounds = bounds
            var regions: [CGRect] = []
            func gap(_ x: CGFloat, _ y: CGFloat, _ endX: CGFloat, _ endY: CGFloat) {
                if endX > x && endY > y { regions.append(CGRect(x: x, y: y, width: endX-x, height: endY-y)) }
            }
            var y: CGFloat = 0
            for (_, keys) in Dictionary(grouping: holes, by: \.y).sorted(by: { $0.key < $1.key }) {
                let row = keys[0].rect.intersection(bounds)
                gap(0, y, bounds.maxX, row.minY)
                var x: CGFloat = 0
                for key in keys.sorted(by: { $0.x < $1.x }) {
                    gap(x, row.minY, key.rect.minX, row.maxY)
                    x = key.rect.maxX
                }
                gap(x, row.minY, bounds.maxX, row.maxY)
                y = row.maxY
            }
            gap(0, y, bounds.maxX, bounds.maxY)
            while backdropPieces.count > regions.count { backdropPieces.removeLast().removeFromSuperlayer() }
            for (i, rect) in regions.enumerated() {
                if i == backdropPieces.count {
                    let piece = CALayer(); piece.zPosition = -1
                    layer.addSublayer(piece); backdropPieces.append(piece)
                }
                let piece = backdropPieces[i]
                piece.frame = rect
                piece.contentsRect = CGRect(x: rect.minX/bounds.width, y: rect.minY/bounds.height,
                    width: rect.width/bounds.width, height: rect.height/bounds.height)
            }
        } else if !refreshContents { return }
        for piece in backdropPieces {
            piece.backgroundColor = paper.cgColor
            piece.contents = mediaBackdrop.image
        }
    }
    private var fillRevealID = UUID()
    private var revealLayers: [(current: CALayer, old: CALayer)] = []

    func captureFillTiles(_ keys: Dictionary<TileKey, CGImage>.Keys) -> [TileKey: CGImage] {
        finishFillReveal()
        return keys.reduce(into: [:]) { result, key in
            if let contents = displayTiles[key]?.contents { result[key] = (contents as! CGImage) }
        }
    }
    private func finishFillReveal() {
        guard !revealLayers.isEmpty else { return }
        fillRevealID = UUID()
        CATransaction.begin(); CATransaction.setDisableActions(true)
        for pair in revealLayers { pair.current.mask = nil; pair.old.removeFromSuperlayer() }
        revealLayers.removeAll()
        CATransaction.commit()
    }
    func revealFill(_ keys: Dictionary<TileKey, CGImage>.Keys, from point: CGPoint, previous: [TileKey: CGImage]) {
        guard !UIAccessibility.isReduceMotionEnabled else { return }
        let visible = keys.compactMap { key in displayTiles[key].map { (key, $0) } }
        guard !visible.isEmpty else { return }
        let area = visible.reduce(CGRect.null) { $0.union($1.0.rect) }
        let radius = hypot(max(abs(point.x - area.minX), abs(point.x - area.maxX)), max(abs(point.y - area.minY), abs(point.y - area.maxY))) + 2
        let id = UUID(); fillRevealID = id
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        CATransaction.setCompletionBlock { [weak self] in if self?.fillRevealID == id { self?.finishFillReveal() } }
        for (key, current) in visible {
            let old = CALayer(); old.frame = current.frame; old.contents = previous[key]; old.magnificationFilter = .nearest
            layer.insertSublayer(old, below: current)
            revealLayers.append((current, old))
            let center = CGPoint(x: point.x - key.rect.minX, y: point.y - key.rect.minY)
            for (target, inverse) in [(old, true), (current, false)] {
                func path(_ radius: CGFloat) -> CGPath {
                    let shape = CGMutablePath()
                    if inverse { shape.addRect(current.bounds) }
                    shape.addEllipse(in: CGRect(x: center.x-radius, y: center.y-radius, width: radius*2, height: radius*2))
                    return shape
                }
                let mask = CAShapeLayer(); mask.frame = current.bounds; mask.fillRule = .evenOdd
                mask.path = path(radius); target.mask = mask
                let animation = CABasicAnimation(keyPath: "path")
                animation.fromValue = path(0.01); animation.toValue = mask.path
                animation.duration = 0.5
                mask.add(animation, forKey: "fill")
            }
        }
        CATransaction.commit()
    }
    func updateVisibleTiles() { refresh(.null) }
    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        finishFillReveal()
        hoverLayer.path = nil
        let all = event?.allTouches?.filter { $0.phase != .ended && $0.phase != .cancelled } ?? []
        if let pointer, pointer.type == .pencil { return }
        if let pencil = touches.first(where: { $0.type == .pencil }) {
            cancelStroke()
            start(pencil, event: event)
        } else if all.filter({ $0.type == .direct }).count > 1 {
            cancelStroke()
        } else if pointer == nil, fingerDrawing, dragCommand == .draw || dragCommand == .smudge,
                  let touch = touches.first(where: { $0.type == .direct }) {
            fingerSmudge = dragCommand == .smudge
            start(touch, event: event)
            if pointer == nil { fingerSmudge = false }
        }
    }
    private func start(_ touch: UITouch, event: UIEvent?) {
        if placingVanishingPoint != 0 {
            if placingVanishingPoint == 1 { vanishingPoint1 = touch.location(in: self) }
            else { vanishingPoint2 = touch.location(in: self) }
            placingVanishingPoint = 0; refreshSelection(); return
        }
        if placingMirrorCenter {
            document.mirrorCenter = touch.location(in: self); placingMirrorCenter = false; refreshSelection(); return
        }
        if mode == .paint && brush.usesStamps && !brush.usesSweptTip && stampRenderer == nil { onError?(DrawingError.message("The brush texture could not be prepared.")); return }

        if shadowPull {
            do { shadowTiles = try document.shadowSource(color: brushColor.withAlphaComponent(brushOpacity)) }
            catch { shadowPull = false; onError?(error); return }
        }
        if document.record.media != nil, mode.streamsPaint || liveFill || mode.isShape {
            do { try document.ensureDrawingCel() } catch { onError?(error); return }
        }
        if liveDrawing != nil {
            do { try advanceRecording(at: touch.timestamp, resumeStroke: false); document.checkpointRecordingGesture() }
            catch { stopLiveDrawing(); onError?(error); return }
        }
        if (mode.streamsPaint || liveFill || shadowPull || mode == .colorPush) && !document.beginStroke(mirrored: mode != .smudge && mode != .colorPush && !shadowPull, brushAids: mode != .colorPush && !shadowPull, photoErase: (mode == .paint && tool == .eraser && !shadowPull) || (pixelErasing && [.pixel, .dither].contains(mode))) { document.finishRecordingGesture(cancelled: true); return }
        pointer = touch; previous = nil; previousTimestamp = -1
        let first = vanishingPoint1 ?? CGPoint(x: bounds.width * 0.25, y: bounds.height * 0.35)
        let second = vanishingPoint2 ?? CGPoint(x: bounds.width * 0.75, y: bounds.height * 0.35)
        let scale = viewport?.zoomScale ?? 1
        magneticRuler = mode == .paint && perspectiveMode > 0 ? NativePerspectiveRuler(mode: Int32(perspectiveMode), x1: Float(first.x), y1: Float(first.y), x2: Float(second.x), y2: Float(second.y), threshold: Float(4 / scale)) : nil
        gesturePoints = [touch.location(in: self)]; shapeTracker = NativeShapeTracker(); fitted = false; elasticShape = nil
        stampPath = brush.usesStamps && !brush.usesSweptTip ? brush.path(size: Float(resolvedBrushSize), opacity: Float(brushOpacity)) : nil
        roundStroke.reset(); graphite.begin()
        strokeBrush = brush
        strokeTool = tool; strokeColor = brushColor; strokeSize = resolvedBrushSize; strokeOpacity = brushOpacity
        if liveFill || shadowPull || mode == .colorPush {
            previewLink = CADisplayLink(target: self, selector: #selector(renderLivePreview))
            previewLink?.add(to: .main, forMode: .common)
        }
        if mode.streamsPaint && !shadowPull { consume(touch, event: event) }
    }
    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let pointer, touches.contains(pointer) else { return }
        if shadowPull || mode == .colorPush || elasticShape != nil { previewPending = true; return }
        if mode.streamsPaint { if !fitted { consume(pointer, event: event) }; return }
        let point = pointer.location(in: self)
        if liveFill {
            if gesturePoints.count >= 8192 { gesturePoints = gesturePoints.enumerated().filter { $0.offset % 2 == 0 }.map(\.element) }
            gesturePoints.append(point); previewPending = true; return
        }
        if mode == .lasso { if gesturePoints.count < 8192 { gesturePoints.append(point) } }
        else { gesturePoints = [gesturePoints[0], point] }
        if mode.isSelection || mode.isShape { outlineLayer.path = mode.outline(gesturePoints) }
        if mode == .transform, let selection = document.selection {
            var t = CGAffineTransform(translationX: point.x - gesturePoints[0].x, y: point.y - gesturePoints[0].y)
            outlineLayer.path = selection.path.copy(using: &t)
        }
    }
    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let pointer, touches.contains(pointer) else { return }
        holdWork?.cancel()
        let point = pointer.location(in: self)
        var changedBounds: CGRect?
        if liveFill || shadowPull || mode == .colorPush {
            gesturePoints.append(point); previewPending = true; renderLivePreview()
            guard self.pointer != nil else { return }
            changedBounds = document.strokePreviewBounds
            document.endStroke(cancelled: false)
        } else if mode.streamsPaint {
            if elasticShape != nil { previewPending = true; renderLivePreview() }
            if !fitted { consume(pointer, event: event) }
            guard self.pointer != nil else { return }
            changedBounds = document.strokePreviewBounds
            document.endStroke(cancelled: false)
        } else {
            do {
                if mode == .lasso { gesturePoints.append(point) } else { gesturePoints = [gesturePoints[0], point] }
                if mode.isSelection { document.combineSelection(mode.outline(gesturePoints)) }
                else if mode.isShape, document.beginStroke(photoErase: mode == .pixelLine && pixelErasing) {
                    if mode == .pixelLine { try document.paintPixelLine(from: gesturePoints[0], to: point, size: Int(brushSize), color: brushColor.withAlphaComponent(brushOpacity), dither: false, erasing: pixelErasing) }
                    else { try document.paintPath(mode.outline(gesturePoints), color: brushColor.withAlphaComponent(brushOpacity), width: strokeSize, filled: filledShapes && [.rectangle, .ellipse, .triangle].contains(mode)) }
                    document.endStroke(cancelled: false)
                } else if mode == .transform {
                    try document.transformSelection(CGAffineTransform(translationX: point.x - gesturePoints[0].x, y: point.y - gesturePoints[0].y))
                }
            } catch { document.endStroke(cancelled: true); onError?(error) }
        }
        document.finishRecordingGesture(cancelled: false)
        previewLink?.invalidate(); previewLink = nil
        roundStroke.reset(); graphite.begin()
        let endedMode = mode
        self.pointer = nil; fingerSmudge = false; previous = nil; shadowPull = false; shadowTiles = [:]
        refresh(changedBounds); refreshSelection(); onCommit?()
        if endedMode == .fill { onFill?(point) }
    }
    func refreshSelection() {
        if perspectiveMode > 0 {
            let path = CGMutablePath()
            if let selection = document.selection { path.addPath(selection.path) }
            let points = [vanishingPoint1 ?? CGPoint(x: bounds.width * 0.25, y: bounds.height * 0.35), vanishingPoint2 ?? CGPoint(x: bounds.width * 0.75, y: bounds.height * 0.35)]
            for c in points.prefix(perspectiveMode) {
                for i in 0..<12 {
                    let angle = CGFloat(i) * .pi / 6, length = max(bounds.width, bounds.height) * 2
                    path.move(to: c); path.addLine(to: CGPoint(x: c.x + cos(angle) * length, y: c.y + sin(angle) * length))
                }
                path.addEllipse(in: CGRect(x: c.x - 6, y: c.y - 6, width: 12, height: 12))
            }
            outlineLayer.path = path; return
        }
        if let selection = document.selection { outlineLayer.path = selection.path; return }
        guard document.mirrorCopies >= 2 else { outlineLayer.path = nil; return }
        let c = document.mirrorCenter ?? CGPoint(x: bounds.midX, y: bounds.midY)
        let path = CGMutablePath()
        let count = document.mirrorCopies == 2 ? 1 : document.mirrorCopies
        for i in 0..<count {
            let angle = document.mirrorCopies == 2 ? CGFloat.pi / 2 : CGFloat(i) * 2 * .pi / CGFloat(count)
            let dx = cos(angle) * bounds.width * 2, dy = sin(angle) * bounds.height * 2
            path.move(to: CGPoint(x: c.x - dx, y: c.y - dy)); path.addLine(to: CGPoint(x: c.x + dx, y: c.y + dy))
        }
        path.addEllipse(in: CGRect(x: c.x - 6, y: c.y - 6, width: 12, height: 12)); outlineLayer.path = path
    }
    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let pointer, touches.contains(pointer) else { return }
        cancelStroke()
    }
    func cancelFingerStroke() { if pointer?.type != .pencil { cancelStroke() } }
    func cancelStroke() {
        previewSnapshot = nil
        guard pointer != nil else { shadowPull = false; shadowTiles = [:]; placingMirrorCenter = false; placingVanishingPoint = 0; return }
        holdWork?.cancel(); previewLink?.invalidate(); previewLink = nil
        document.endStroke(cancelled: true)
        document.finishRecordingGesture(cancelled: true)
        roundStroke.reset(); graphite.begin()
        pointer = nil; fingerSmudge = false; previous = nil; shadowPull = false; shadowTiles = [:]
        refreshSelection()
        refresh()
        onCommit?()
    }
    override func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        guard gestureRecognizer === holdGesture else { return super.gestureRecognizerShouldBegin(gestureRecognizer) }
        return pointer?.type != .pencil && gestureRecognizer.numberOfTouches == 1 && !elasticLine && !liveFill && !shadowPull && mode != .colorPush
    }
    @objc private func pickColor(_ gesture: UILongPressGestureRecognizer) {
        guard pointer?.type != .pencil, gesture.state == .began || gesture.state == .changed else { return }
        if gesture.state == .began { cancelFingerStroke() }
        performFingerCommand(holdCommand, gesture)
    }
    func configureInput() {
        dragCommand = fingerDrawing ? FingerBinding.drag.command : .navigate
        holdCommand = FingerBinding.hold.command
        holdGesture.isEnabled = holdCommand != .disabled
        let ms = UserDefaults.standard.integer(forKey: "finger.holdDelay")
        holdGesture.minimumPressDuration = Double(ms == 0 ? 550 : ms) / 1000
        commandDrag.isEnabled = [.pick, .undo, .redo].contains(dragCommand)
        viewport?.panGestureRecognizer.minimumNumberOfTouches = dragCommand == .navigate ? 1 : 2
        accessibilityHint = dragCommand == .navigate
            ? "Drag to pan. Pinch to zoom. Draw with Apple Pencil."
            : "One-finger drag: " + dragCommand.rawValue + ". Use two fingers to pan and pinch to zoom."
    }
    @objc private func runDrag(_ gesture: UIPanGestureRecognizer) {
        performFingerCommand(dragCommand, gesture)
    }
    @objc private func tapHistory(_ gesture: UITapGestureRecognizer) {
        guard gesture.state == .ended, pointer?.type != .pencil else { return }
        cancelFingerStroke()
        let command = (gesture.numberOfTouchesRequired == 2 ? FingerBinding.two : FingerBinding.three).command
        if command == .undo || command == .redo { onFingerHistory?(command) }
    }
    private func performFingerCommand(_ command: FingerCommand, _ gesture: UIGestureRecognizer) {
        guard pointer?.type != .pencil else { return }
        if command == .undo || command == .redo {
            if gesture.state == .began { onFingerHistory?(command) }
            return
        }
        guard command == .pick, gesture.state == .began || gesture.state == .changed else { return }
        do {
            if let color = try document.color(at: gesture.location(in: self), mediaPreview: mediaBackdrop.image) {
                brushColor = color; onPickColor?(color)
            }
        } catch { onError?(error) }
    }
    private func paintFitted(_ points: [CGPoint]) throws {
        if strokeTool == .pencil && !strokeBrush.usesStamps {
            graphite.begin()
            for point in points {
                _ = try graphite.paint(document: document, brush: strokeBrush, point: point, size: strokeSize,
                    pressure: 0.5, tilt: 0, angle: 0, speed: 1, color: strokeColor, opacity: strokeOpacity)
            }
        } else if strokeBrush.usesSweptTip {
            roundStroke.reset()
            var prior: CGPoint?
            for point in points {
                _ = try roundStroke.paintTip(document: document, brush: strokeBrush, from: prior ?? point, to: point,
                    size: strokeSize, pressure: 0.5, tilt: 0, roll: 0, speed: 1, opacity: strokeOpacity,
                    color: strokeColor, erasing: strokeTool == .eraser)
                prior = point
            }
        } else if stampPath != nil, let stampRenderer {
            let path = strokeBrush.path(size: Float(strokeSize), opacity: Float(strokeOpacity))
            var stamps: [BrushStamp] = []
            for (i, point) in points.enumerated() {
                stamps += path.append(x: Float(point.x), y: Float(point.y), pressure: 0.5, tilt: 0, time: Double(i) / 120)
            }
            _ = try stampRenderer.paint(stamps, color: strokeColor, document: document, erasing: strokeTool == .eraser)
        } else {
            let path = CGMutablePath(); path.addLines(between: points)
            try document.paintPath(path, color: strokeColor.withAlphaComponent(strokeOpacity), width: strokeSize, filled: false, erasing: strokeTool == .eraser)
        }
    }
    private func fitQuickShape() {
        if elasticLine, let pointer {
            let shape = shapeTracker.elastic()
            let points = shape.points.map { CGPoint(x: CGFloat($0.x), y: CGFloat($0.y)) }
            let path = CGMutablePath(); path.addLines(between: points)
            let scale = viewport?.zoomScale ?? 1
            guard max(path.boundingBoxOfPath.width, path.boundingBoxOfPath.height) * scale >= 24 else { return }
            elasticShape = shape; bendAnchor = pointer.location(in: self); fitted = true
            previewLink = CADisplayLink(target: self, selector: #selector(renderLivePreview))
            previewLink?.add(to: .main, forMode: .common); previewPending = true; renderLivePreview(); return
        }
        guard pointer != nil, let shape = shapeTracker.fit() else { return }
        let points = shape.points.map { CGPoint(x: CGFloat($0.x), y: CGFloat($0.y)) }
        document.resetStrokePreview()
        do { try paintFitted(points); fitted = true; refresh(document.strokePreviewBounds) }
        catch { cancelStroke(); onError?(error) }
    }
    private func consume(_ touch: UITouch, event: UIEvent?) {
        do {
            var dirty = CGRect.null
            for sample in event?.coalescedTouches(for: touch) ?? [touch] {
                guard sample.timestamp > previousTimestamp else { continue }
                try advanceRecording(at: sample.timestamp, resumeStroke: true)
                var point = sample.location(in: self)
                if mode == .paint, let previous {
                    let response = 1 - CGFloat(strokeBrush.stabilization) * 0.85
                    point = CGPoint(x: previous.x + (point.x - previous.x) * response, y: previous.y + (point.y - previous.y) * response)
                }
                if let magneticRuler {
                    let projected = magneticRuler.project(x: Float(point.x), y: Float(point.y))
                    point = CGPoint(x: CGFloat(projected.x), y: CGFloat(projected.y))
                }
                // Do not bake estimated force into permanent pixels. USB-C Pencil and fingers use constant pressure.
                let measured = sample.type == .pencil && sample.maximumPossibleForce > 0 &&
                    !sample.estimatedProperties.contains(.force) && sample.force > 0
                let pressure = measured ? max(0.05, min(1, sample.force / sample.maximumPossibleForce)) : 0.5
                let tilt = sample.type == .pencil ? Float(.pi / 2 - sample.altitudeAngle) : 0
                let shading = strokeTool == .pencil ? CGFloat(strokeBrush.tiltResponse(radians: tilt)) : 0
                let response = CGFloat(strokeBrush.sizeResponse(pressure: Float(pressure)))
                let pencilScale = CGFloat(strokeBrush.pencilPointSize) * (1 + 9 * CGFloat(strokeBrush.pencilShadeSize) * shading) * 0.18
                let speed = previous.map { hypot(point.x - $0.x, point.y - $0.y) / max(0.001, sample.timestamp - previousTimestamp) } ?? 0
                let speedScale = 1 - CGFloat(strokeBrush.speedTaper) * min(0.85, speed / 2500)
                let radius = max(0.25, speedScale * strokeSize * 0.5 * (strokeTool == .eraser ? 1 : response) *
                    (strokeTool == .pencil ? pencilScale : 1))
                let alpha = strokeOpacity * (strokeTool == .eraser ? 1 : CGFloat(strokeBrush.opacityResponse(pressure: Float(pressure)))) *
                    (1 + (CGFloat(strokeBrush.pencilShadeOpacity) - 1) * shading)
                let color = (strokeTool == .eraser ? UIColor.black : strokeColor).withAlphaComponent(alpha)
                let roll: CGFloat
                if #available(iOS 17.5, *), sample.type == .pencil { roll = sample.rollAngle } else { roll = 0 }
                if mode == .paint && strokeBrush.usesSweptTip {
                    dirty = dirty.union(try roundStroke.paintTip(document: document, brush: strokeBrush,
                        from: previous ?? point, to: point, size: strokeSize, pressure: pressure, tilt: tilt,
                        roll: roll, speed: speedScale, opacity: strokeOpacity, color: strokeColor,
                        erasing: strokeTool == .eraser))
                } else if mode == .paint && strokeTool == .pencil && !strokeBrush.usesStamps {
                    dirty = dirty.union(try graphite.paint(document: document, brush: strokeBrush, point: point, size: strokeSize,
                        pressure: pressure, tilt: tilt, angle: sample.azimuthAngle(in: self), speed: speedScale,
                        color: strokeColor, opacity: strokeOpacity))
                } else if mode == .paint, let stampPath, let stampRenderer {
                    let stamps = stampPath.append(x: Float(point.x), y: Float(point.y), pressure: Float(pressure), tilt: tilt, time: sample.timestamp)
                    dirty = dirty.union(try stampRenderer.paint(stamps, color: strokeColor, document: document,
                        erasing: strokeTool == .eraser, roll: roll))
                } else if mode == .paint && strokeTool == .airbrush {
                    dirty = dirty.union(try roundStroke.paint(document: document, from: previous ?? point, to: point, radius: radius,
                        alpha: alpha, hardness: CGFloat(strokeBrush.hardness), color: strokeColor))
                } else if mode == .smudge { try document.smudge(from: previous ?? point, to: point, radius: strokeSize / 2, strength: strokeOpacity) }
                else if mode == .pixel || mode == .dither {
                    try document.paintPixelLine(from: previous ?? point, to: point, size: Int(strokeSize), color: strokeColor.withAlphaComponent(strokeOpacity), dither: mode == .dither, erasing: pixelErasing)
                } else {
                    dirty = dirty.union(try document.paint(from: previous ?? point, to: point, radius: radius,
                        color: color, erasing: strokeTool == .eraser,
                        softness: strokeTool == .airbrush ? 1 - CGFloat(strokeBrush.hardness) : 0))
                    
                }
                dirty = dirty.union(CGRect(x: min((previous ?? point).x, point.x), y: min((previous ?? point).y, point.y),
                    width: abs((previous ?? point).x - point.x), height: abs((previous ?? point).y - point.y)).insetBy(dx: -strokeSize, dy: -strokeSize))
                shapeTracker.append(x: Float(point.x), y: Float(point.y))
                previous = point; previousTimestamp = sample.timestamp
            }
            if !dirty.isNull { refresh(dirty) }
            holdWork?.cancel()
            if liveDrawing == nil && mode == .paint && magneticRuler == nil && (quickShapes || elasticLine) && (elasticLine || strokeTool != .eraser) {
                let work = DispatchWorkItem { [weak self] in self?.fitQuickShape() }
                holdWork = work; DispatchQueue.main.asyncAfter(deadline: .now() + 0.65, execute: work)
            }
        } catch {
            cancelStroke(); stopLiveDrawing(); onError?(error)
        }
    }
}

final class CanvasViewport: UIScrollView, UIScrollViewDelegate, UIGestureRecognizerDelegate {
    var onZoom: ((CGFloat) -> Void)?
    private let page = UIView()
    private var angle: CGFloat = 0
    private var turn: UIRotationGestureRecognizer!
    var rotationLocked: Bool {
        get { !turn.isEnabled }
        set { turn.isEnabled = !newValue }
    }
    let canvas: CanvasView
    private var fittedSize = CGSize.zero
    init(document: RasterDocument) {
        canvas = CanvasView(document: document)
        super.init(frame: .zero)
        delegate = self
        backgroundColor = .secondarySystemBackground
        contentSize = canvas.bounds.size
        canvas.viewport = self
        page.frame = canvas.bounds
        addSubview(page)
        page.addSubview(canvas)
        turn = UIRotationGestureRecognizer(target: self, action: #selector(rotatePage(_:)))
        turn.allowedTouchTypes = [NSNumber(value: UITouch.TouchType.direct.rawValue)]
        turn.delegate = self
        addGestureRecognizer(turn)
        rotationLocked = UserDefaults.standard.bool(forKey: "canvas.rotationLocked")
        contentInsetAdjustmentBehavior = .never
        showsHorizontalScrollIndicator = false
        showsVerticalScrollIndicator = false
        delaysContentTouches = false
        panGestureRecognizer.minimumNumberOfTouches = 2
        panGestureRecognizer.allowedTouchTypes = [NSNumber(value: UITouch.TouchType.direct.rawValue)]
        pinchGestureRecognizer?.allowedTouchTypes = [NSNumber(value: UITouch.TouchType.direct.rawValue)]
        panGestureRecognizer.addTarget(self, action: #selector(navigationStarted(_:)))
        pinchGestureRecognizer?.addTarget(self, action: #selector(navigationStarted(_:)))
    }
    required init?(coder: NSCoder) { fatalError("Use init(document:)") }
    func viewForZooming(in scrollView: UIScrollView) -> UIView? { page }
    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool {
        gestureRecognizer == turn && (other == panGestureRecognizer || other == pinchGestureRecognizer)
    }
    private func layoutPage() {
        let transform = CGAffineTransform(rotationAngle: angle)
        let box = canvas.bounds.applying(transform).standardized
        page.bounds = CGRect(origin: .zero, size: box.size)
        canvas.transform = transform
        canvas.center = CGPoint(x: box.width / 2, y: box.height / 2)
        contentSize = CGSize(width: box.width * zoomScale, height: box.height * zoomScale)
        layoutNavigationArea()
    }
    @objc private func rotatePage(_ gesture: UIRotationGestureRecognizer) {
        guard gesture.state == .began || gesture.state == .changed else { return }
        if gesture.state == .began { canvas.cancelFingerStroke() }
        let focus = gesture.location(in: self)
        let point = canvas.convert(focus, from: self)
        angle += gesture.rotation; gesture.rotation = 0
        layoutPage()
        let moved = canvas.convert(point, to: self)
        contentOffset = CGPoint(x: contentOffset.x + moved.x - focus.x, y: contentOffset.y + moved.y - focus.y)
        canvas.updateVisibleTiles()
    }
    override func layoutSubviews() {
        super.layoutSubviews()
        if bounds.size != fittedSize, bounds.width > 0, bounds.height > 0 {
            fittedSize = bounds.size
            fit()
        }
        layoutNavigationArea()
        canvas.updateVisibleTiles()
    }
    func fit() {
        canvas.cancelStroke()
        angle = 0; layoutPage()
        let scale = min(0.76 * bounds.width / canvas.bounds.width, 0.83 * bounds.height / canvas.bounds.height)
        maximumZoomScale = max(256, scale)
        minimumZoomScale = min(scale / 4, 1)
        setZoomScale(scale, animated: false)
        layoutNavigationArea()
        contentOffset = CGPoint(x: (contentSize.width - bounds.width) / 2,
                                y: (contentSize.height - bounds.height) / 2)
    }
    func scrollViewDidZoom(_ scrollView: UIScrollView) {
        layoutNavigationArea()
        canvas.updateVisibleTiles()
        onZoom?(zoomScale)
    }
    func scrollViewDidScroll(_ scrollView: UIScrollView) { canvas.updateVisibleTiles() }
    private func layoutNavigationArea() {
        page.center = CGPoint(x: contentSize.width / 2, y: contentSize.height / 2)
        // Allow any edge of the sheet to reach the viewport center, even at Fit.
        let margin = UIEdgeInsets(top: bounds.height / 2, left: bounds.width / 2,
                                  bottom: bounds.height / 2, right: bounds.width / 2)
        if contentInset != margin { contentInset = margin }
    }
    @objc private func navigationStarted(_ gesture: UIGestureRecognizer) {
        if gesture.state == .began { canvas.cancelFingerStroke() }
    }
}
