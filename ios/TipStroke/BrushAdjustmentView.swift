import UIKit

/// A short track with a persistent readout. Size reserves more travel for fine tips.
final class BrushAdjustmentView: UIStackView {
    private let slider = UISlider()
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
        spacing = 6
        readout.titleLabel?.font = .monospacedDigitSystemFont(ofSize: 11, weight: .medium)
        readout.titleLabel?.numberOfLines = 2
        readout.showsMenuAsPrimaryAction = true
        readout.widthAnchor.constraint(equalToConstant: 44).isActive = true
        addArrangedSubview(readout)
        addArrangedSubview(slider)
        slider.accessibilityLabel = title
        slider.addTarget(self, action: #selector(changed), for: .valueChanged)
        slider.addTarget(self, action: #selector(ended), for: [.touchUpInside, .touchUpOutside, .touchCancel])
        heightAnchor.constraint(greaterThanOrEqualToConstant: 40).isActive = true
    }
    required init(coder: NSCoder) { fatalError("Use init(title:isSize:)") }

    func display(_ value: Float, range: ClosedRange<Float>, screenUnits: Bool = false, allowsUnits: Bool = false) {
        self.range = range
        let value = min(range.upperBound, max(range.lowerBound, value))
        slider.value = isSize ? log(value / range.lowerBound) / log(range.upperBound / range.lowerBound)
            : (value - range.lowerBound) / (range.upperBound - range.lowerBound)
        currentValue = value
        let number = isSize ? "\(Int(value.rounded())) \(screenUnits ? "pt" : "px")" : "\(Int((100 * value).rounded()))%"
        readout.setTitle(title + "\n" + number, for: .normal)
        readout.accessibilityLabel = title + " options"
        var items = [-1, 1].map { direction in
            UIAction(title: direction < 0 ? "Decrease by one" : "Increase by one") { [weak self] _ in
                guard let self else { return }
                let step = Float(direction) * (self.isSize ? 1 : 0.01)
                self.adjusted?(min(self.range.upperBound, max(self.range.lowerBound, self.currentValue + step)))
                self.finished?()
            }
        }
        if allowsUnits {
            for screen in [false, true] {
                items.append(UIAction(title: screen ? "Screen points" : "Canvas pixels", state: screen == screenUnits ? .on : .off) { [weak self] _ in
                    self?.screenUnitsChanged?(screen)
                })
            }
        }
        readout.menu = UIMenu(children: items)
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
