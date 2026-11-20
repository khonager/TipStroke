import UIKit

struct PhotoMotionSample {
    let time: TimeInterval
    let translation: CGPoint
    let scale: CGFloat
    let angle: CGFloat

    var matrix: CGAffineTransform {
        CGAffineTransform(translationX: translation.x, y: translation.y).rotated(by: angle).scaledBy(x: scale, y: scale)
    }
    func toward(_ b: Self, fraction f: CGFloat) -> Self {
        Self(time: time + (b.time-time) * Double(f), translation: CGPoint(x: translation.x + (b.translation.x-translation.x)*f,
            y: translation.y + (b.translation.y-translation.y)*f), scale: scale + (b.scale-scale)*f, angle: angle + (b.angle-angle)*f)
    }
}

extension RasterDocument {
    func recordPhotoMotion(_ samples: [PhotoMotionSample], through endIndex: Int, realTime: Bool) throws {
        guard var a = record.animation, let photo = activePhoto, let first = samples.first, let last = samples.last,
              let layer = record.layers.first(where: { $0.id == activeLayer }), !layer.locked, layer.visible,
              a.frames.indices.contains(endIndex), endIndex > a.selectedIndex else {
            throw DrawingError.message("Select an unlocked photo and at least two frames.")
        }
        let start = a.selectedIndex, span = last.time - first.time
        var j = 0
        a.backgroundLayers?.remove(activeLayer)
        for i in start...endIndex {
            let time = first.time + (realTime ? Double(i - start) / Double(a.fps) : span * Double(i-start) / Double(endIndex-start))
            while j + 1 < samples.count && samples[j+1].time <= time { j += 1 }
            let next = samples[min(j+1, samples.count-1)]
            let fraction = max(0, min(1, (time-samples[j].time)/max(.ulpOfOne, next.time-samples[j].time)))
            var moved = photo
            moved.transform = photo.transform.concatenating(samples[j].toward(next, fraction: CGFloat(fraction)).matrix)
            try moved.validate()
            var cels = a.frames[i].photoCels ?? [:]
            cels[activeLayer.uuidString] = PhotoCelRecord(photo: moved); a.frames[i].photoCels = cels
        }
        beginMetadataEdit()
        record.animation = a; installPhotoCels(a); endMetadataEdit()
    }
}

/// A transient native overlay captures a continuous drag/pinch/twist without altering saved cels.
private final class PhotoMotionView: UIView {
    weak var editor: EditorController?
    let photo: PhotoLayerRecord
    let end: Int
    let realTime: Bool
    var points: [UITouch: CGPoint] = [:]
    var touchesInOrder: [UITouch] = []
    var base = CGAffineTransform.identity
    var current = CGAffineTransform.identity
    var samples: [PhotoMotionSample] = []

    init(_ editor: EditorController, photo: PhotoLayerRecord, end: Int, realTime: Bool) {
        self.editor = editor; self.photo = photo; self.end = end; self.realTime = realTime
        super.init(frame: editor.view.bounds)
        autoresizingMask = [.flexibleWidth, .flexibleHeight]; isMultipleTouchEnabled = true
        let cancel = UIButton(type: .system, primaryAction: UIAction(title: "Drag, pinch or twist the photo · Cancel") { [weak self] _ in self?.close() })
        cancel.configuration = .tinted(); cancel.translatesAutoresizingMaskIntoConstraints = false; addSubview(cancel)
        NSLayoutConstraint.activate([cancel.topAnchor.constraint(equalTo: safeAreaLayoutGuide.topAnchor, constant: 6),
            cancel.centerXAnchor.constraint(equalTo: safeAreaLayoutGuide.centerXAnchor)])
        NotificationCenter.default.addObserver(self, selector: #selector(close), name: UIApplication.willResignActiveNotification, object: nil)
    }
    required init?(coder: NSCoder) { fatalError("Use init(editor:photo:end:realTime:)") }
    deinit { NotificationCenter.default.removeObserver(self) }
    @objc private func close() {
        editor?.viewport.canvas.previewSnapshot = nil
        editor?.viewport.canvas.refresh()
        removeFromSuperview()
    }
    private func resetBaseline() {
        base = current
        for touch in touchesInOrder { points[touch] = touch.location(in: editor?.viewport.canvas) }
    }
    private func capture(_ time: TimeInterval) {
        guard let editor, let a = touchesInOrder.first, let old = points[a] else { return }
        let p = a.location(in: editor.viewport.canvas)
        var delta = CGAffineTransform(translationX: p.x-old.x, y: p.y-old.y)
        if touchesInOrder.count > 1, let second = points[touchesInOrder[1]] {
            let q = touchesInOrder[1].location(in: editor.viewport.canvas)
            let oldAngle = atan2(second.y-old.y,second.x-old.x), newAngle = atan2(q.y-p.y,q.x-p.x)
            let scale = min(20,max(0.05,hypot(q.x-p.x,q.y-p.y)/max(1,hypot(second.x-old.x,second.y-old.y))))
            delta = CGAffineTransform(translationX: (p.x+q.x)/2, y: (p.y+q.y)/2)
                .rotated(by: newAngle-oldAngle).scaledBy(x: scale,y: scale).translatedBy(x: -(old.x+second.x)/2,y: -(old.y+second.y)/2)
        }
        current = base.concatenating(delta)
        let scale = hypot(current.a, current.b)
        guard scale.isFinite, (0.01...100).contains(scale) else { current = base; return }
        var angle = atan2(current.b,current.a)
        if let previous = samples.last?.angle {
            angle += round((previous-angle)/(2*CGFloat.pi))*2*CGFloat.pi
        }
        if samples.count >= 2048 {
            samples = samples.enumerated().filter { $0.offset % 2 == 0 }.map(\.element)
        }
        let sample = PhotoMotionSample(time: time, translation: CGPoint(x: current.tx,y: current.ty), scale: scale, angle: angle)
        if samples.last?.time == time { samples.removeLast() }
        samples.append(sample)
        var snapshot = DocumentSnapshot(record: editor.document.record,pixels: editor.document.pixels)
        if let i = snapshot.record.layers.firstIndex(where: { $0.id == editor.document.activeLayer }) {
            snapshot.record.layers[i].photo?.transform = photo.transform.concatenating(current)
            snapshot.record.layers[i].photoCelActive = true
            editor.viewport.canvas.previewSnapshot = snapshot
            editor.viewport.canvas.refresh()
        }
    }
    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        for touch in touches where !touchesInOrder.contains(touch) { touchesInOrder.append(touch) }
        resetBaseline(); capture(event?.timestamp ?? ProcessInfo.processInfo.systemUptime)
    }
    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) { capture(event?.timestamp ?? ProcessInfo.processInfo.systemUptime) }
    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) { close() }
    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        capture(event?.timestamp ?? ProcessInfo.processInfo.systemUptime)
        touchesInOrder.removeAll { touches.contains($0) }; for touch in touches { points[touch] = nil }
        if touchesInOrder.isEmpty {
            defer { close() }
            do { try editor?.document.recordPhotoMotion(samples, through: end, realTime: realTime); editor?.changed() }
            catch { editor?.showError(error) }
        } else { resetBaseline() }
    }
}

extension EditorController {
    func configurePhotoMotion() {
        guard let a = document.record.animation, let photo = document.activePhoto,
              a.selectedIndex+1 < a.frames.count else { showError(DrawingError.message("Select a photo and a starting frame with another frame after it.")); return }
        let alert = UIAlertController(title: "Record photo motion", message: "Last frame in the recording range", preferredStyle: .alert)
        alert.addTextField { $0.text = String(a.frames.count); $0.keyboardType = .numberPad }
        for (name, real) in [("Fit gesture to range", false), ("Real time", true)] {
            alert.addAction(UIAlertAction(title: name, style: .default) { [weak self, weak alert] _ in
                guard let self, let end = Int(alert?.textFields?.first?.text ?? ""), (a.selectedIndex+2...a.frames.count).contains(end) else { return }
                self.viewport.canvas.cancelStroke()
                self.view.addSubview(PhotoMotionView(self, photo: photo, end: end-1, realTime: real))
            })
        }
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel)); present(alert, animated: true)
    }
}
