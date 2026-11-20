import UIKit

final class ToolPalette: UIView {
    weak var anchor: UIView?
    var onClose: (() -> Void)?
    private let panel = UIView()
    private let caption = UILabel()
    private var buttons: [UIButton] = []
    private var actions: [() -> Void] = []
    private var hovered: Int?
    private var dwell: DispatchWorkItem?

    override init(frame: CGRect) {
        super.init(frame: frame)
        accessibilityViewIsModal = true
        let backdrop = UIButton(type: .custom)
        backdrop.frame = bounds
        backdrop.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        backdrop.accessibilityLabel = "Close drawing tools"
        backdrop.addAction(UIAction { [weak self] _ in self?.onClose?() }, for: .touchUpInside)
        addSubview(backdrop)
        panel.backgroundColor = .secondarySystemBackground
        panel.layer.cornerRadius = 12
        addSubview(panel)
        caption.font = .preferredFont(forTextStyle: .caption1)
        caption.textAlignment = .center
        panel.addSubview(caption)
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
        let x = min(max(safe.minX, target.midX - width / 2), safe.maxX - width)
        let y = target.minY >= safe.minY + height ? target.minY - height : min(target.maxY, safe.maxY - height)
        panel.frame = CGRect(x: x, y: y, width: width, height: height)
        for (index, button) in buttons.enumerated() {
            button.frame = CGRect(x: CGFloat(index % 4) * cell, y: CGFloat(index / 4) * cell, width: cell, height: cell)
        }
        caption.frame = CGRect(x: 0, y: height - 24, width: width, height: 24)
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
        dwell?.cancel()
        caption.text = nil
        guard let index else { return }
        buttons[index].backgroundColor = .tertiarySystemFill
        let work = DispatchWorkItem { [weak self] in
            guard let self, self.hovered == index else { return }
            self.caption.text = self.buttons[index].accessibilityLabel
        }
        dwell = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.45, execute: work)
    }
    func finish(_ point: CGPoint) {
        let index = hit(point)
        onClose?()
        if let index { actions[index]() }
    }
    @objc private func showName(_ gesture: UILongPressGestureRecognizer) {
        if gesture.state == .began { caption.text = gesture.view?.accessibilityLabel }
    }
    override func accessibilityPerformEscape() -> Bool { onClose?(); return true }
    deinit { dwell?.cancel() }
}
