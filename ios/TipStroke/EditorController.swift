import UIKit
import TipStrokeShared
import UniformTypeIdentifiers
import ImageIO

final class EditorController: UIViewController, UIPencilInteractionDelegate, UIDocumentPickerDelegate {
    let document: RasterDocument
    let viewport: CanvasViewport
    private let store = ProjectStore.shared
    private lazy var recorder = ProcessRecorder(store: store, id: document.record.id)
    private var saveWork: DispatchWorkItem?
    private var saving = false
    private var pending: DocumentSnapshot?
    private var closeAfterSave = false
    private var backgroundTask: UIBackgroundTaskIdentifier = .invalid
    private let undoButton = UIButton(type: .system)
    private let redoButton = UIButton(type: .system)
    private let toolPicker = UISegmentedControl(items: CanvasView.Tool.allCases.map(\.rawValue))
    private var palette: ToolPalette?
    private var portraitLayout: [NSLayoutConstraint] = []
    private var landscapeLayout: [NSLayoutConstraint] = []
    private weak var actionStack: UIStackView?
    private var sideDock = false
    private let saveLabel = UILabel()
    private let hoverName = UILabel()
    private var hoverWork: DispatchWorkItem?

    lazy var sharedControls = AppleEditorBridge(actions: EditorActions(self))
    var fillPending = false
    var closingEditor: Bool { closeAfterSave }
    var exporting = false
    var importingMedia = false
    var importingBrush = false
    var importingBrushTexture: Bool?
    private var previousTool: CanvasView.Tool = .ink

    init(document: RasterDocument) {
        self.document = document; viewport = CanvasViewport(document: document)
        super.init(nibName: nil, bundle: nil)
    }
    required init?(coder: NSCoder) { fatalError("Use init(document:)") }
    override func viewDidLoad() {
        super.viewDidLoad()
        document.onCelReady = { [weak self] in self?.viewport.canvas.refresh(); self?.updateHistory() }
        document.onDeferredHistoryChange = { [weak self] in self?.changed() }
        document.onCelError = { [weak self] error in self?.showError(error) }
        if let animation = document.record.animation, animation.onionSkin {
            document.prepareFrame(animation.selectedIndex) { [weak self] in self?.viewport.canvas.refresh() }
        }
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
        share.showsMenuAsPrimaryAction = true
        share.menu = UIMenu(children: [
            UIAction(title: "Share PNG", image: UIImage(systemName: "square.and.arrow.up")) { [weak self] _ in self?.export() },
            UIAction(title: "Drawing progress", image: UIImage(systemName: "play.rectangle")) { [weak self] _ in
                guard let self else { return }
                self.viewport.canvas.cancelStroke()
                self.present(UINavigationController(rootViewController: ProcessReplayController(folder: self.store.replayFolder(self.document.record.id))), animated: true)
            },
            UIAction(title: "Share transparent PNG") { [weak self] _ in self?.export(transparent: true) },
            UIAction(title: "Share animation MP4") { [weak self] _ in self?.chooseExportTiming { self?.exportAnimationVideo(smooth: $0) } },
            UIAction(title: "Share PNG sequence") { [weak self] _ in self?.chooseExportTiming { self?.exportAnimation(pngSequence: true, smooth: $0) } },
            UIAction(title: "Share animation GIF") { [weak self] _ in self?.chooseExportTiming { self?.exportAnimation(smooth: $0) } },
            UIAction(title: "Import media clip") { [weak self] _ in self?.importMedia() },
            UIAction(title: "Import image", image: UIImage(systemName: "photo")) { [weak self] _ in self?.importImage() }
        ])
        let top = UIStackView(arrangedSubviews: [back, undoButton, redoButton, fit, layers, share])
        top.distribution = .equalSpacing
        actionStack = top
        let topPanel = panel(top)
        toolPicker.setTitleTextAttributes([.font: UIFont.systemFont(ofSize: 12)], for: .normal)
        toolPicker.apportionsSegmentWidthsByContent = true
        toolPicker.selectedSegmentIndex = 1
        toolPicker.addTarget(self, action: #selector(toolChanged), for: .valueChanged)
        toolPicker.accessibilityLabel = "Drawing tool"
        let color = button("Brush color", symbol: "circle.lefthalf.filled") { [weak self] in
            guard let self else { return }; self.viewport.canvas.cancelStroke()
            self.refreshDrawingColors()
            self.showShared(self.sharedControls.colorController(), title: "Colors")
        }
        color.addGestureRecognizer(UIPanGestureRecognizer(target: self, action: #selector(dropColor(_:))))
        color.accessibilityHint = "Tap to change color or drag to fill a region."
        let settings = button("Brush size, opacity and finger drawing", symbol: "slider.horizontal.3") { [weak self] in self?.brushSettings() }
        let tools = button("Drawing tools", symbol: "eraser") { [weak self] in
            guard let self else { return }
            if self.holdTools { self.toggleEraser() } else { self.openPalette() }
        }
        tools.accessibilityHint = "Tap for the default action or open the drawing tool palette."
        tools.accessibilityCustomActions = [UIAccessibilityCustomAction(name: "Open drawing tools") { [weak self] _ in
            self?.openPalette(); return true
        }]
        tools.addGestureRecognizer(UILongPressGestureRecognizer(target: self, action: #selector(trackTools(_:))))
        toolsButton = tools
        updateToolValue()
        let bottomActions = UIStackView(arrangedSubviews: [color, tools, settings]); bottomActions.distribution = .equalSpacing
        let quickColors = sharedControls.quickColorsController()
        quickShelf = quickColors.view
        addChild(quickColors)
        quickColors.view.heightAnchor.constraint(equalToConstant: 44).isActive = true
        let bottom = UIStackView(arrangedSubviews: [toolPicker, quickColors.view, bottomActions]); bottom.spacing = 4
        quickColors.didMove(toParent: self)
        bottom.axis = .vertical

        let bottomPanel = panel(bottom)
        saveLabel.font = .preferredFont(forTextStyle: .caption2)
        saveLabel.textColor = .secondaryLabel; saveLabel.textAlignment = .center
        saveLabel.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(saveLabel)
        let safe = view.safeAreaLayoutGuide
        portraitLayout = [
            viewport.leadingAnchor.constraint(equalTo: view.leadingAnchor), viewport.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            viewport.topAnchor.constraint(equalTo: topPanel.bottomAnchor, constant: 4), viewport.bottomAnchor.constraint(equalTo: bottomPanel.topAnchor, constant: -4),
            topPanel.topAnchor.constraint(equalTo: safe.topAnchor, constant: 4), topPanel.centerXAnchor.constraint(equalTo: safe.centerXAnchor),
            topPanel.widthAnchor.constraint(lessThanOrEqualToConstant: 380), topPanel.leadingAnchor.constraint(greaterThanOrEqualTo: safe.leadingAnchor, constant: 8),
            topPanel.trailingAnchor.constraint(lessThanOrEqualTo: safe.trailingAnchor, constant: -8),
            topPanel.widthAnchor.constraint(equalTo: safe.widthAnchor, constant: -16).withPriority(.defaultHigh),
            bottomPanel.centerXAnchor.constraint(equalTo: safe.centerXAnchor), bottomPanel.bottomAnchor.constraint(equalTo: saveLabel.topAnchor, constant: -2),
            bottomPanel.widthAnchor.constraint(lessThanOrEqualToConstant: 480), bottomPanel.leadingAnchor.constraint(greaterThanOrEqualTo: safe.leadingAnchor, constant: 4),
            bottomPanel.trailingAnchor.constraint(lessThanOrEqualTo: safe.trailingAnchor, constant: -4),
            bottomPanel.widthAnchor.constraint(equalTo: safe.widthAnchor, constant: -12).withPriority(.defaultHigh),
        ]
        landscapeLayout = [
            viewport.leadingAnchor.constraint(equalTo: view.leadingAnchor), viewport.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            viewport.topAnchor.constraint(equalTo: view.topAnchor), viewport.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            topPanel.leadingAnchor.constraint(equalTo: safe.leadingAnchor, constant: 4), topPanel.centerYAnchor.constraint(equalTo: safe.centerYAnchor),
            bottomPanel.trailingAnchor.constraint(equalTo: safe.trailingAnchor, constant: -4), bottomPanel.centerYAnchor.constraint(equalTo: safe.centerYAnchor),
            bottomPanel.widthAnchor.constraint(equalToConstant: 180),
        ]
        NSLayoutConstraint.activate(portraitLayout)
        NSLayoutConstraint.activate([saveLabel.bottomAnchor.constraint(equalTo: safe.bottomAnchor, constant: -2),
            saveLabel.centerXAnchor.constraint(equalTo: safe.centerXAnchor)])
        let hint = UIButton(type: .system)
        hint.configuration = .tinted(); hint.titleLabel?.font = .preferredFont(forTextStyle: .caption1)
        hint.translatesAutoresizingMaskIntoConstraints = false; hint.isHidden = true
        hint.addAction(UIAction { [weak self] _ in self?.viewport.canvas.stopLiveDrawing(); self?.viewport.canvas.cancelStroke() }, for: .touchUpInside)
        view.addSubview(hint)
        NSLayoutConstraint.activate([
            hint.topAnchor.constraint(equalTo: safe.topAnchor, constant: 55),
            hint.centerXAnchor.constraint(equalTo: safe.centerXAnchor),
            hint.widthAnchor.constraint(lessThanOrEqualTo: safe.widthAnchor, constant: -16)
        ])
        viewport.canvas.onInteractionHint = { [weak hint] text in hint?.setTitle(text, for: .normal); hint?.isHidden = text == nil }
        viewport.canvas.onCommit = { [weak self] in self?.sharedControls.recordColor(); self?.changed(redraw: false) }
        viewport.canvas.onPickColor = { [weak self] color in
            var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
            color.getRed(&r, green: &g, blue: &b, alpha: &a)
            self?.sharedControls.setColor(red: Float(r), green: Float(g), blue: Float(b))
        }
        viewport.canvas.onFill = { [weak self] point in self?.fill(at: point) }
        viewport.canvas.onError = { [weak self] error in self?.showError(error) }
        configureFingerGestures()
        let pencil = UIPencilInteraction(); pencil.delegate = self; viewport.addInteraction(pencil)
        NotificationCenter.default.addObserver(self, selector: #selector(backgrounded), name: UIApplication.willResignActiveNotification, object: nil)
        ProjectStore.shared.queue.async {
            let url = ProjectStore.shared.root.appendingPathComponent("brush-library.json")
            if let data = try? String(contentsOf: url, encoding: .utf8) {
                DispatchQueue.main.async { _ = self.sharedControls.restoreLibrary(data: data) }
            }
        }
        let defaults = UserDefaults.standard
        let savedBrush = defaults.string(forKey: "brush.data")
        sharedControls.restore(id: defaults.string(forKey: "brush.id") ?? "ink-v1",
            size: defaults.object(forKey: "brush.size") as? Float ?? 18,
            opacity: defaults.object(forKey: "brush.opacity") as? Float ?? 1,
            finger: defaults.object(forKey: "fingerDrawing") as? Bool ?? true)
        if let data = savedBrush { _ = sharedControls.restoreBrush(data: data) }
        refreshDrawingColors()
        recorder.capture(document)
        updateHistory(); saveLabel.text = "Saved on this device"
    }
    override func viewWillLayoutSubviews() {
        super.viewWillLayoutSubviews()
        let safe = view.safeAreaLayoutGuide.layoutFrame
        let useSideDock = safe.width > safe.height && safe.width >= 560 && safe.height >= 280
        guard useSideDock != sideDock else { return }
        NSLayoutConstraint.deactivate(sideDock ? landscapeLayout : portraitLayout)
        sideDock = useSideDock
        actionStack?.axis = useSideDock ? .vertical : .horizontal
        for (index, symbol) in ["pencil", "paintbrush.pointed", "aqi.low", "eraser"].enumerated() {
            if useSideDock { toolPicker.setImage(UIImage(systemName: symbol), forSegmentAt: index) }
            else { toolPicker.setTitle(CanvasView.Tool.allCases[index].rawValue, forSegmentAt: index) }
        }
        NSLayoutConstraint.activate(useSideDock ? landscapeLayout : portraitLayout)
    }
    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        navigationController?.setNavigationBarHidden(true, animated: animated)
        navigationController?.interactivePopGestureRecognizer?.isEnabled = false
    }
    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        viewport.canvas.stopLiveDrawing()
        palette?.onClose?()
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
        button.addGestureRecognizer(UIHoverGestureRecognizer(target: self, action: #selector(hoverControl(_:))))
        button.addAction(UIAction { _ in action() }, for: .touchUpInside)
        button.widthAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
        button.heightAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
    }
    @objc private func hoverControl(_ gesture: UIHoverGestureRecognizer) {
        guard gesture.state != .changed else { return }
        hoverWork?.cancel(); hoverName.removeFromSuperview()
        guard gesture.state == .began, let control = gesture.view, let title = control.accessibilityLabel else { return }
        let work = DispatchWorkItem { [weak self, weak control] in
            guard let self, let control, control.window != nil, self.palette == nil else { return }
            let label = self.hoverName
            label.text = title; label.font = .preferredFont(forTextStyle: .caption1); label.textAlignment = .center
            label.textColor = .label; label.backgroundColor = .secondarySystemBackground
            label.layer.cornerRadius = 6; label.clipsToBounds = true; label.sizeToFit()
            let target = control.convert(control.bounds, to: self.view), safe = self.view.safeAreaLayoutGuide.layoutFrame
            let w = min(safe.width, label.bounds.width + 16), h = label.bounds.height + 8
            let y = target.maxY + h <= safe.maxY ? target.maxY : target.minY-h
            label.frame = CGRect(x: min(max(safe.minX, target.midX - w/2), safe.maxX-w), y: y, width: w, height: h)
            self.view.addSubview(label)
        }
        hoverWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.65, execute: work)
    }
    @objc private func toolChanged() {
        viewport.canvas.cancelStroke()
        let tool = CanvasView.Tool.allCases[toolPicker.selectedSegmentIndex]
        if tool == .eraser && viewport.canvas.tool != .eraser { previousTool = viewport.canvas.tool }
        viewport.canvas.tool = tool
        viewport.canvas.mode = .paint
        if tool != .eraser { sharedControls.selectBrush(id: tool.brushID) }
        updateToolValue()
    }
    func pencilInteractionDidTap(_ interaction: UIPencilInteraction) {
        toggleEraser()
    }
    @available(iOS 17.5, *)
    func pencilInteraction(_ interaction: UIPencilInteraction, didReceiveSqueeze squeeze: UIPencilInteraction.Squeeze) {
        guard squeeze.phase == .ended, presentedViewController == nil else { return }
        switch UIPencilInteraction.preferredSqueezeAction {
        case .switchEraser: toggleEraser()
        case .showColorPalette:
            viewport.canvas.cancelStroke()
            refreshDrawingColors()
            showShared(sharedControls.colorController(), title: "Colors")
        case .showContextualPalette: openPalette()
        default: break
        }
    }
    private func toggleEraser() {
        viewport.canvas.cancelStroke()
        viewport.canvas.mode = .paint
        if viewport.canvas.tool == .eraser { viewport.canvas.tool = previousTool }
        else { previousTool = viewport.canvas.tool; viewport.canvas.tool = .eraser }
        toolPicker.selectedSegmentIndex = CanvasView.Tool.allCases.firstIndex(of: viewport.canvas.tool) ?? 1
        updateToolValue()
    }
    private let dropSwatch = UIView()
    private weak var quickShelf: UIView?
    func dragSwatch(_ point: CGPoint, color: UIColor, ended: Bool, cancelled: Bool) {
        guard let quickShelf else { return }
        showDrop(at: quickShelf.convert(point, to: view), color: color, ended: ended, cancelled: cancelled)
    }
    private func showDrop(at point: CGPoint, color: UIColor, ended: Bool, cancelled: Bool) {
        if cancelled || ended {
            dropSwatch.removeFromSuperview()
            if ended && !cancelled && viewport.bounds.contains(viewport.convert(point, from: view)) {
                let local = viewport.canvas.convert(point, from: view)
                if document.bounds.contains(local) { fill(at: local, swatch: color) }
            }
            return
        }
        if dropSwatch.superview == nil {
            dropSwatch.frame.size = CGSize(width: 28, height: 28)
            dropSwatch.layer.cornerRadius = 14; dropSwatch.layer.borderColor = UIColor.white.cgColor
            dropSwatch.layer.borderWidth = 2; dropSwatch.isUserInteractionEnabled = false
            dropSwatch.backgroundColor = color; view.addSubview(dropSwatch)
        }
        dropSwatch.center = point
    }
    @objc private func dropColor(_ gesture: UIPanGestureRecognizer) {
        showDrop(at: gesture.location(in: view), color: viewport.canvas.brushColor, ended: gesture.state == .ended,
            cancelled: gesture.state == .cancelled || gesture.state == .failed)
    }
    private weak var toolsButton: UIButton?
    private func updateToolValue() {
        toolsButton?.accessibilityValue = viewport.canvas.mode == .paint ? viewport.canvas.tool.rawValue : viewport.canvas.mode.rawValue
    }
    private var holdTools: Bool { UserDefaults.standard.object(forKey: "holdDrawingTools") as? Bool ?? true }

    private func openPalette() {
        guard let anchor = toolsButton else { return }
        viewport.canvas.cancelStroke()
        palette?.removeFromSuperview()
        let palette = ToolPalette(frame: view.bounds)
        palette.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        self.palette = palette
        view.addSubview(palette)
        palette.anchor = anchor
        palette.beside = sideDock
        palette.onClose = { [weak self] in self?.palette?.removeFromSuperview(); self?.palette = nil }
        palette.add("Eraser", "eraser") { [weak self] in
            guard let self else { return }
            if self.viewport.canvas.tool != .eraser { self.toggleEraser() }
            else { self.viewport.canvas.mode = .paint }
        }
        let modes: [(CanvasMode, String)] = [(.lasso, "lasso"), (.smudge, "drop"), (.lassoFill, "paintbrush.fill"),
            (.ribbon, "scribble.variable"), (.colorPush, "arrow.up.and.down.and.arrow.left.and.right"),
            (.pixel, "pencil"), (.pixelLine, "line.diagonal"), (.dither, "circle.grid.3x3.fill"), (.fill, "paintbrush")]
        for (mode, icon) in modes {
            palette.add(mode.rawValue, icon) { [weak self] in self?.activateMode(mode) }
        }
        palette.add("Colorize mask", "paintpalette") { [weak self] in self?.colorizeMenu() }
        palette.add("Blur & bloom", "sun.max") { [weak self] in self?.effectMenu() }
        palette.add("Brush aids", "ruler") { [weak self] in self?.brushAids() }
        palette.add("Adjust tip", "slider.horizontal.3") { [weak self] in self?.brushSettings() }
        palette.add("Reselect previous area", "arrow.uturn.backward.circle") { [weak self] in
            guard let self else { return }
            guard self.document.restoreSelection() else { self.showError(DrawingError.message("Make a selection in this drawing first.")); return }
            self.activateMode(.lasso)
            self.viewport.canvas.refreshSelection()
        }
        palette.add("Transform", "perspective") { [weak self] in self?.showTransformEditor() }
        palette.add("More tools", "ellipsis") { [weak self] in self?.showTools() }
        palette.layoutIfNeeded()
    }

    func activateMode(_ mode: CanvasMode) {
        if mode == .transform { showTransformEditor(); return }
        viewport.canvas.cancelStroke()
        if viewport.canvas.tool == .eraser { toggleEraser() }
        viewport.canvas.mode = mode
        if mode != .paint { toolPicker.selectedSegmentIndex = UISegmentedControl.noSegment }
        updateToolValue()
        if [.pixel, .pixelLine, .dither].contains(mode) { viewport.canvas.brushSize = 1 }
    }

    @objc private func trackTools(_ recognizer: UILongPressGestureRecognizer) {
        switch recognizer.state {
        case .began: openPalette()
        case .changed: palette?.track(recognizer.location(in: palette))
        case .ended: palette?.finish(recognizer.location(in: palette))
        case .cancelled, .failed: palette?.onClose?()
        default: break
        }
    }
    private func updateHistory() { undoButton.isEnabled = document.canUndo; redoButton.isEnabled = document.canRedo }
    func changed(redraw: Bool = true) {
        recorder.capture(document)
        if viewport.canvas.bounds.size != document.bounds.size {
            viewport.setZoomScale(1, animated: false)
            viewport.canvas.bounds = document.bounds; viewport.contentSize = document.bounds.size; viewport.fit()
        }
        if redraw { viewport.canvas.refresh() }
        updateHistory(); saveLabel.text = "Unsaved changes"
        saveWork?.cancel()
        let work = DispatchWorkItem { [weak self] in self?.saveNow() }
        saveWork = work; DispatchQueue.main.asyncAfter(deadline: .now() + 0.8, execute: work)
    }
    @objc private func backgrounded() {
        viewport.canvas.stopLiveDrawing()
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
        refreshDrawingColors()
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
    private func export(transparent: Bool = false) {
        viewport.canvas.cancelStroke()
        let snapshot = document.snapshot()
        store.queue.async {
            let result = Result { try self.store.exportPNG(snapshot, transparent: transparent) }
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
        let layers = LayersController(document: document, sharedControls: sharedControls)
        layers.onChange = { [weak self] in self?.changed() }
        let navigation = UINavigationController(rootViewController: layers)
        navigation.modalPresentationStyle = .pageSheet
        navigation.sheetPresentationController?.detents = [.medium(), .large()]
        present(navigation, animated: true)
    }
    private func brushSettings() {
        viewport.canvas.cancelStroke()
        showShared(sharedControls.brushController(), title: "Brush settings")
    }
    func showShared(_ controller: UIViewController, title: String) {
        controller.title = title
        controller.navigationItem.rightBarButtonItem = UIBarButtonItem(systemItem: .done,
            primaryAction: UIAction { [weak controller] _ in controller?.dismiss(animated: true) })
        let navigation = UINavigationController(rootViewController: controller)
        navigation.modalPresentationStyle = .pageSheet
        navigation.sheetPresentationController?.detents = [.large()]
        navigation.sheetPresentationController?.prefersGrabberVisible = true
        present(navigation, animated: true)
    }
    func applyBrush(_ brush: NativeBrush) {
        viewport.canvas.cancelStroke()
        let keepEraser = viewport.canvas.tool == .eraser && viewport.canvas.brush.id == brush.id
        viewport.canvas.brush = brush
        viewport.canvas.brushSize = CGFloat(brush.size)
        viewport.canvas.brushOpacity = CGFloat(brush.opacity)
        viewport.canvas.tool = keepEraser ? .eraser : (CanvasView.Tool.allCases.first { $0.brushID == brush.id } ?? .ink)
        toolPicker.selectedSegmentIndex = CanvasView.Tool.allCases.firstIndex(of: viewport.canvas.tool) ?? 1
        updateToolValue()
        if viewport.canvas.tool != .eraser { previousTool = viewport.canvas.tool }
        let defaults = UserDefaults.standard
        defaults.set(brush.id, forKey: "brush.id"); defaults.set(brush.size, forKey: "brush.size")
        defaults.set(brush.opacity, forKey: "brush.opacity"); defaults.set(sharedControls.brushData(), forKey: "brush.data")
    }
    private func importImage() {
        viewport.canvas.cancelStroke()
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.image], asCopy: true)
        picker.delegate = self
        present(picker, animated: true)
    }
    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        importingMedia = false; importingBrush = false; importingBrushTexture = nil
    }
    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard let url = urls.first else { return }
        if importingMedia { importingMedia = false; addMediaFile(url); return }
        if importingBrush { importingBrush = false; importBrushFile(url); return }
        if let grain = importingBrushTexture { importingBrushTexture = nil; importBrushTexture(url, grain: grain); return }
        // Read the original and prepare its bounded display cache off the input thread.
        view.isUserInteractionEnabled = false
        store.queue.async {
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            let result = Result { try PhotoSource(file: url) }
            DispatchQueue.main.async {
                self.view.isUserInteractionEnabled = true
                do { try self.document.importPhoto(result.get(), name: url.deletingPathExtension().lastPathComponent); self.changed(); self.showTransformEditor() }
                catch { self.showError(error) }
            }
        }
    }
    override var keyCommands: [UIKeyCommand]? {
        [UIKeyCommand(input: "z", modifierFlags: .command, action: #selector(keyUndo)),
         UIKeyCommand(input: "z", modifierFlags: [.command, .shift], action: #selector(keyRedo)),
         UIKeyCommand(input: "0", modifierFlags: .command, action: #selector(keyFit))]
    }
    @objc private func keyUndo() { viewport.canvas.cancelStroke(); document.undo(); changed() }
    @objc private func keyRedo() { viewport.canvas.cancelStroke(); document.redo(); changed() }
    @objc private func keyFit() { viewport.fit() }

}

private extension NSLayoutConstraint {
    func withPriority(_ priority: UILayoutPriority) -> NSLayoutConstraint { self.priority = priority; return self }
}

// Kotlin retains this adapter; the adapter must not retain the editor in return.
private final class EditorActions: NSObject, AppleEditorActions {
    weak var editor: EditorController?
    init(_ editor: EditorController) { self.editor = editor }
    func brushChanged(brush: NativeBrush) { editor?.applyBrush(brush) }
    func colorChanged(red: Float, green: Float, blue: Float) {
        editor?.viewport.canvas.brushColor = UIColor(red: CGFloat(red), green: CGFloat(green), blue: CGFloat(blue), alpha: 1)
    }
    func swatchDrag(x: Float, y: Float, red: Float, green: Float, blue: Float, ended: Bool, cancelled: Bool) {
        let color = UIColor(red: CGFloat(red), green: CGFloat(green), blue: CGFloat(blue), alpha: 1)
        editor?.dragSwatch(CGPoint(x: CGFloat(x), y: CGFloat(y)), color: color, ended: ended, cancelled: cancelled)
    }
    func fingerDrawingChanged(enabled: Bool) {
        editor?.viewport.canvas.fingerDrawing = enabled
        UserDefaults.standard.set(enabled, forKey: "fingerDrawing")
    }
}
