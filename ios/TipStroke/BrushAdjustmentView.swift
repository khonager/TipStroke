import UIKit

/// A short track with a persistent readout. Size reserves more travel for fine tips.
final class BrushAdjustmentView: UIStackView {
    private let slider = UISlider()
    private let track = UIView()
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
        axis = .horizontal
        spacing = 2
        readout.titleLabel?.font = .monospacedDigitSystemFont(ofSize: 11, weight: .medium)
        readout.titleLabel?.numberOfLines = 2
        readout.showsMenuAsPrimaryAction = true
        readout.setContentHuggingPriority(.required, for: .horizontal)
        addArrangedSubview(readout)
        track.addSubview(slider)
        for (button, symbol, direction) in [(minus, "minus", -1), (plus, "plus", 1)] {
            button.setImage(UIImage(systemName: symbol), for: .normal)
            button.accessibilityLabel = (direction < 0 ? "Decrease " : "Increase ") + title.lowercased()
            button.addAction(UIAction { [weak self] _ in self?.step(direction) }, for: .touchUpInside)
            button.widthAnchor.constraint(greaterThanOrEqualToConstant: 24).isActive = true
        }
        addArrangedSubview(minus)
        addArrangedSubview(track)
        addArrangedSubview(plus)
        slider.accessibilityLabel = title
        slider.addTarget(self, action: #selector(changed), for: .valueChanged)
        slider.addTarget(self, action: #selector(ended), for: [.touchUpInside, .touchUpOutside, .touchCancel])
        heightAnchor.constraint(greaterThanOrEqualToConstant: 40).isActive = true
    }
    required init(coder: NSCoder) { fatalError("Use init(title:isSize:)") }

    private var trackHeight: NSLayoutConstraint?
    func setVertical(_ vertical: Bool) {
        axis = vertical ? .vertical : .horizontal
        trackHeight?.isActive = false
        if vertical {
            insertArrangedSubview(plus, at: 1)
            addArrangedSubview(minus)
            trackHeight = track.heightAnchor.constraint(equalToConstant: 58)
            trackHeight?.isActive = true
        } else {
            insertArrangedSubview(minus, at: 1)
            addArrangedSubview(plus)
        }
        setNeedsLayout()
    }
    override func layoutSubviews() {
        super.layoutSubviews()
        let vertical = axis == .vertical
        slider.bounds = CGRect(x: 0, y: 0, width: vertical ? track.bounds.height : track.bounds.width, height: vertical ? track.bounds.width : track.bounds.height)
        slider.transform = vertical ? CGAffineTransform(rotationAngle: -.pi / 2) : .identity
        slider.center = CGPoint(x: track.bounds.midX, y: track.bounds.midY)
    }
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
        readout.setTitle(title + "\n" + number, for: .normal)
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
        slider.accessibilityValue = number
    }
    @objc private func changed() {
        let value = isSize ? (range.lowerBound * pow(range.upperBound / range.lowerBound, slider.value)).rounded()
            : ((range.lowerBound + slider.value * (range.upperBound - range.lowerBound)) * 100).rounded() / 100
        adjusted?(value)
        if !slider.isTracking { finished?() }
    }
    @objc private func ended() { finished?() }
}
