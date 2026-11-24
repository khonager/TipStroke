import UIKit

/// Lives above the viewport. Only the thumbnail itself participates in hit testing.
final class CanvasNavigator: UIImageView {
    private var preferredWidth: CGFloat = 140
    private var tuckedRight: Bool?
    private weak var viewport: CanvasViewport?
    private var rendering = false
    private var pending = false
    private let handle = UIImageView(image: UIImage(systemName: "chevron.compact.right"))

    init(viewport: CanvasViewport) {
        self.viewport = viewport
        super.init(frame: .zero)
        contentMode = .scaleAspectFit
        backgroundColor = .secondarySystemBackground
        layer.cornerRadius = 8
        layer.borderWidth = 1
        layer.borderColor = UIColor.systemGray.cgColor
        clipsToBounds = true
        isUserInteractionEnabled = true
        isAccessibilityElement = true
        accessibilityLabel = "Canvas preview"
        accessibilityHint = "Drag to move, pinch to resize, or drag to the edge to tuck away."
        handle.contentMode = .center
        handle.backgroundColor = .secondarySystemBackground
        handle.isHidden = true
        addSubview(handle)
        addGestureRecognizer(UIPanGestureRecognizer(target: self, action: #selector(move(_:))))
        addGestureRecognizer(UIPinchGestureRecognizer(target: self, action: #selector(resize(_:))))
        addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(expand)))
    }
    required init?(coder: NSCoder) { fatalError("Use init(viewport:)") }

    func constrainPosition() {
        guard let viewport, let host = superview else { return }
        let area = viewport.convert(viewport.bounds, to: host).intersection(host.safeAreaLayoutGuide.layoutFrame)
        guard !area.isEmpty else { return }
        let sheet = viewport.canvas.bounds.size
        let aspect = max(0.4, min(2.5, sheet.width / sheet.height))
        let width = min(preferredWidth, area.width, area.height * aspect)
        let start = frame.isEmpty ? CGPoint(x: area.minX + 8, y: area.minY + 8) : frame.origin
        let size = tuckedRight == nil ? CGSize(width: width, height: width / aspect) : CGSize(width: 30, height: 48)
        var x = min(max(start.x, area.minX), area.maxX - size.width)
        if let tuckedRight { x = tuckedRight ? area.maxX - size.width : area.minX }
        frame = CGRect(x: x, y: min(max(start.y, area.minY), area.maxY - size.height), width: size.width, height: size.height)
        handle.frame = bounds
        handle.isHidden = tuckedRight == nil
        handle.transform = tuckedRight == true ? CGAffineTransform(rotationAngle: .pi) : .identity
    }

    @objc private func expand() { tuckedRight = nil; constrainPosition() }
    override func accessibilityActivate() -> Bool { expand(); return true }
    @objc private func move(_ pan: UIPanGestureRecognizer) {
        guard let host = superview else { return }
        let delta = pan.translation(in: host)
        if pan.state == .changed {
            tuckedRight = nil
            center.x += delta.x
            center.y += delta.y
            pan.setTranslation(.zero, in: host)
        } else if pan.state == .ended {
            let x = pan.location(in: host).x
            let safe = host.safeAreaLayoutGuide.layoutFrame
            if x <= safe.minX + 16 { tuckedRight = false }
            if x >= safe.maxX - 16 { tuckedRight = true }
        }
        constrainPosition()
    }
    @objc private func resize(_ pinch: UIPinchGestureRecognizer) {
        preferredWidth = min(420, max(70, preferredWidth * pinch.scale))
        pinch.scale = 1
        expand()
    }
    func refresh() {
        guard let viewport, superview != nil else { return }
        if rendering { pending = true; return }
        rendering = true
        let snapshot = viewport.canvas.document.snapshot()
        ProjectStore.shared.queue.async { [weak self] in
            let thumb = try? ProjectStore.thumbnail(snapshot)
            DispatchQueue.main.async {
                guard let self else { return }
                self.rendering = false
                if let thumb { self.image = UIImage(cgImage: thumb) }
                if self.pending { self.pending = false; self.refresh() }
            }
        }
    }
}
