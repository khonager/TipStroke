import UIKit

final class GalleryArtworkPreview: UIView {
    let picture = UIImageView()
    var onExpand: (() -> Void)?
    private let close = UIButton(type: .system)
    private let anchor: CGRect
    private let initial: CGFloat
    private var scale: CGFloat
    private var offset = CGPoint.zero
    private var expanded = false

    init(frame: CGRect, image: UIImage, anchor: CGRect) {
        self.anchor = anchor
        let fit = min(frame.width / image.size.width, frame.height / image.size.height)
        initial = min(0.95, min(anchor.width / image.size.width, anchor.height / image.size.height) / fit)
        scale = initial
        super.init(frame: frame)
        autoresizingMask = [.flexibleWidth, .flexibleHeight]
        accessibilityViewIsModal = true
        picture.image = image
        picture.contentMode = .scaleAspectFit
        addSubview(picture)
        close.setTitle("Close", for: .normal)
        close.tintColor = .white
        close.isHidden = true
        close.addAction(UIAction { [weak self] _ in self?.removeFromSuperview() }, for: .touchUpInside)
        addSubview(close)
        addGestureRecognizer(UIPinchGestureRecognizer(target: self, action: #selector(pinch(_:))))
        addGestureRecognizer(UIPanGestureRecognizer(target: self, action: #selector(pan(_:))))
    }
    required init?(coder: NSCoder) { fatalError("Use init(frame:image:anchor:)") }
    override func layoutSubviews() {
        super.layoutSubviews()
        let weight = min(1, max(0, (1 - scale) / (1 - initial)))
        picture.bounds = CGRect(origin: .zero, size: bounds.size)
        picture.center = CGPoint(x: bounds.midX + (anchor.midX - bounds.midX) * weight + offset.x,
                                 y: bounds.midY + (anchor.midY - bounds.midY) * weight + offset.y)
        picture.transform = CGAffineTransform(scaleX: scale, y: scale)
        backgroundColor = UIColor.black.withAlphaComponent(min(1, max(0.4, scale)))
        close.frame = CGRect(x: bounds.maxX - safeAreaInsets.right - 72, y: safeAreaInsets.top, width: 68, height: 48)
    }
    @objc func pinch(_ gesture: UIPinchGestureRecognizer) {
        switch gesture.state {
        case .began, .changed:
            scale = min(8, max(0.05, scale * gesture.scale))
            gesture.scale = 1
            if scale <= 1 { offset = .zero }
            setNeedsLayout()
        case .ended, .cancelled, .failed:
            if scale >= 0.5 && (expanded || scale > initial) && (expanded || gesture.state == .ended) {
                if !expanded { onExpand?(); onExpand = nil }
                expanded = true
                close.isHidden = false
                scale = max(1, scale)
                UIView.animate(withDuration: 0.17) { self.setNeedsLayout(); self.layoutIfNeeded() }
            } else {
                scale = initial
                UIView.animate(withDuration: 0.15, animations: { self.setNeedsLayout(); self.layoutIfNeeded(); self.alpha = 0 },
                               completion: { _ in self.removeFromSuperview() })
            }
        default: break
        }
    }
    @objc private func pan(_ gesture: UIPanGestureRecognizer) {
        guard expanded, scale > 1 else { return }
        let delta = gesture.translation(in: self)
        offset.x = min(bounds.width * (scale - 1) / 2, max(-bounds.width * (scale - 1) / 2, offset.x + delta.x))
        offset.y = min(bounds.height * (scale - 1) / 2, max(-bounds.height * (scale - 1) / 2, offset.y + delta.y))
        gesture.setTranslation(.zero, in: self)
        setNeedsLayout()
    }
    override func accessibilityPerformEscape() -> Bool { removeFromSuperview(); return true }
}
