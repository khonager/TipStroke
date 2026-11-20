import UIKit

final class ToolPalette: UIView {
    weak var anchor: UIView?
    var beside = false
    var onClose: (() -> Void)?
    private let panel = UIView()
    private let caption = UILabel()
    private var buttons: [UIButton] = []
    private var actions: [() -> Void] = []
    private var hovered: Int?

    override init(frame: CGRect) {
        super.init(frame: frame)
        accessibilityViewIsModal = true
        let backdrop = UIButton(type: .custom)
        backdrop.frame = bounds
        backdrop.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        backdrop.accessibilityLabel = "Close drawing tools"
        backdrop.addAction(UIAction { [weak self] _ in self?.onClose?() }, for: .touchUpInside)
        addSubview(backdrop)
        panel.backgroundColor = UIColor(red: 32/255, green: 33/255, blue: 37/255, alpha: 1)
        panel.tintColor = .white
        caption.textColor = .white
        panel.layer.cornerRadius = 12
        addSubview(panel)
        caption.font = .preferredFont(forTextStyle: .caption1)
        caption.textAlignment = .center
        panel.addSubview(caption)
        panel.addGestureRecognizer(UIHoverGestureRecognizer(target: self, action: #selector(hoverIcon(_:))))
    }
    required init?(coder: NSCoder) { fatalError("Use init(frame:)") }

    func add(_ name: String, _ symbol: String, _ action: @escaping () -> Void) {
        let button = UIButton(type: .system)
        button.setImage(UIImage(systemName: symbol), for: .normal)
        button.accessibilityLabel = name
        button.addAction(UIAction { [weak self] _ in self?.onClose?(); action() }, for: .touchUpInside)
        button.addGestureRecognizer(UILongPressGestureRecognizer(target: self, action: #selector(showName(_:))))
        buttons.append(button)
        actions.append(action)
        panel.addSubview(button)
        setNeedsLayout()
    }
    override func layoutSubviews() {
        super.layoutSubviews()
        guard let anchor else { return }
        let target = anchor.convert(anchor.bounds, to: self)
        let safe = bounds.inset(by: safeAreaInsets)
        let cell: CGFloat = min(48, safe.width / 4)
        let width = cell * 4, height = CGFloat((buttons.count + 3) / 4) * cell + 24
        let preferredX = beside ? target.minX - width : target.midX - width / 2
        let x = min(max(safe.minX, preferredX), safe.maxX - width)
        let preferredY = beside ? target.midY - height / 2 : (target.minY >= safe.minY + height ? target.minY - height : target.maxY)
        let y = max(safe.minY, min(preferredY, safe.maxY - height))
        panel.frame = CGRect(x: x, y: y, width: width, height: height)
        for (index, button) in buttons.enumerated() {
            button.frame = CGRect(x: CGFloat(index % 4) * cell, y: 24 + CGFloat(index / 4) * cell, width: cell, height: cell)
        }
        caption.frame = CGRect(x: 0, y: 0, width: width, height: 24)
    }

    private func hit(_ point: CGPoint) -> Int? {
        let local = panel.convert(point, from: self)
        return buttons.firstIndex { $0.frame.contains(local) }
    }
    func track(_ point: CGPoint) {
        let index = hit(point)
        guard index != hovered else { return }
        if let hovered { buttons[hovered].backgroundColor = .clear }
        hovered = index
        caption.text = nil
        guard let index else { return }
        buttons[index].backgroundColor = UIColor(white: 0.32, alpha: 1)
        caption.text = buttons[index].accessibilityLabel
    }
    func finish(_ point: CGPoint) {
        let index = hit(point)
        onClose?()
        if let index { actions[index]() }
    }
    @objc private func hoverIcon(_ gesture: UIHoverGestureRecognizer) {
        if gesture.state == .began || gesture.state == .changed { track(gesture.location(in: self)) }
        else { track(CGPoint(x: -1, y: -1)) }
    }
    @objc private func showName(_ gesture: UILongPressGestureRecognizer) {
        if gesture.state == .began { caption.text = gesture.view?.accessibilityLabel }
    }
    override func accessibilityPerformEscape() -> Bool { onClose?(); return true }
}
