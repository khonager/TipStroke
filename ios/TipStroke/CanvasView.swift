import UIKit

final class CanvasView: UIView {
    enum Tool: String, CaseIterable { case pencil = "Pencil", ink = "Ink", eraser = "Eraser" }
    let document: RasterDocument
    var tool: Tool = .ink
    var brushColor: UIColor = .black
    var brushSize: CGFloat = 8
    var brushOpacity: CGFloat = 1
    var fingerDrawing = true
    var onCommit: (() -> Void)?
    var onError: ((Error) -> Void)?
    var isDrawing: Bool { pointer != nil }
    private var displayTiles: [TileKey: CALayer] = [:]
    private var pointer: UITouch?
    private var previous: CGPoint?
    private var previousTimestamp: TimeInterval = -1
    private var strokeTool: Tool = .ink
    private var strokeColor = UIColor.black
    private var strokeSize: CGFloat = 8
    private var strokeOpacity: CGFloat = 1

    init(document: RasterDocument) {
        self.document = document
        super.init(frame: document.bounds)
        backgroundColor = .white
        isMultipleTouchEnabled = true
        isOpaque = true
        clipsToBounds = true
        accessibilityLabel = "Drawing canvas"
        accessibilityHint = "Draw with one finger or Apple Pencil. Use two fingers to pan and pinch to zoom."
    }
    required init?(coder: NSCoder) { fatalError("Use init(document:)") }
    // One native layer per visible tile; never allocate a document-sized UIView backing bitmap.
    func refresh(_ dirty: CGRect? = nil) {
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
            guard document.record.layers.contains(where: { $0.visible && document.pixels[$0.id]?[key] != nil }) else {
                displayTiles.removeValue(forKey: key)?.removeFromSuperlayer(); continue
            }
            do {
                let context = try RasterDocument.context(width: 256, height: 256)
                context.translateBy(x: -key.rect.minX, y: -key.rect.minY)
                RasterDocument.composite(snapshot, in: context, visible: key.rect)
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
        guard document.beginStroke() else { return }
        pointer = touch; previous = nil; previousTimestamp = -1
        strokeTool = tool; strokeColor = brushColor; strokeSize = brushSize; strokeOpacity = brushOpacity
        consume(touch, event: event)
    }
    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let pointer, touches.contains(pointer) else { return }
        consume(pointer, event: event)
    }
    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let pointer, touches.contains(pointer) else { return }
        consume(pointer, event: event)
        guard self.pointer != nil else { return }
        document.endStroke(cancelled: false)
        self.pointer = nil; previous = nil
        onCommit?()
    }
    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let pointer, touches.contains(pointer) else { return }
        cancelStroke()
    }
    func cancelFingerStroke() { if pointer?.type != .pencil { cancelStroke() } }
    func cancelStroke() {
        guard pointer != nil else { return }
        document.endStroke(cancelled: true)
        pointer = nil; previous = nil
        refresh()
        onCommit?()
    }
    private func consume(_ touch: UITouch, event: UIEvent?) {
        do {
            var dirty = CGRect.null
            for sample in event?.coalescedTouches(for: touch) ?? [touch] {
                guard sample.timestamp > previousTimestamp else { continue }
                let point = sample.location(in: self)
                // Do not bake estimated force into permanent pixels. USB-C Pencil and fingers use constant pressure.
                let measured = sample.type == .pencil && sample.maximumPossibleForce > 0 &&
                    !sample.estimatedProperties.contains(.force) && sample.force > 0
                let pressure = measured ? max(0.05, min(1, sample.force / sample.maximumPossibleForce)) : 0.5
                let radius = max(0.25, strokeSize * (strokeTool == .eraser ? 0.5 : 0.15 + pressure * 0.7))
                let alpha = strokeOpacity * (strokeTool == .pencil ? 0.65 : 1)
                let color = (strokeTool == .eraser ? UIColor.black : strokeColor).withAlphaComponent(alpha)
                dirty = dirty.union(try document.paint(from: previous ?? point, to: point, radius: radius,
                                                       color: color, erasing: strokeTool == .eraser))
                previous = point; previousTimestamp = sample.timestamp
            }
            if !dirty.isNull { refresh(dirty) }
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
