import UIKit
import TipStrokeShared

final class CanvasView: UIView, UIGestureRecognizerDelegate {
    enum Tool: String, CaseIterable {
        case pencil = "Pencil", ink = "Ink", airbrush = "Airbrush", eraser = "Eraser"
        var brushID: String {
            switch self { case .pencil: return "pencil-v1"; case .airbrush: return "airbrush-v1"; default: return "ink-v1" }
        }
    }
    let document: RasterDocument
    var mode: CanvasMode = .paint { didSet { cancelStroke() } }
    var filledShapes = false
    var quickShapes = true
    var onFill: ((CGPoint) -> Void)?
    private let outlineLayer = CAShapeLayer()
    private var gesturePoints: [CGPoint] = []
    private var shapeTracker = NativeShapeTracker()
    private var holdWork: DispatchWorkItem?
    private var fitted = false
    var tool: Tool = .ink
    var brush = NativeBrush.companion.builtIn(id: "ink-v1") {
        didSet {
            do { stampRenderer = brush.usesStamps ? try NativeStampRenderer(brush: brush) : nil }
            catch { stampRenderer = nil; onError?(error) }
        }
    }
    private var stampRenderer: NativeStampRenderer?
    private var stampPath: NativeStampPath?
    var brushColor: UIColor = .black
    var brushSize: CGFloat = 8
    var brushOpacity: CGFloat = 1
    var fingerDrawing = true
    var onCommit: (() -> Void)?
    var onPickColor: ((UIColor) -> Void)?
    var onError: ((Error) -> Void)?
    var isDrawing: Bool { pointer != nil }
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
        layer.addSublayer(outlineLayer)
        isMultipleTouchEnabled = true
        isOpaque = true
        clipsToBounds = true
        accessibilityLabel = "Drawing canvas"
        let picker = UILongPressGestureRecognizer(target: self, action: #selector(pickColor(_:)))
        picker.minimumPressDuration = 0.55
        picker.cancelsTouchesInView = false
        picker.delegate = self
        picker.allowedTouchTypes = [NSNumber(value: UITouch.TouchType.direct.rawValue)]
        addGestureRecognizer(picker)
        accessibilityHint = "Draw with one finger or Apple Pencil. Use two fingers to pan and pinch to zoom."
    }
    required init?(coder: NSCoder) { fatalError("Use init(document:)") }
    // One native layer per visible tile; never allocate a document-sized UIView backing bitmap.
    func refresh(_ dirty: CGRect? = nil) {
        backgroundColor = document.record.paperColor
        isOpaque = (document.record.backgroundOpacity ?? 1) == 1
        let visible = superview.map { convert($0.bounds, from: $0) } ?? bounds
        let keys = Set(TileKey.intersecting(visible, width: document.record.width, height: document.record.height))
        CATransaction.begin(); CATransaction.setDisableActions(true)
        defer { CATransaction.commit() }
        for key in Array(displayTiles.keys) where !keys.contains(key) {
            displayTiles.removeValue(forKey: key)?.removeFromSuperlayer()
        }
        let snapshot = DocumentSnapshot(record: document.record, pixels: document.pixels)
        for key in keys {
            let existed = displayTiles[key] != nil
            if existed, let dirty, !dirty.intersects(key.rect) { continue }
            let onion = document.record.animation?.onionSkin == true
            guard onion || document.record.layers.contains(where: { $0.visible && document.pixels[$0.id]?[key] != nil }) else {
                displayTiles.removeValue(forKey: key)?.removeFromSuperlayer(); continue
            }
            do {
                let context = try RasterDocument.context(width: 256, height: 256)
                context.translateBy(x: -key.rect.minX, y: -key.rect.minY)
                if let animation = document.record.animation, animation.onionSkin {
                    for index in [animation.selectedIndex - 1, animation.selectedIndex + 1] where animation.frames.indices.contains(index) {
                        var ghost = snapshot
                        ghost.pixels = document.framePixels[animation.frames[index].id] ?? [:]
                        context.saveGState(); context.setAlpha(0.18); context.beginTransparencyLayer(auxiliaryInfo: nil)
                        try RasterDocument.composite(ghost, in: context, visible: key.rect)
                        context.endTransparencyLayer(); context.restoreGState()
                    }
                }
                try RasterDocument.composite(snapshot, in: context, visible: key.rect)
                guard let image = context.makeImage() else { throw DrawingError.message("Could not display this tile.") }
                let tile = displayTiles[key] ?? CALayer()
                tile.frame = key.rect; tile.contents = image; tile.contentsScale = 1
                tile.magnificationFilter = .nearest
                if !existed { layer.addSublayer(tile); displayTiles[key] = tile }
            } catch { onError?(error); return }
        }
    }
    func updateVisibleTiles() { refresh(.null) }
    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        let all = event?.allTouches?.filter { $0.phase != .ended && $0.phase != .cancelled } ?? []
        if let pointer, pointer.type == .pencil { return }
        if let pencil = touches.first(where: { $0.type == .pencil }) {
            cancelStroke()
            start(pencil, event: event)
        } else if all.filter({ $0.type == .direct }).count > 1 {
            cancelStroke()
        } else if pointer == nil, fingerDrawing, let touch = touches.first(where: { $0.type == .direct }) {
            start(touch, event: event)
        }
    }
    private func start(_ touch: UITouch, event: UIEvent?) {
        if mode == .paint && brush.usesStamps && stampRenderer == nil { onError?(DrawingError.message("The brush texture could not be prepared.")); return }

        if mode.streamsPaint && !document.beginStroke() { return }
        pointer = touch; previous = nil; previousTimestamp = -1
        gesturePoints = [touch.location(in: self)]; shapeTracker = NativeShapeTracker(); fitted = false
        stampPath = brush.usesStamps ? brush.path(size: Float(brushSize), opacity: Float(brushOpacity)) : nil
        strokeBrush = brush
        strokeTool = tool; strokeColor = brushColor; strokeSize = brushSize; strokeOpacity = brushOpacity
        if mode.streamsPaint { consume(touch, event: event) }
    }
    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let pointer, touches.contains(pointer) else { return }
        if mode.streamsPaint { if !fitted { consume(pointer, event: event) }; return }
        let point = pointer.location(in: self)
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
        if mode.streamsPaint {
            if !fitted { consume(pointer, event: event) }
            guard self.pointer != nil else { return }
            document.endStroke(cancelled: false)
        } else {
            do {
                if mode == .lasso { gesturePoints.append(point) } else { gesturePoints = [gesturePoints[0], point] }
                if mode.isSelection { document.selection = CanvasSelection(path: mode.outline(gesturePoints)) }
                else if mode.isShape, document.beginStroke() {
                    if mode == .pixelLine { try document.paintPixelLine(from: gesturePoints[0], to: point, size: Int(brushSize), color: brushColor.withAlphaComponent(brushOpacity), dither: false) }
                    else { try document.paintPath(mode.outline(gesturePoints), color: brushColor.withAlphaComponent(brushOpacity), width: brushSize, filled: filledShapes && [.rectangle, .ellipse, .triangle].contains(mode)) }
                    document.endStroke(cancelled: false)
                } else if mode == .transform {
                    try document.transformSelection(CGAffineTransform(translationX: point.x - gesturePoints[0].x, y: point.y - gesturePoints[0].y))
                }
            } catch { document.endStroke(cancelled: true); onError?(error) }
        }
        self.pointer = nil; previous = nil
        refresh(); refreshSelection(); onCommit?()
        if mode == .fill { onFill?(point) }
    }
    func refreshSelection() { outlineLayer.path = document.selection?.path }
    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let pointer, touches.contains(pointer) else { return }
        cancelStroke()
    }
    func cancelFingerStroke() { if pointer?.type != .pencil { cancelStroke() } }
    func cancelStroke() {
        guard pointer != nil else { return }
        holdWork?.cancel()
        document.endStroke(cancelled: true)
        pointer = nil; previous = nil
        refreshSelection()
        refresh()
        onCommit?()
    }
    func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        pointer?.type != .pencil && gestureRecognizer.numberOfTouches == 1
    }
    @objc private func pickColor(_ gesture: UILongPressGestureRecognizer) {
        guard pointer?.type != .pencil, gesture.state == .began || gesture.state == .changed else { return }
        cancelStroke()
        do {
            if let color = try document.color(at: gesture.location(in: self)) {
                brushColor = color; onPickColor?(color)
            }
        } catch { onError?(error) }
    }
    private func fitQuickShape() {
        guard pointer != nil, let shape = shapeTracker.fit() else { return }
        let points = shape.points.map { CGPoint(x: CGFloat($0.x), y: CGFloat($0.y)) }
        let path = CGMutablePath(); path.addLines(between: points)
        document.endStroke(cancelled: true)
        guard document.beginStroke() else { return }
        do { try document.paintPath(path, color: strokeColor.withAlphaComponent(strokeOpacity), width: strokeSize, filled: false); fitted = true; refresh() }
        catch { cancelStroke(); onError?(error) }
    }
    private func consume(_ touch: UITouch, event: UIEvent?) {
        do {
            var dirty = CGRect.null
            for sample in event?.coalescedTouches(for: touch) ?? [touch] {
                guard sample.timestamp > previousTimestamp else { continue }
                var point = sample.location(in: self)
                if mode == .paint, let previous {
                    let response = 1 - CGFloat(strokeBrush.stabilization) * 0.85
                    point = CGPoint(x: previous.x + (point.x - previous.x) * response, y: previous.y + (point.y - previous.y) * response)
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
                if mode == .paint, let stampPath, let stampRenderer {
                    let stamps = stampPath.append(x: Float(point.x), y: Float(point.y), pressure: Float(pressure), tilt: tilt, time: sample.timestamp)
                    dirty = dirty.union(try stampRenderer.paint(stamps, color: strokeColor, document: document, erasing: strokeTool == .eraser))
                } else if mode == .smudge { try document.smudge(from: previous ?? point, to: point, radius: strokeSize / 2, strength: strokeOpacity) }
                else if mode == .pixel || mode == .dither {
                    try document.paintPixelLine(from: previous ?? point, to: point, size: Int(strokeSize), color: strokeColor.withAlphaComponent(strokeOpacity), dither: mode == .dither)
                } else {
                    dirty = dirty.union(try document.paint(from: previous ?? point, to: point, radius: radius,
                        color: color, erasing: strokeTool == .eraser,
                        softness: strokeTool == .airbrush ? 1 - CGFloat(strokeBrush.hardness) : 0))
                    shapeTracker.append(x: Float(point.x), y: Float(point.y))
                }
                dirty = dirty.union(CGRect(x: min((previous ?? point).x, point.x), y: min((previous ?? point).y, point.y),
                    width: abs((previous ?? point).x - point.x), height: abs((previous ?? point).y - point.y)).insetBy(dx: -strokeSize, dy: -strokeSize))
                previous = point; previousTimestamp = sample.timestamp
            }
            if !dirty.isNull { refresh(dirty) }
            holdWork?.cancel()
            if mode == .paint && quickShapes && strokeTool != .eraser {
                let work = DispatchWorkItem { [weak self] in self?.fitQuickShape() }
                holdWork = work; DispatchQueue.main.asyncAfter(deadline: .now() + 0.6, execute: work)
            }
        } catch {
            cancelStroke(); onError?(error)
        }
    }
}

final class CanvasViewport: UIScrollView, UIScrollViewDelegate {
    let canvas: CanvasView
    private var fittedSize = CGSize.zero
    init(document: RasterDocument) {
        canvas = CanvasView(document: document)
        super.init(frame: .zero)
        delegate = self
        backgroundColor = .secondarySystemBackground
        contentSize = canvas.bounds.size
        addSubview(canvas)
        delaysContentTouches = false
        panGestureRecognizer.minimumNumberOfTouches = 2
        panGestureRecognizer.allowedTouchTypes = [NSNumber(value: UITouch.TouchType.direct.rawValue)]
        pinchGestureRecognizer?.allowedTouchTypes = [NSNumber(value: UITouch.TouchType.direct.rawValue)]
        panGestureRecognizer.addTarget(self, action: #selector(navigationStarted(_:)))
        pinchGestureRecognizer?.addTarget(self, action: #selector(navigationStarted(_:)))
    }
    required init?(coder: NSCoder) { fatalError("Use init(document:)") }
    func viewForZooming(in scrollView: UIScrollView) -> UIView? { canvas }
    override func layoutSubviews() {
        super.layoutSubviews()
        if bounds.size != fittedSize, bounds.width > 0, bounds.height > 0 {
            fittedSize = bounds.size
            fit()
        }
        centerCanvas()
        canvas.updateVisibleTiles()
    }
    func fit() {
        canvas.cancelStroke()
        let scale = min(bounds.width / canvas.bounds.width, bounds.height / canvas.bounds.height) * 0.95
        minimumZoomScale = min(scale, 1)
        maximumZoomScale = max(8, scale)
        setZoomScale(minimumZoomScale, animated: false)
        contentOffset = .zero
        centerCanvas()
    }
    func scrollViewDidZoom(_ scrollView: UIScrollView) { centerCanvas(); canvas.updateVisibleTiles() }
    func scrollViewDidScroll(_ scrollView: UIScrollView) { canvas.updateVisibleTiles() }
    private func centerCanvas() {
        canvas.center = CGPoint(x: max(contentSize.width, bounds.width) / 2, y: max(contentSize.height, bounds.height) / 2)
    }
    @objc private func navigationStarted(_ gesture: UIGestureRecognizer) {
        if gesture.state == .began { canvas.cancelFingerStroke() }
    }
}
