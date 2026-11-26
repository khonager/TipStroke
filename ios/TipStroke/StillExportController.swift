import UIKit

struct StillExport {
    enum Format: String, CaseIterable { case png, jpeg = "jpg", webp }
    var format: Format = .png
    var scale: CGFloat = 1
    var quality = 0.9
    var transparent = false
    var name = "TipStroke"
}

final class StillExportController: UIViewController {
    private let record: DocumentRecord
    var onExport: ((StillExport, Bool) -> Void)?
    private let format = UISegmentedControl(items: ["PNG","JPEG","WebP"])
    private let scale = UISegmentedControl(items: ["100%", "50%", "25%"])
    private let quality = UISlider()
    private let alpha = UISwitch()
    private let filename = UITextField()
    private let dimensions = UILabel()
    private let qualityValue = UILabel()
    init(record: DocumentRecord) { self.record = record; super.init(nibName: nil, bundle: nil) }
    required init?(coder: NSCoder) { fatalError() }
    override func viewDidLoad() {
        super.viewDidLoad(); title = "Export image"; view.backgroundColor = .systemBackground
        filename.text = record.name; filename.borderStyle = .roundedRect; filename.accessibilityLabel = "File name"
        format.selectedSegmentIndex = 0; scale.selectedSegmentIndex = 0
        quality.minimumValue = 0.2; quality.maximumValue = 1; quality.value = 0.9; quality.accessibilityLabel = "Quality"
        let transparent = UILabel(); transparent.text = "Transparent background"
        let row = UIStackView(arrangedSubviews: [transparent,alpha])
        let stack = UIStackView(arrangedSubviews: [filename,format,scale,dimensions,qualityValue,quality,row])
        stack.axis = .vertical; stack.spacing = 12; stack.translatesAutoresizingMaskIntoConstraints = false
        let scroll = UIScrollView(frame: view.bounds); scroll.autoresizingMask = [.flexibleWidth,.flexibleHeight]
        view.addSubview(scroll); scroll.addSubview(stack)
        NSLayoutConstraint.activate([stack.leadingAnchor.constraint(equalTo: scroll.contentLayoutGuide.leadingAnchor, constant: 16),
            stack.widthAnchor.constraint(equalTo: scroll.frameLayoutGuide.widthAnchor, constant: -32),
            stack.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor, constant: 16),
            stack.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor, constant: -16)])
        for control: UIControl in [format, scale, quality] { control.addAction(UIAction { [weak self] _ in self?.refresh() }, for: .valueChanged) }
        navigationItem.leftBarButtonItem = UIBarButtonItem(systemItem: .cancel, primaryAction: UIAction { [weak self] _ in self?.dismiss(animated: true) })
        for (title, photos) in [("Save to Photos", true), ("Share", false)] {
            let button = UIButton(configuration: .tinted(), primaryAction: UIAction(title: title) { [weak self] _ in
                guard let self else { return }
                let options = self.options, callback = self.onExport
                self.dismiss(animated: true) { callback?(options, photos) }
            })
            stack.addArrangedSubview(button)
        }
        refresh()
    }
    private var options: StillExport {
        StillExport(format: StillExport.Format.allCases[format.selectedSegmentIndex], scale: [1,0.5,0.25][scale.selectedSegmentIndex],
                    quality: Double(quality.value), transparent: alpha.isOn && format.selectedSegmentIndex != 1, name: filename.text ?? "")
    }
    private func refresh() {
        let o = options
        dimensions.text = "\(max(1, Int((CGFloat(record.width)*o.scale).rounded()))) × \(max(1, Int((CGFloat(record.height)*o.scale).rounded()))) px"
        qualityValue.text = "Quality · \(Int((o.quality*100).rounded()))%"
        qualityValue.isHidden = o.format == .png; quality.isHidden = qualityValue.isHidden
        alpha.isEnabled = o.format != .jpeg
        if !alpha.isEnabled { alpha.isOn = false }
    }
}
