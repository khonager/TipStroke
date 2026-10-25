import UIKit

final class EditorController: UIViewController, UIColorPickerViewControllerDelegate, UIPencilInteractionDelegate {
    let document: RasterDocument
    let viewport: CanvasViewport
    private let store = ProjectStore.shared
    private var saveWork: DispatchWorkItem?
    private var saving = false
    private var pending: DocumentSnapshot?
    private var closeAfterSave = false
    private var backgroundTask: UIBackgroundTaskIdentifier = .invalid
    private let undoButton = UIButton(type: .system)
    private let redoButton = UIButton(type: .system)
    private let toolPicker = UISegmentedControl(items: CanvasView.Tool.allCases.map(\.rawValue))
    private let saveLabel = UILabel()
    private var previousTool: CanvasView.Tool = .ink

    init(document: RasterDocument) {
        self.document = document; viewport = CanvasViewport(document: document)
        super.init(nibName: nil, bundle: nil)
    }
    required init?(coder: NSCoder) { fatalError("Use init(document:)") }
    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .secondarySystemBackground
        viewport.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(viewport)
        let back = button("Gallery", symbol: "square.grid.2x2") { [weak self] in self?.close() }
        configure(undoButton, label: "Undo", symbol: "arrow.uturn.backward") { [weak self] in
            guard let self else { return }; self.viewport.canvas.cancelStroke(); self.document.undo(); self.changed()
        }
        configure(redoButton, label: "Redo", symbol: "arrow.uturn.forward") { [weak self] in
            guard let self else { return }; self.viewport.canvas.cancelStroke(); self.document.redo(); self.changed()
        }
        let fit = button("Fit canvas", symbol: "arrow.up.left.and.arrow.down.right") { [weak self] in self?.viewport.fit() }
        let layers = button("Layers", symbol: "square.3.layers.3d") { [weak self] in self?.showLayers() }
        let share = button("Share PNG", symbol: "square.and.arrow.up") { [weak self] in self?.export() }
        let top = UIStackView(arrangedSubviews: [back, undoButton, redoButton, fit, layers, share])
        top.distribution = .equalSpacing
        let topPanel = panel(top)
        toolPicker.selectedSegmentIndex = 1
        toolPicker.addTarget(self, action: #selector(toolChanged), for: .valueChanged)
        toolPicker.accessibilityLabel = "Drawing tool"
        let color = button("Brush color", symbol: "circle.lefthalf.filled") { [weak self] in
            guard let self else { return }; self.viewport.canvas.cancelStroke()
            let picker = UIColorPickerViewController(); picker.selectedColor = self.viewport.canvas.brushColor
            picker.supportsAlpha = false; picker.delegate = self; self.present(picker, animated: true)
        }
        let settings = button("Brush size, opacity and finger drawing", symbol: "slider.horizontal.3") { [weak self] in self?.brushSettings() }
        let bottom = UIStackView(arrangedSubviews: [toolPicker, color, settings]); bottom.spacing = 8
        let bottomPanel = panel(bottom)
        saveLabel.font = .preferredFont(forTextStyle: .caption2)
        saveLabel.textColor = .secondaryLabel; saveLabel.textAlignment = .center
        saveLabel.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(saveLabel)
        let safe = view.safeAreaLayoutGuide
        NSLayoutConstraint.activate([
            viewport.leadingAnchor.constraint(equalTo: view.leadingAnchor), viewport.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            viewport.topAnchor.constraint(equalTo: topPanel.bottomAnchor, constant: 4), viewport.bottomAnchor.constraint(equalTo: bottomPanel.topAnchor, constant: -4),
            topPanel.topAnchor.constraint(equalTo: safe.topAnchor, constant: 4), topPanel.centerXAnchor.constraint(equalTo: safe.centerXAnchor),
            topPanel.widthAnchor.constraint(lessThanOrEqualToConstant: 380), topPanel.leadingAnchor.constraint(greaterThanOrEqualTo: safe.leadingAnchor, constant: 8),
            topPanel.trailingAnchor.constraint(lessThanOrEqualTo: safe.trailingAnchor, constant: -8),
            topPanel.widthAnchor.constraint(equalTo: safe.widthAnchor, constant: -16).withPriority(.defaultHigh),
            bottomPanel.centerXAnchor.constraint(equalTo: safe.centerXAnchor), bottomPanel.bottomAnchor.constraint(equalTo: saveLabel.topAnchor, constant: -2),
            bottomPanel.widthAnchor.constraint(lessThanOrEqualToConstant: 480), bottomPanel.leadingAnchor.constraint(greaterThanOrEqualTo: safe.leadingAnchor, constant: 4),
            bottomPanel.trailingAnchor.constraint(lessThanOrEqualTo: safe.trailingAnchor, constant: -4),
            saveLabel.bottomAnchor.constraint(equalTo: safe.bottomAnchor, constant: -2), saveLabel.centerXAnchor.constraint(equalTo: safe.centerXAnchor)
        ])
        viewport.canvas.onCommit = { [weak self] in self?.changed(redraw: false) }
        viewport.canvas.onError = { [weak self] error in self?.showError(error) }
        let pencil = UIPencilInteraction(); pencil.delegate = self; viewport.addInteraction(pencil)
        NotificationCenter.default.addObserver(self, selector: #selector(backgrounded), name: UIApplication.willResignActiveNotification, object: nil)
        updateHistory(); saveLabel.text = "Saved on this device"
    }
    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        navigationController?.setNavigationBarHidden(true, animated: animated)
        navigationController?.interactivePopGestureRecognizer?.isEnabled = false
    }
    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        if isMovingFromParent { navigationController?.interactivePopGestureRecognizer?.isEnabled = true }
    }
    deinit { NotificationCenter.default.removeObserver(self); saveWork?.cancel() }
    private func panel(_ stack: UIStackView) -> UIView {
        let panel = UIVisualEffectView(effect: UIBlurEffect(style: .systemMaterial))
        panel.layer.cornerRadius = 16; panel.clipsToBounds = true
        panel.translatesAutoresizingMaskIntoConstraints = false; stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(panel); panel.contentView.addSubview(stack)
        NSLayoutConstraint.activate([stack.leadingAnchor.constraint(equalTo: panel.contentView.leadingAnchor, constant: 8),
            stack.trailingAnchor.constraint(equalTo: panel.contentView.trailingAnchor, constant: -8),
            stack.topAnchor.constraint(equalTo: panel.contentView.topAnchor, constant: 2),
            stack.bottomAnchor.constraint(equalTo: panel.contentView.bottomAnchor, constant: -2)])
        return panel
    }
    private func button(_ label: String, symbol: String, action: @escaping () -> Void) -> UIButton {
        let button = UIButton(type: .system); configure(button, label: label, symbol: symbol, action: action); return button
    }
    private func configure(_ button: UIButton, label: String, symbol: String, action: @escaping () -> Void) {
        button.setImage(UIImage(systemName: symbol), for: .normal); button.accessibilityLabel = label
        button.addAction(UIAction { _ in action() }, for: .touchUpInside)
        button.widthAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
        button.heightAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
    }
    @objc private func toolChanged() {
        viewport.canvas.cancelStroke(); viewport.canvas.tool = CanvasView.Tool.allCases[toolPicker.selectedSegmentIndex]
    }
    func pencilInteractionDidTap(_ interaction: UIPencilInteraction) {
        viewport.canvas.cancelStroke()
        if viewport.canvas.tool == .eraser { viewport.canvas.tool = previousTool }
        else { previousTool = viewport.canvas.tool; viewport.canvas.tool = .eraser }
        toolPicker.selectedSegmentIndex = CanvasView.Tool.allCases.firstIndex(of: viewport.canvas.tool) ?? 1
    }
    func colorPickerViewControllerDidSelectColor(_ viewController: UIColorPickerViewController) { viewport.canvas.brushColor = viewController.selectedColor }
    private func updateHistory() { undoButton.isEnabled = document.canUndo; redoButton.isEnabled = document.canRedo }
    private func changed(redraw: Bool = true) {
        if redraw { viewport.canvas.refresh() }
        updateHistory(); saveLabel.text = "Unsaved changes"
        saveWork?.cancel()
        let work = DispatchWorkItem { [weak self] in self?.saveNow() }
        saveWork = work; DispatchQueue.main.asyncAfter(deadline: .now() + 0.8, execute: work)
    }
    @objc private func backgrounded() {
        viewport.canvas.cancelStroke()
        if backgroundTask == .invalid {
            backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "Save drawing") { [weak self] in self?.endBackgroundTask() }
        }
        saveNow()
    }
    private func endBackgroundTask() {
        if backgroundTask != .invalid { UIApplication.shared.endBackgroundTask(backgroundTask); backgroundTask = .invalid }
    }
    private func saveNow() {
        saveWork?.cancel(); saveWork = nil
        guard !viewport.canvas.isDrawing else { return }
        // The latest snapshot replaces any pending save, bounding queued artwork retention.
        pending = document.snapshot()
        startSaveIfNeeded()
    }
    private func startSaveIfNeeded() {
        guard !saving, let snapshot = pending else { return }
        pending = nil; saving = true; saveLabel.text = "Saving…"
        store.queue.async {
            let result = Result { try self.store.save(snapshot) }
            DispatchQueue.main.async {
                self.saving = false
                switch result {
                case .failure(let error):
                    self.pending = nil; self.closeAfterSave = false; self.view.isUserInteractionEnabled = true
                    self.saveLabel.text = "Save failed — tap to retry"; self.showError(error)
                    let tap = UITapGestureRecognizer(target: self, action: #selector(self.retrySave))
                    self.saveLabel.gestureRecognizers?.forEach { self.saveLabel.removeGestureRecognizer($0) }
                    self.saveLabel.addGestureRecognizer(tap); self.saveLabel.isUserInteractionEnabled = true
                    self.endBackgroundTask()
                case .success:
                    if self.pending != nil { self.startSaveIfNeeded() }
                    else {
                        self.saveLabel.text = self.saveWork == nil ? "Saved on this device" : "Unsaved changes"
                        self.endBackgroundTask()
                        if self.closeAfterSave { self.navigationController?.popViewController(animated: true) }
                    }
                }
            }
        }
    }
    @objc private func retrySave() { saveNow() }
    private func close() {
        viewport.canvas.cancelStroke(); closeAfterSave = true; view.isUserInteractionEnabled = false; saveNow()
    }
    private func export() {
        viewport.canvas.cancelStroke()
        let snapshot = document.snapshot()
        store.queue.async {
            let result = Result { try self.store.exportPNG(snapshot) }
            DispatchQueue.main.async {
                switch result {
                case .failure(let error): self.showError(error)
                case .success(let url):
                    let sheet = UIActivityViewController(activityItems: [url], applicationActivities: nil)
                    sheet.popoverPresentationController?.sourceView = self.view
                    sheet.popoverPresentationController?.sourceRect = CGRect(x: self.view.bounds.midX, y: self.view.safeAreaInsets.top + 30, width: 1, height: 1)
                    self.present(sheet, animated: true)
                }
            }
        }
    }
    private func showLayers() {
        viewport.canvas.cancelStroke()
        let layers = LayersController(document: document)
        layers.onChange = { [weak self] in self?.changed() }
        let navigation = UINavigationController(rootViewController: layers)
        navigation.modalPresentationStyle = .pageSheet
        navigation.sheetPresentationController?.detents = [.medium(), .large()]
        present(navigation, animated: true)
    }
    private func brushSettings() {
        viewport.canvas.cancelStroke()
        let controller = BrushController(canvas: viewport.canvas)
        let navigation = UINavigationController(rootViewController: controller)
        navigation.sheetPresentationController?.detents = [.medium()]
        present(navigation, animated: true)
    }
}

private extension NSLayoutConstraint {
    func withPriority(_ priority: UILayoutPriority) -> NSLayoutConstraint { self.priority = priority; return self }
}

final class BrushController: UIViewController {
    private let canvas: CanvasView
    init(canvas: CanvasView) { self.canvas = canvas; super.init(nibName: nil, bundle: nil) }
    required init?(coder: NSCoder) { fatalError("Use init(canvas:)") }
    override func viewDidLoad() {
        super.viewDidLoad(); title = "Brush"; view.backgroundColor = .systemBackground
        navigationItem.rightBarButtonItem = UIBarButtonItem(systemItem: .done, primaryAction: UIAction { [weak self] _ in self?.dismiss(animated: true) })
        let sizeLabel = UILabel(); sizeLabel.text = "Size · \(Int(canvas.brushSize)) px"
        let size = UISlider(); size.minimumValue = 1; size.maximumValue = 128; size.value = Float(canvas.brushSize)
        size.accessibilityLabel = "Brush size"
        size.addAction(UIAction { [weak self, weak size, weak sizeLabel] _ in
            guard let size else { return }; self?.canvas.brushSize = CGFloat(size.value); sizeLabel?.text = "Size · \(Int(size.value)) px"
        }, for: .valueChanged)
        let opacityLabel = UILabel(); opacityLabel.text = "Opacity · \(Int(canvas.brushOpacity * 100))%"
        let opacity = UISlider(); opacity.minimumValue = 0.05; opacity.maximumValue = 1; opacity.value = Float(canvas.brushOpacity)
        opacity.accessibilityLabel = "Brush opacity"
        opacity.addAction(UIAction { [weak self, weak opacity, weak opacityLabel] _ in
            guard let opacity else { return }; self?.canvas.brushOpacity = CGFloat(opacity.value); opacityLabel?.text = "Opacity · \(Int(opacity.value * 100))%"
        }, for: .valueChanged)
        let finger = UISwitch(); finger.isOn = canvas.fingerDrawing; finger.accessibilityLabel = "Draw with finger"
        finger.addAction(UIAction { [weak self, weak finger] _ in self?.canvas.fingerDrawing = finger?.isOn ?? true }, for: .valueChanged)
        let label = UILabel(); label.text = "Draw with finger"
        let row = UIStackView(arrangedSubviews: [label, finger]); row.distribution = .equalSpacing
        let stack = UIStackView(arrangedSubviews: [sizeLabel, size, opacityLabel, opacity, row])
        stack.axis = .vertical; stack.spacing = 12; stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)
        NSLayoutConstraint.activate([stack.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 20),
            stack.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor, constant: 24),
            stack.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -24)])
    }
}
