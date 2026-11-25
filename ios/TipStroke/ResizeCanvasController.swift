import UIKit

final class ResizeCanvasController: UIViewController {
    let snapshot: DocumentSnapshot
    var apply: ((CGRect) -> Void)?
    private let artwork = UIImageView()
    private let border = UIView()
    private let shade = CAShapeLayer()
    private var handles: [UIView] = []
    private let anchors = [(0,0),(1,0),(2,0),(0,1),(2,1),(0,2),(1,2),(2,2)]
    private let original: CGRect
    private var crop: CGRect
    private var start = CGRect.zero
    private var scale: CGFloat = 1
    private var origin = CGPoint.zero
    private var dragging = false

    init(snapshot: DocumentSnapshot) {
        self.snapshot = snapshot
        original = CGRect(x: 0, y: 0, width: snapshot.record.width, height: snapshot.record.height)
        crop = original
        super.init(nibName: nil, bundle: nil)
    }
    required init?(coder: NSCoder) { fatalError() }
    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemGray3
        border.backgroundColor = snapshot.record.paperColor
        view.addSubview(border)
        view.addSubview(artwork)
        shade.fillRule = .evenOdd
        shade.fillColor = UIColor.black.withAlphaComponent(0.55).cgColor
        view.layer.addSublayer(shade)
        for (i, anchor) in anchors.enumerated() {
            let handle = UIView()
            handle.tag = i
            let dot = UIView(frame: CGRect(x: 14, y: 14, width: 16, height: 16))
            dot.layer.cornerRadius = 8; dot.backgroundColor = .systemOrange
            handle.addSubview(dot)
            handle.accessibilityLabel = ["Left", "Center", "Right"][anchor.0] + " " + ["top","middle","bottom"][anchor.1]
            handle.addGestureRecognizer(UIPanGestureRecognizer(target: self, action: #selector(drag(_:))))
            handles.append(handle); view.addSubview(handle)
        }
        navigationItem.leftBarButtonItem = UIBarButtonItem(systemItem: .cancel, primaryAction: UIAction { [weak self] _ in self?.dismiss(animated: true) })
        navigationItem.rightBarButtonItems = [
            UIBarButtonItem(title: "Apply", primaryAction: UIAction { [weak self] _ in
                guard let self else { return }
                let action = self.apply, rect = self.crop
                self.dismiss(animated: true) { action?(rect) }
            }),
            UIBarButtonItem(title: "Reset", primaryAction: UIAction { [weak self] _ in
                guard let self else { return }; self.crop = self.original; self.view.setNeedsLayout()
            })
        ]
        let snapshot = self.snapshot
        ProjectStore.shared.queue.async { [weak self] in
            let image = try? ProjectStore.thumbnail(snapshot)
            DispatchQueue.main.async { if let image { self?.artwork.image = UIImage(cgImage: image) } }
        }
    }
    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        if !dragging {
            let area = view.safeAreaLayoutGuide.layoutFrame.insetBy(dx: 32, dy: 32)
            let box = original.union(crop)
            scale = max(0.001, min(area.width / box.width, area.height / box.height) * 0.8)
            origin = CGPoint(x: area.midX - box.midX * scale, y: area.midY - box.midY * scale)
        }
        let pose = CGAffineTransform(translationX: origin.x, y: origin.y).scaledBy(x: scale, y: scale)
        artwork.frame = original.applying(pose)
        let frame = crop.applying(pose)
        border.frame = frame
        let path = UIBezierPath(rect: artwork.frame)
        let kept = artwork.frame.intersection(frame)
        if !kept.isNull { path.append(UIBezierPath(rect: kept)) }
        CATransaction.begin(); CATransaction.setDisableActions(true); shade.path = path.cgPath; CATransaction.commit()
        for (i, anchor) in anchors.enumerated() {
            handles[i].bounds.size = CGSize(width: 44, height: 44)
            handles[i].center = CGPoint(x: frame.minX + CGFloat(anchor.0) * frame.width / 2, y: frame.minY + CGFloat(anchor.1) * frame.height / 2)
        }
        title = "\(Int(crop.width)) × \(Int(crop.height)) px"
    }
    @objc private func drag(_ gesture: UIPanGestureRecognizer) {
        guard let handle = gesture.view else { return }
        if gesture.state == .began { dragging = true; start = crop }
        let anchor = anchors[handle.tag], translation = gesture.translation(in: view)
        let dx = (translation.x / scale).rounded(), dy = (translation.y / scale).rounded()
        var l = start.minX, r = start.maxX, t = start.minY, b = start.maxY
        if anchor.0 == 0 { l = min(r - 16, max(r - 8192, l + dx)) }
        if anchor.0 == 2 { r = min(l + 8192, max(l + 16, r + dx)) }
        if anchor.1 == 0 { t = min(b - 16, max(b - 8192, t + dy)) }
        if anchor.1 == 2 { b = min(t + 8192, max(t + 16, b + dy)) }
        crop = CGRect(x: l, y: t, width: r-l, height: b-t)
        if gesture.state == .cancelled { crop = start }
        dragging = gesture.state == .began || gesture.state == .changed
        view.setNeedsLayout()
    }
}
