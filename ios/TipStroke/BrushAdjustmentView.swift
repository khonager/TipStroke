import UIKit

private final class AdjustmentSlider: UISlider {
    override func trackRect(forBounds bounds: CGRect) -> CGRect {
        let rect = super.trackRect(forBounds: bounds)
        return CGRect(x: rect.minX, y: bounds.midY - 6, width: rect.width, height: 12)
    }
}

/// Full-width tracks with persistent readouts and independent step buttons.
final class BrushAdjustmentView: UIStackView {
    private let slider = AdjustmentSlider()
    private let minus = UIButton(type: .system)
    private let plus = UIButton(type: .system)
    private let readout = UIButton(type: .system)
    private var currentValue: Float = 1
    var screenUnitsChanged: ((Bool) -> Void)?
    private let title: String
    private let isSize: Bool
    private var range: ClosedRange<Float> = 0...1
    var adjusted: ((Float) -> Void)?
    var finished: (() -> Void)?

    init(title: String, isSize: Bool) {
        self.title = title
        self.isSize = isSize
        super.init(frame: .zero)
        axis = .vertical
        spacing = 2
        let label = UILabel()
        label.text = title
        label.font = .preferredFont(forTextStyle: .subheadline)
        readout.titleLabel?.font = .monospacedDigitSystemFont(ofSize: 14, weight: .medium)
        readout.tintColor = UIColor(red: 0.93, green: 0.41, blue: 0.35, alpha: 1)
        readout.showsMenuAsPrimaryAction = true
        let header = UIStackView(arrangedSubviews: [label, UIView(), readout])
        addArrangedSubview(header)
        let accent = readout.tintColor ?? .systemOrange
        slider.minimumTrackTintColor = accent
        slider.maximumTrackTintColor = UIColor(red: 0.28, green: 0.27, blue: 0.33, alpha: 1)
        let thumb = UIGraphicsImageRenderer(size: CGSize(width: 12, height: 38)).image { _ in
            accent.setFill()
            UIBezierPath(roundedRect: CGRect(x: 4, y: 0, width: 4, height: 38), cornerRadius: 2).fill()
        }
        slider.setThumbImage(thumb.withRenderingMode(.alwaysOriginal), for: .normal)
        slider.heightAnchor.constraint(equalToConstant: 44).isActive = true
        addArrangedSubview(slider)
        let steps = UIStackView()
        steps.spacing = 6
        for (button, symbol, direction) in [(minus, "minus", -1), (plus, "plus", 1)] {
            button.setImage(UIImage(systemName: symbol), for: .normal)
            button.accessibilityLabel = (direction < 0 ? "Decrease " : "Increase ") + title.lowercased()
            button.addAction(UIAction { [weak self] _ in self?.step(direction) }, for: .touchUpInside)
            button.widthAnchor.constraint(equalToConstant: 44).isActive = true
            button.heightAnchor.constraint(equalToConstant: 44).isActive = true
            button.backgroundColor = slider.maximumTrackTintColor
            button.layer.cornerRadius = 12
            steps.addArrangedSubview(button)
        }
        steps.addArrangedSubview(UIView())
        addArrangedSubview(steps)
        slider.accessibilityIdentifier = title
        slider.addTarget(self, action: #selector(changed), for: .valueChanged)
        slider.addTarget(self, action: #selector(ended), for: [.touchUpInside, .touchUpOutside, .touchCancel])
    }
    required init(coder: NSCoder) { fatalError("Use init(title:isSize:)") }

    private func step(_ direction: Int) {
        let delta = Float(direction) * (isSize ? 1 : 0.01)
        adjusted?(min(range.upperBound, max(range.lowerBound, currentValue + delta)))
        finished?()
    }
    func display(_ value: Float, range: ClosedRange<Float>, screenUnits: Bool = false, allowsUnits: Bool = false) {
        self.range = range
        let value = min(range.upperBound, max(range.lowerBound, value))
        slider.value = isSize ? log(value / range.lowerBound) / log(range.upperBound / range.lowerBound)
            : (value - range.lowerBound) / (range.upperBound - range.lowerBound)
        currentValue = value
        let number = isSize ? "\(Int(value.rounded())) \(screenUnits ? "pt" : "px")" : "\(Int((100 * value).rounded()))%"
        readout.setTitle(number + (allowsUnits ? " ▾" : ""), for: .normal)
        readout.accessibilityLabel = title + " options"
        var items: [UIAction] = []
        if allowsUnits {
            for screen in [false, true] {
                items.append(UIAction(title: screen ? "Screen points" : "Canvas pixels", state: screen == screenUnits ? .on : .off) { [weak self] _ in
                    self?.screenUnitsChanged?(screen)
                })
            }
        }
        readout.menu = items.isEmpty ? nil : UIMenu(children: items)
        readout.isUserInteractionEnabled = allowsUnits
        // Keep UISlider’s native accessibility value in track coordinates. Size uses
        // a logarithmic scale, so its brush value cannot describe the thumb position.
        slider.accessibilityLabel = "\(title), \(number)"
        readout.accessibilityIdentifier = title + " readout"
        readout.accessibilityValue = number
    }
    @objc private func changed() {
        let value = isSize ? (range.lowerBound * pow(range.upperBound / range.lowerBound, slider.value)).rounded()
            : ((range.lowerBound + slider.value * (range.upperBound - range.lowerBound)) * 100).rounded() / 100
        adjusted?(value)
        if !slider.isTracking { finished?() }
    }
    @objc private func ended() { finished?() }
}
