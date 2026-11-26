import UIKit
import PhotosUI
import TipStrokeShared
import UniformTypeIdentifiers
import ImageIO

final class EditorController: UIViewController, UIPencilInteractionDelegate, UIDocumentPickerDelegate, PHPickerViewControllerDelegate, UIViewControllerTransitioningDelegate {
    let document: RasterDocument
    let viewport: CanvasViewport
    private let store = ProjectStore.shared
    private lazy var recorder = ProcessRecorder(store: store, id: document.record.id)
    private var saveWork: DispatchWorkItem?
    private var saving = false
    private var pending: DocumentSnapshot?
    private var closeAfterSave = false
    private var saveFailure: String?
    private let saveStatus = UIButton(type: .system)
    private var backgroundTask: UIBackgroundTaskIdentifier = .invalid
    private weak var colorButton: UIButton?
    private let undoButton = UIButton(type: .system)
    private let redoButton = UIButton(type: .system)
    private let adjustButton = UIButton(type: .system)
    private let dock = UIStackView()
    private weak var adjustmentPanel: UIView?
    private weak var quickPanel: UIView?
    private let fingerMode = UIButton(type: .system)
    private var gridSize: CGFloat = 1
    private let sizeControl = BrushAdjustmentView(title: "Size", isSize: true)
    private let opacityControl = BrushAdjustmentView(title: "Opacity", isSize: false)
    private lazy var adjustments = UIStackView(arrangedSubviews: [sizeControl, opacityControl])
    private let adjustmentPreview = UIImageView()
    private var palette: ToolPalette?
    private var portraitLayout: [NSLayoutConstraint] = []
    private var landscapeLayout: [NSLayoutConstraint] = []
    private var tabletPortraitLayout: [NSLayoutConstraint] = []
    private var tabletLandscapeLayout: [NSLayoutConstraint] = []
    private var tabletControls = false
    private var phoneAdjustmentsOpen = false
    private var activeDockLayout: [NSLayoutConstraint] {
        if tabletControls { return sideDock ? tabletLandscapeLayout : tabletPortraitLayout }
        return sideDock ? landscapeLayout : portraitLayout
    }
    private var commandStacks: [UIStackView] = []
    private var horizontalCommands: [NSLayoutConstraint] = []
    private var verticalCommands: [NSLayoutConstraint] = []
    private var landscapeEdges: [NSLayoutConstraint] = []
    private weak var tipPanel: UIView?
    private var landscapeTipHeight: NSLayoutConstraint?
    private weak var pixelTipShortcut: UIView?
    private var leftTips = false
    private var reversedRails = false
    private var topButtonWidths: [NSLayoutConstraint] = []
    private var commandPanels: [UIView] = []
    private weak var compactBar: UIView?
    private var compactCommands = false
    private var inlineShelf = false
    private var inlineShelfLayout: [NSLayoutConstraint] = []
    private var popupShelfLayout: [NSLayoutConstraint] = []
    private var wideActions: [UIButton] = []
    var timelinePanel: UINavigationController?
    private var navigator: CanvasNavigator?
    private var panels: [UIVisualEffectView] = []
    private var sideDock = false
    private let hoverName = UILabel()
    private var hoverWork: DispatchWorkItem?

    var brushLibrarySlot: Int32 = -1
    lazy var sharedControls = AppleEditorBridge(actions: EditorActions(self))
    var fillPending = false
    var closingEditor: Bool { closeAfterSave }
    var exporting = false
    var importingMedia = false
    // Read the provider's temporary file in its callback, then apply the edit on the main thread.
    private weak var imageFilePicker: UIDocumentPickerViewController?
    private var readPickedPhoto: ((URL, String) throws -> (() throws -> Void))?
    private var previousTool: CanvasView.Tool = .ink

    init(document: RasterDocument) {
        self.document = document; viewport = CanvasViewport(document: document)
        super.init(nibName: nil, bundle: nil)
    }
    required init?(coder: NSCoder) { fatalError("Use init(document:)") }
    override func viewDidLoad() {
        super.viewDidLoad()
        document.onCelReady = { [weak self] in self?.viewport.canvas.refresh(); self?.navigator?.refresh(); self?.updateHistory() }
        document.onDeferredHistoryChange = { [weak self] in self?.changed() }
        document.onCelError = { [weak self] error in self?.showError(error) }
        if let animation = document.record.animation, animation.onionSkin {
            document.prepareFrame(animation.selectedIndex) { [weak self] in self?.viewport.canvas.refresh() }
        }
        view.backgroundColor = .secondarySystemBackground
        viewport.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(viewport)
        let back = button("Gallery", symbol: "arrow.left") { [weak self] in self?.close() }
        configure(undoButton, label: "Undo", symbol: "arrow.uturn.backward") { [weak self] in
            guard let self else { return }; self.finishCanvasInteraction(); self.document.undo(); self.changed()
        }
        configure(redoButton, label: "Redo", symbol: "arrow.uturn.forward") { [weak self] in
            guard let self else { return }; self.finishCanvasInteraction(); self.document.redo(); self.changed()
        }
        let layers = button("Layers", symbol: "square.3.layers.3d") { [weak self] in self?.showLayers() }
        let share = button("More canvas actions", symbol: "ellipsis") { [weak self] in self?.openCanvasActions() }
        moreButton = share
        let color = button("Brush color", symbol: "circle.lefthalf.filled") { [weak self] in
            guard let self else { return }; self.finishCanvasInteraction()
            self.refreshDrawingColors()
            self.showShared(self.sharedControls.colorController(), title: "Colors")
        }
        colorButton = color
        updateColorSwatch(viewport.canvas.brushColor)
        color.addGestureRecognizer(UIPanGestureRecognizer(target: self, action: #selector(dropColor(_:))))
        color.accessibilityHint = "Tap to change color or drag to fill a region."
        configure(adjustButton, label: "Show size and opacity", symbol: "slider.horizontal.3") { [weak self] in
            guard let self, let adjustmentPanel = self.adjustmentPanel else { return }
            if self.tabletControls { self.brushSettings(); return }
            self.phoneAdjustmentsOpen.toggle()
            adjustmentPanel.isHidden = !self.phoneAdjustmentsOpen
            self.adjustButton.isSelected = !adjustmentPanel.isHidden
            self.adjustButton.accessibilityLabel = adjustmentPanel.isHidden ? "Show size and opacity" : "Hide size and opacity"
        }
        let tools = button("Drawing tools", symbol: "eraser") { [weak self] in
            guard let self else { return }
            if self.viewport.canvas.tool == .eraser { self.showTipPicker(eraser: true) }
            else if self.holdTools { self.toggleEraser() }
            else { self.openPalette() }
        }
        tools.accessibilityHint = "Tap for the default action or open the drawing tool palette."
        tools.accessibilityCustomActions = [UIAccessibilityCustomAction(name: "Open drawing tools") { [weak self] _ in
            self?.openPalette(); return true
        }]
        tools.addGestureRecognizer(UILongPressGestureRecognizer(target: self, action: #selector(trackTools(_:))))
        toolsButton = tools
        let movingTools = button("Moving tools", symbol: "arrow.up.and.down.and.arrow.left.and.right") { [weak self] in
            self?.openPalette(moving: true)
        }
        movingTools.accessibilityHint = "Open selection and transform tools."
        movingTools.addGestureRecognizer(UILongPressGestureRecognizer(target: self, action: #selector(trackTools(_:))))
        movingToolsButton = movingTools
        updateToolValue()
        let pinned = sharedControls.pinsController(callbacks: self)
        addChild(pinned)
        let quickColors = sharedControls.quickColorsController()
        quickShelf = quickColors.view
        addChild(quickColors)
        quickColors.view.heightAnchor.constraint(equalToConstant: 44).isActive = true
        configure(fingerMode, label: "Finger mode", symbol: "hand.draw") { [weak self] in
            guard let self else { return }
            self.finishCanvasInteraction()
            let enable = !self.viewport.canvas.fingerDrawing || FingerBinding.drag.command == .navigate
            if enable && FingerBinding.drag.command == .navigate { FingerBinding.drag.set(.draw) }
            self.sharedControls.setFingerDrawing(enabled: enable)
        }
        let fit = button("Fit canvas", symbol: "scope") { [weak self] in self?.viewport.fit() }
        let export = button("Export image", symbol: "square.and.arrow.down") { [weak self] in self?.openExportActions() }
        let resize = button("Resize canvas", symbol: "crop") { [weak self] in self?.resizeCanvas() }
        let animate = button("Animate", symbol: "film") { [weak self] in self?.showTimeline() }
        wideActions = [export, resize, animate]
        let history = [back, undoButton, redoButton, fit]
        let actions = [fingerMode, color, layers, export, resize, animate, share]
        for item in history + actions {
            let width = item.widthAnchor.constraint(equalToConstant: 44)
            width.priority = .defaultHigh
            width.isActive = true
            topButtonWidths.append(width)
        }
        let compactBar = panel(UIStackView())
        self.compactBar = compactBar
        compactBar.isUserInteractionEnabled = false
        commandStacks = [UIStackView(arrangedSubviews: history), UIStackView(arrangedSubviews: actions)]
        let historyPanel = panel(commandStacks[0])
        let actionsPanel = panel(commandStacks[1])
        commandPanels = [historyPanel, actionsPanel]
        let quickPanel = panel(UIStackView(arrangedSubviews: [quickColors.view!]))
        quickPanel.isHidden = true
        self.quickPanel = quickPanel
        adjustments.isLayoutMarginsRelativeArrangement = true
        adjustments.directionalLayoutMargins = NSDirectionalEdgeInsets(top: 8, leading: 4, bottom: 8, trailing: 4)
        adjustments.distribution = .fillEqually
        adjustments.spacing = 8
        sizeControl.adjusted = { [weak self] value in
            self?.viewport.canvas.brushSize = CGFloat(value)
            if self?.pixelTool == true { self?.gridSize = CGFloat(value) }
            self?.previewAdjustment()
        }
        opacityControl.adjusted = { [weak self] value in
            self?.viewport.canvas.brushOpacity = CGFloat(value)
            self?.previewAdjustment()
        }
        sizeControl.screenUnitsChanged = { [weak self] enabled in
            self?.viewport.canvas.sizeInScreenPoints = enabled
            UserDefaults.standard.set(enabled, forKey: "brush.screenPoints")
            self?.refreshAdjustments()
        }
        sizeControl.finished = { [weak self] in self?.finishAdjustment() }
        opacityControl.finished = { [weak self] in self?.finishAdjustment() }
        adjustmentPreview.isUserInteractionEnabled = false
        adjustmentPreview.layer.shadowOpacity = 0.4
        adjustmentPreview.layer.shadowRadius = 1
        adjustmentPreview.layer.shadowOffset = .zero
        let pixelBrushes = [PixelTool.pencil, .line, .dither].map { NativeBrush(preset: $0.preset) }
        let pixelShortcut = tipFolder(tips: pixelBrushes, label: "Pixel tips", title: "Pixel tips")
        pixelTipShortcut = pixelShortcut
        let shortcutHeight = pixelShortcut.heightAnchor.constraint(equalToConstant: 44)
        shortcutHeight.priority = .defaultHigh
        shortcutHeight.isActive = true
        let shortcutWidth = pixelShortcut.widthAnchor.constraint(equalToConstant: 44)
        shortcutWidth.priority = .defaultHigh
        shortcutWidth.isActive = true
        let updatePixelShortcut: (CGFloat) -> Void = { [weak pixelShortcut] zoom in
            pixelShortcut?.isHidden = !PixelTool.companion.showsShortcut(zoom: Float(zoom))
        }
        viewport.onZoom = updatePixelShortcut
        updatePixelShortcut(viewport.zoomScale)
        dock.spacing = 2
        for item in [pinned.view!, tools, pixelShortcut, movingTools, adjustButton] { dock.addArrangedSubview(item) }
        pinned.didMove(toParent: self)
        quickColors.didMove(toParent: self)
        let bottomPanel = panel(dock)
        tipPanel = bottomPanel
        let adjustmentPanel = panel(adjustments)
        adjustmentPanel.isHidden = true
        self.adjustmentPanel = adjustmentPanel
        let safe = view.safeAreaLayoutGuide
        NSLayoutConstraint.activate([
            viewport.leadingAnchor.constraint(equalTo: view.leadingAnchor), viewport.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            viewport.topAnchor.constraint(equalTo: view.topAnchor), viewport.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])
        horizontalCommands = [
            historyPanel.leadingAnchor.constraint(equalTo: safe.leadingAnchor, constant: 4),
            historyPanel.topAnchor.constraint(equalTo: safe.topAnchor, constant: 4),
            actionsPanel.trailingAnchor.constraint(equalTo: safe.trailingAnchor, constant: -4),
            actionsPanel.topAnchor.constraint(equalTo: historyPanel.topAnchor),
            historyPanel.trailingAnchor.constraint(lessThanOrEqualTo: actionsPanel.leadingAnchor, constant: -4),
            compactBar.leadingAnchor.constraint(equalTo: historyPanel.leadingAnchor),
            compactBar.trailingAnchor.constraint(equalTo: actionsPanel.trailingAnchor),
            compactBar.topAnchor.constraint(equalTo: actionsPanel.topAnchor),
            compactBar.bottomAnchor.constraint(equalTo: actionsPanel.bottomAnchor),
        ]
        NSLayoutConstraint.activate(horizontalCommands)
        verticalCommands = [
            actionsPanel.centerXAnchor.constraint(equalTo: historyPanel.centerXAnchor),
            compactBar.centerYAnchor.constraint(equalTo: safe.centerYAnchor),
            compactBar.leadingAnchor.constraint(equalTo: historyPanel.leadingAnchor),
            compactBar.trailingAnchor.constraint(equalTo: historyPanel.trailingAnchor),
        ]
        inlineShelfLayout = [
            quickPanel.trailingAnchor.constraint(equalTo: actionsPanel.leadingAnchor, constant: -4),
            quickPanel.centerYAnchor.constraint(equalTo: actionsPanel.centerYAnchor),
        ]
        popupShelfLayout = [
            quickPanel.trailingAnchor.constraint(equalTo: actionsPanel.trailingAnchor),
            quickPanel.topAnchor.constraint(equalTo: actionsPanel.bottomAnchor, constant: 4),
        ]
        NSLayoutConstraint.activate(popupShelfLayout)
        compactBar.isHidden = true
        portraitLayout = [
            adjustmentPanel.centerXAnchor.constraint(equalTo: bottomPanel.centerXAnchor),
            adjustmentPanel.bottomAnchor.constraint(equalTo: bottomPanel.topAnchor, constant: -8),
            adjustmentPanel.widthAnchor.constraint(equalTo: bottomPanel.widthAnchor, constant: -8),
            dock.heightAnchor.constraint(equalToConstant: 48),
            bottomPanel.centerXAnchor.constraint(equalTo: safe.centerXAnchor), bottomPanel.bottomAnchor.constraint(equalTo: safe.bottomAnchor, constant: -4),
            bottomPanel.widthAnchor.constraint(lessThanOrEqualToConstant: 480), bottomPanel.leadingAnchor.constraint(greaterThanOrEqualTo: safe.leadingAnchor, constant: 4),
            bottomPanel.trailingAnchor.constraint(lessThanOrEqualTo: safe.trailingAnchor, constant: -4),
            bottomPanel.widthAnchor.constraint(equalTo: safe.widthAnchor, constant: -12).withPriority(.defaultHigh),
        ]
        let tipHeight = bottomPanel.heightAnchor.constraint(equalToConstant: 0)
        landscapeTipHeight = tipHeight
        landscapeLayout = [
            bottomPanel.centerYAnchor.constraint(equalTo: safe.centerYAnchor),
            adjustmentPanel.centerYAnchor.constraint(equalTo: bottomPanel.centerYAnchor),
            adjustmentPanel.widthAnchor.constraint(equalToConstant: 164),
            dock.widthAnchor.constraint(equalToConstant: 48),
            tipHeight,
        ]
        tabletPortraitLayout = [
            bottomPanel.leadingAnchor.constraint(equalTo: safe.leadingAnchor, constant: 12),
            bottomPanel.bottomAnchor.constraint(equalTo: safe.bottomAnchor, constant: -12),
            bottomPanel.widthAnchor.constraint(equalTo: safe.widthAnchor, multiplier: 0.47).withPriority(.defaultHigh),
            bottomPanel.widthAnchor.constraint(lessThanOrEqualToConstant: 440),
            adjustmentPanel.trailingAnchor.constraint(equalTo: safe.trailingAnchor, constant: -12),
            adjustmentPanel.bottomAnchor.constraint(equalTo: bottomPanel.bottomAnchor),
            adjustmentPanel.leadingAnchor.constraint(greaterThanOrEqualTo: bottomPanel.trailingAnchor, constant: 8),
            adjustmentPanel.widthAnchor.constraint(equalToConstant: 380).withPriority(.defaultHigh),
            dock.heightAnchor.constraint(equalToConstant: 48),
        ]
        tabletLandscapeLayout = [
            bottomPanel.leadingAnchor.constraint(equalTo: safe.leadingAnchor, constant: 20),
            bottomPanel.centerYAnchor.constraint(equalTo: safe.centerYAnchor, constant: 24),
            bottomPanel.heightAnchor.constraint(equalTo: safe.heightAnchor, constant: -110),
            dock.widthAnchor.constraint(equalToConstant: 48),
            adjustmentPanel.trailingAnchor.constraint(equalTo: safe.trailingAnchor, constant: -20),
            adjustmentPanel.centerYAnchor.constraint(equalTo: bottomPanel.centerYAnchor),
            adjustmentPanel.widthAnchor.constraint(equalToConstant: 178),
        ]
        for action in [tools, movingTools, adjustButton] {
            let width = action.widthAnchor.constraint(equalToConstant: 48)
            let height = action.heightAnchor.constraint(equalToConstant: 48)
            portraitLayout.append(width)
            tabletPortraitLayout.append(width)
            landscapeLayout.append(height)
            tabletLandscapeLayout.append(height)
        }
        view.bringSubviewToFront(quickPanel)
        NSLayoutConstraint.activate(portraitLayout)
        quickPanel.widthAnchor.constraint(equalToConstant: 208).isActive = true
        saveStatus.translatesAutoresizingMaskIntoConstraints = false
        saveStatus.configuration = .filled()
        saveStatus.setTitle("Unsaved changes · Retry", for: .normal)
        saveStatus.isHidden = true
        saveStatus.addAction(UIAction { [weak self] _ in self?.showSaveRecovery(leaving: false) }, for: .touchUpInside)
        view.addSubview(saveStatus)
        NSLayoutConstraint.activate([
            saveStatus.centerXAnchor.constraint(equalTo: safe.centerXAnchor),
            saveStatus.topAnchor.constraint(equalTo: safe.topAnchor, constant: 58)
        ])
        let hint = UIButton(type: .system)
        hint.configuration = .tinted(); hint.titleLabel?.font = .preferredFont(forTextStyle: .caption1)
        hint.translatesAutoresizingMaskIntoConstraints = false; hint.isHidden = true
        hint.addAction(UIAction { [weak self] _ in self?.finishCanvasInteraction() }, for: .touchUpInside)
        view.addSubview(hint)
        NSLayoutConstraint.activate([
            hint.topAnchor.constraint(equalTo: safe.topAnchor, constant: 55),
            hint.centerXAnchor.constraint(equalTo: safe.centerXAnchor),
            hint.widthAnchor.constraint(lessThanOrEqualTo: safe.widthAnchor, constant: -16)
        ])
        let deselect = button("Deselect", symbol: "xmark") { [weak self] in
            guard let self else { return }
            self.finishCanvasInteraction()
            self.document.selection = nil
            self.viewport.canvas.refreshSelection()
        }
        deselect.backgroundColor = .secondarySystemBackground
        deselect.layer.cornerRadius = 10
        deselect.translatesAutoresizingMaskIntoConstraints = false
        deselect.isHidden = document.selection == nil
        view.addSubview(deselect)
        NSLayoutConstraint.activate([
            deselect.trailingAnchor.constraint(equalTo: safe.trailingAnchor, constant: -8),
            deselect.topAnchor.constraint(equalTo: safe.topAnchor, constant: 56)
        ])
        document.onSelectionPresenceChanged = { [weak deselect] selected in deselect?.isHidden = !selected }
        viewport.canvas.onInteractionHint = { [weak hint] text in hint?.setTitle(text, for: .normal); hint?.isHidden = text == nil }
        viewport.canvas.onCommit = { [weak self] in self?.sharedControls.recordColor(); self?.changed(redraw: false) }
        viewport.canvas.onPickColor = { [weak self] color in
            var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
            color.getRed(&r, green: &g, blue: &b, alpha: &a)
            self?.updateColorSwatch(color)
            self?.sharedControls.setColor(red: Float(r), green: Float(g), blue: Float(b))
        }
        viewport.canvas.onFill = { [weak self] point in self?.fill(at: point) }
        viewport.canvas.onError = { [weak self] error in self?.showError(error) }
        configureFingerGestures()
        let pencil = UIPencilInteraction(); pencil.delegate = self; viewport.addInteraction(pencil)
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
        if max(document.record.width, document.record.height) <= 128 { activateMode(.pixel) }
        refreshDrawingColors()
        recorder.capture(document)
        updateHistory()
    }
    override func viewWillLayoutSubviews() {
        super.viewWillLayoutSubviews()
        let safe = view.safeAreaLayoutGuide.layoutFrame
        // Keep both command groups on screen in narrow split views as well.
        let phoneSide = safe.width > safe.height && min(safe.width, safe.height) < 600
        let buttonWidth = min(44, max(1, ((phoneSide ? safe.height : safe.width) - 44) / 8))
        for width in topButtonWidths where width.constant != buttonWidth { width.constant = buttonWidth }
        let compact = min(safe.width, safe.height) < 600
        if compact != compactCommands {
            compactCommands = compact
            compactBar?.isHidden = !compact
            for action in wideActions { action.isHidden = compact }
            refreshPanelStyle()
        }
        let inline = !phoneSide && safe.width >= 900
        if inline != inlineShelf {
            NSLayoutConstraint.deactivate(inlineShelf ? inlineShelfLayout : popupShelfLayout)
            inlineShelf = inline
            NSLayoutConstraint.activate(inline ? inlineShelfLayout : popupShelfLayout)
            quickPanel?.isHidden = !inline
        }
        let useSideDock = safe.width > safe.height
        let useTabletControls = min(safe.width, safe.height) >= 600
        if useSideDock {
            let height = min(205, max(50, safe.height - 170))
            sizeControl.setTrackLength(height); opacityControl.setTrackLength(height)
        }
        if phoneSide {
            // Three fixed 48-point actions and an optional 44-point pixel shortcut.
            // Include the 2-point stack gaps and the panel's top and bottom inset.
            let fixed: CGFloat = 3 * 50 + (pixelTipShortcut?.isHidden == false ? 46 : 0) + 2
            let available = min(480, safe.height - 8)
            let visiblePins = min(CGFloat(sharedControls.dockTipCount), max(0, floor((available - fixed) / 50)))
            let height = min(available, fixed + visiblePins * 50)
            if landscapeTipHeight?.constant != height { landscapeTipHeight?.constant = height }
        }
        let tipsOnLeft = phoneSide && view.window?.windowScene?.interfaceOrientation == .landscapeLeft
        guard useSideDock != sideDock || useTabletControls != tabletControls || leftTips != tipsOnLeft else { return }
        NSLayoutConstraint.deactivate(horizontalCommands + verticalCommands + landscapeEdges)
        leftTips = tipsOnLeft
        let reverse = phoneSide && !leftTips
        if reversedRails != reverse {
            reversedRails = reverse
            for stack in commandStacks + [dock] {
                let children = Array(stack.arrangedSubviews.reversed())
                children.forEach { stack.removeArrangedSubview($0); stack.addArrangedSubview($0) }
            }
        }
        commandStacks.forEach { $0.axis = phoneSide ? .vertical : .horizontal }
        if phoneSide, let tipPanel, let adjustmentPanel, let compactBar, let history = commandPanels.first {
            let guide = view.safeAreaLayoutGuide
            let actions = commandPanels[1]
            let first = reverse ? actions : history
            let last = reverse ? history : actions
            landscapeEdges = [
                first.topAnchor.constraint(equalTo: compactBar.topAnchor),
                last.topAnchor.constraint(equalTo: first.bottomAnchor),
                last.bottomAnchor.constraint(equalTo: compactBar.bottomAnchor),
                leftTips ? tipPanel.leadingAnchor.constraint(equalTo: guide.leadingAnchor, constant: 4)
                         : tipPanel.trailingAnchor.constraint(equalTo: guide.trailingAnchor, constant: -4),
                leftTips ? history.trailingAnchor.constraint(equalTo: guide.trailingAnchor, constant: -4)
                         : history.leadingAnchor.constraint(equalTo: guide.leadingAnchor, constant: 4),
                leftTips ? adjustmentPanel.leadingAnchor.constraint(equalTo: tipPanel.trailingAnchor, constant: 6)
                         : adjustmentPanel.trailingAnchor.constraint(equalTo: tipPanel.leadingAnchor, constant: -6),
            ]
            if let quickPanel {
                NSLayoutConstraint.deactivate(popupShelfLayout)
                landscapeEdges += [
                    leftTips ? quickPanel.trailingAnchor.constraint(equalTo: history.leadingAnchor, constant: -4)
                             : quickPanel.leadingAnchor.constraint(equalTo: history.trailingAnchor, constant: 4),
                    quickPanel.centerYAnchor.constraint(equalTo: guide.centerYAnchor),
                ]
            }
            NSLayoutConstraint.activate(verticalCommands + landscapeEdges)
        } else {
            NSLayoutConstraint.activate(horizontalCommands)
            if !inline { NSLayoutConstraint.activate(popupShelfLayout) }
        }
        NSLayoutConstraint.deactivate(activeDockLayout)
        tabletControls = useTabletControls
        adjustmentPanel?.isHidden = !tabletControls && !phoneAdjustmentsOpen
        adjustButton.isSelected = !tabletControls && phoneAdjustmentsOpen
        adjustButton.accessibilityLabel = tabletControls ? "Adjust tip" : (phoneAdjustmentsOpen ? "Hide size and opacity" : "Show size and opacity")
        sizeControl.useVerticalTrack(useSideDock)
        opacityControl.useVerticalTrack(useSideDock)
        sideDock = useSideDock
        dock.axis = useSideDock ? .vertical : .horizontal
        sharedControls.updateDockAxis(vertical: useSideDock, reverse: reverse)
        NSLayoutConstraint.activate(activeDockLayout)
    }
    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        navigator?.constrainPosition()
        if let timelinePanel {
            let safe = view.safeAreaLayoutGuide.layoutFrame.insetBy(dx: 8, dy: 4)
            let sides: CGFloat = sideDock ? 68 : 0
            let width = min(680, max(1, safe.width - 2 * sides))
            let height = min(timelinePanel.isToolbarHidden ? 122 : 166, safe.height * 0.44)
            timelinePanel.view.frame = CGRect(x: safe.midX - width / 2,
                y: safe.maxY - height - (sideDock ? 0 : 62), width: width, height: height)
        }
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        navigationController?.setNavigationBarHidden(true, animated: animated)
        navigationController?.interactivePopGestureRecognizer?.isEnabled = false
    }
    override func viewIsAppearing(_ animated: Bool) {
        super.viewIsAppearing(animated)
        let center = NotificationCenter.default
        center.removeObserver(self, name: UIScene.willDeactivateNotification, object: nil)
        if let scene = view.window?.windowScene {
            center.addObserver(self, selector: #selector(backgrounded), name: UIScene.willDeactivateNotification, object: scene)
        }
    }
    func finishCanvasInteraction() {
        viewport.canvas.stopLiveDrawing()
        viewport.canvas.cancelStroke()
    }

    override func present(_ viewControllerToPresent: UIViewController, animated flag: Bool, completion: (() -> Void)? = nil) {
        viewport.canvas.stopLiveDrawing()
        super.present(viewControllerToPresent, animated: flag, completion: completion)
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        viewport.canvas.stopLiveDrawing()
        palette?.onClose?()
        if isMovingFromParent { navigationController?.interactivePopGestureRecognizer?.isEnabled = true }
    }
    deinit { NotificationCenter.default.removeObserver(self); saveWork?.cancel() }
    func refreshFingerMode() {
        let canvas = viewport.canvas
        canvas.configureInput()
        let command: FingerCommand = canvas.fingerDrawing ? FingerBinding.drag.command : .navigate
        fingerMode.setImage(UIImage(systemName: command == .navigate ? "hand.raised" : "hand.draw"), for: .normal)
        fingerMode.accessibilityValue = command.rawValue
    }

    private var pixelTool: Bool { [.pixel, .pixelLine, .dither].contains(viewport.canvas.mode) }

    private func refreshAdjustments() {
        let canvas = viewport.canvas
        sizeControl.display(Float(canvas.brushSize), range: pixelTool ? 1...32 : 2...512,
                            screenUnits: canvas.supportsScreenSize && canvas.sizeInScreenPoints, allowsUnits: canvas.supportsScreenSize)
        opacityControl.display(Float(canvas.brushOpacity), range: 0.05...1)
    }

    private func previewAdjustment() {
        let canvas = viewport.canvas
        refreshAdjustments()
        if adjustmentPreview.superview == nil {
            let color = canvas.tool == .eraser ? UIColor.lightGray : canvas.brushColor
            if let mask = try? NativeStampRenderer.image(canvas.brush.shape(), color: color) {
                adjustmentPreview.image = UIImage(cgImage: mask)
            }
            view.addSubview(adjustmentPreview)
        }
        let width = canvas.resolvedBrushSize * viewport.zoomScale
        adjustmentPreview.bounds.size = CGSize(width: width, height: width * CGFloat(canvas.brush.aspectRatio))
        adjustmentPreview.center = viewport.convert(CGPoint(x: viewport.bounds.midX, y: viewport.bounds.midY), to: view)
        adjustmentPreview.alpha = canvas.brushOpacity
    }

    private func finishAdjustment() {
        adjustmentPreview.removeFromSuperview()
        let canvas = viewport.canvas
        let defaults = UserDefaults.standard
        if canvas.tool == .eraser {
            sharedControls.setEraserAdjustment(size: Float(canvas.brushSize), opacity: Float(canvas.brushOpacity))
        } else if !pixelTool {
            sharedControls.setDrawingAdjustment(size: Float(canvas.brushSize), opacity: Float(canvas.brushOpacity))
            defaults.set(Float(canvas.brushSize), forKey: "brush.size")
            defaults.set(Float(canvas.brushOpacity), forKey: "brush.opacity")
            defaults.set(sharedControls.brushData(), forKey: "brush.data")
        }
    }

    private func panel(_ stack: UIStackView) -> UIView {
        let panel = UIVisualEffectView()
        panels.append(panel)
        refreshPanelStyle()
        panel.layer.cornerRadius = 18; panel.clipsToBounds = true
        panel.translatesAutoresizingMaskIntoConstraints = false; stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(panel); panel.contentView.addSubview(stack)
        NSLayoutConstraint.activate([stack.leadingAnchor.constraint(equalTo: panel.contentView.leadingAnchor, constant: 8),
            stack.trailingAnchor.constraint(equalTo: panel.contentView.trailingAnchor, constant: -8),
            stack.topAnchor.constraint(equalTo: panel.contentView.topAnchor, constant: 2),
            stack.bottomAnchor.constraint(equalTo: panel.contentView.bottomAnchor, constant: -2)])
        return panel
    }
    private func refreshPanelStyle() {
        overrideUserInterfaceStyle = .dark
        view.tintColor = UIColor(red: 0.93, green: 0.41, blue: 0.35, alpha: 1)
        viewport.backgroundColor = UIColor(red: 23/255, green: 24/255, blue: 27/255, alpha: 1)
        for panel in panels {
            let embedded = compactCommands && commandPanels.contains { $0 === panel }
            panel.tintColor = .white
            panel.backgroundColor = embedded ? .clear : UIColor(red: 32/255, green: 33/255, blue: 37/255, alpha: 1)
            panel.layer.borderWidth = embedded ? 0 : 1
            panel.layer.borderColor = UIColor(white: 0.28, alpha: 1).cgColor
        }
    }

    private func toggleCanvasPreview() {
        if let navigator { navigator.removeFromSuperview(); self.navigator = nil; return }
        let preview = CanvasNavigator(viewport: viewport)
        view.insertSubview(preview, aboveSubview: viewport)
        navigator = preview
        preview.constrainPosition()
        preview.refresh()
    }

    private func button(_ label: String, symbol: String, action: @escaping () -> Void) -> UIButton {
        let button = UIButton(type: .system); configure(button, label: label, symbol: symbol, action: action); return button
    }
    private func configure(_ button: UIButton, label: String, symbol: String, action: @escaping () -> Void) {
        let icon = UIImage.SymbolConfiguration(pointSize: 21, weight: .medium)
        button.setImage(UIImage(systemName: symbol, withConfiguration: icon), for: .normal); button.accessibilityLabel = label
        button.addGestureRecognizer(UIHoverGestureRecognizer(target: self, action: #selector(hoverControl(_:))))
        button.addAction(UIAction { _ in action() }, for: .touchUpInside)
        button.heightAnchor.constraint(greaterThanOrEqualToConstant: 36).isActive = true
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
    func pencilInteractionDidTap(_ interaction: UIPencilInteraction) {
        toggleEraser()
    }
    @available(iOS 17.5, *)
    func pencilInteraction(_ interaction: UIPencilInteraction, didReceiveSqueeze squeeze: UIPencilInteraction.Squeeze) {
        guard squeeze.phase == .ended, presentedViewController == nil else { return }
        switch UIPencilInteraction.preferredSqueezeAction {
        case .switchEraser: toggleEraser()
        case .showColorPalette:
            finishCanvasInteraction()
            refreshDrawingColors()
            showShared(sharedControls.colorController(), title: "Colors")
        case .showContextualPalette: openPalette()
        default: break
        }
    }
    private func toggleEraser() {
        finishCanvasInteraction()
        if pixelTool {
            viewport.canvas.pixelErasing.toggle()
            updateToolValue()
            return
        }
        viewport.canvas.mode = .paint
        if viewport.canvas.tool == .eraser {
            viewport.canvas.tool = previousTool
            applyBrush(sharedControls.currentDrawingBrush())
        } else {
            previousTool = viewport.canvas.tool
            viewport.canvas.tool = .eraser
            activateEraserBrush()
        }
        updateToolValue()
    }
    func activateEraserBrush() {
        let tip = sharedControls.eraserBrush()
        viewport.canvas.brush = tip
        viewport.canvas.brushSize = CGFloat(tip.size)
        viewport.canvas.brushOpacity = CGFloat(tip.opacity)
        refreshAdjustments()
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
    private weak var moreButton: UIButton?

    private func actionPanel(_ title: String, anchor: UIView?) -> ToolPalette {
        palette?.removeFromSuperview()
        let panel = ToolPalette(frame: view.bounds, title: title, list: true)
        panel.anchor = anchor
        panel.beside = sideDock
        panel.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        panel.onClose = { [weak self] in self?.palette?.removeFromSuperview(); self?.palette = nil }
        palette = panel
        view.addSubview(panel)
        return panel
    }
    private func openCanvasActions() {
        let panel = actionPanel("Canvas actions", anchor: moreButton)
        panel.add(navigator == nil ? "Canvas preview" : "Hide preview", "picture.in.picture") { [weak self] in self?.toggleCanvasPreview() }
        panel.add("Quick colors", "paintpalette") { [weak self] in self?.quickPanel?.isHidden.toggle() }
        panel.add("Replay drawing progress", "play.rectangle") { [weak self] in
            guard let self else { return }
            self.finishCanvasInteraction()
            self.present(EditorSheetNavigationController(rootViewController: ProcessReplayController(folder: self.store.replayFolder(self.document.record.id))), animated: true)
        }
        if compactCommands {
            panel.add("Export", "square.and.arrow.down") { [weak self] in self?.openExportActions() }
            panel.add("Resize canvas", "crop") { [weak self] in self?.resizeCanvas() }
            panel.add("Animate", "film") { [weak self] in self?.showTimeline() }
        }
    }
    private func openExportActions() {
        let panel = actionPanel("Export", anchor: compactCommands ? moreButton : wideActions.first)
        panel.add("Image…", "photo") { [weak self] in self?.showImageExport() }
        panel.add("Animation MP4", "film") { [weak self] in self?.chooseMediaDestination { photos in self?.chooseExportTiming { self?.exportAnimationVideo(smooth: $0, photos: photos) } } }
        panel.add("PNG sequence", "square.stack") { [weak self] in self?.chooseExportTiming { self?.exportAnimation(pngSequence: true, smooth: $0) } }
        panel.add("Animation GIF", "film.stack") { [weak self] in self?.chooseMediaDestination { photos in self?.chooseExportTiming { self?.exportAnimation(smooth: $0, photos: photos) } } }

    }
    private weak var toolsButton: UIButton?
    private weak var movingToolsButton: UIButton?
    private let drawingModes: [(CanvasMode, String)] = [(.smudge, "drop"), (.lassoFill, "paintbrush.fill"),
            (.colorPush, "hand.draw"),
            (.fill, "paintbrush")]
    private let selectionModes: [(CanvasMode, String)] = [(.lasso, "lasso"), (.rectangleSelection, "rectangle.dashed"),
            (.ellipseSelection, "circle.dashed")]
    private func updateToolValue() {
        refreshAdjustments()
        let mode = viewport.canvas.mode
        let activeTip = mode.pixelTip?.tipId ?? (mode == .paint ? viewport.canvas.brush.id : nil)
        sharedControls.setDockSelection(id: viewport.canvas.tool == .eraser ? nil : activeTip)
        let drawingSymbol = drawingModes.first { $0.0 == mode }?.1 ?? (mode.isShape ? "scribble.variable" : "eraser")
        toolsButton?.setImage(UIImage(systemName: drawingSymbol), for: .normal)
        toolsButton?.accessibilityValue = mode.isSelection ? nil : (mode == .paint ? viewport.canvas.tool.rawValue : mode.rawValue)
        let movingSymbol = selectionModes.first { $0.0 == mode }?.1 ?? "arrow.up.and.down.and.arrow.left.and.right"
        movingToolsButton?.setImage(UIImage(systemName: movingSymbol), for: .normal)
        movingToolsButton?.accessibilityValue = mode.isSelection ? mode.rawValue : nil
    }
    private var holdTools: Bool { UserDefaults.standard.object(forKey: "holdDrawingTools") as? Bool ?? true }

    private func openPalette(moving: Bool = false) {
        guard let anchor = (moving ? movingToolsButton : toolsButton) else { return }
        finishCanvasInteraction()
        palette?.removeFromSuperview()
        let palette = ToolPalette(frame: view.bounds, title: moving ? "Moving tools" : "Drawing tools")
        palette.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        self.palette = palette
        view.addSubview(palette)
        palette.anchor = anchor
        palette.beside = sideDock
        palette.onClose = { [weak self] in self?.palette?.removeFromSuperview(); self?.palette = nil }
        if moving { addMovingTools(to: palette) } else { addDrawingTools(to: palette) }
        palette.layoutIfNeeded()
    }

    private func addDrawingTools(to palette: ToolPalette) {
        palette.add("Eraser", "eraser") { [weak self] in
            guard let self else { return }
            if self.viewport.canvas.tool != .eraser { self.toggleEraser() }
            else { self.viewport.canvas.mode = .paint }
        }
        for (mode, icon) in drawingModes {
            palette.add(mode.rawValue, icon) { [weak self] in self?.activateMode(mode) }
        }
        palette.add("Colorize mask", "paintpalette") { [weak self] in self?.colorizeMenu() }
        palette.add("Blur & bloom", "sun.max") { [weak self] in self?.effectMenu() }
        palette.add("Brush aids", "ruler") { [weak self] in self?.brushAids() }
        palette.add("Adjust tip", "slider.horizontal.3") { [weak self] in self?.brushSettings() }
        palette.add("More tools", "ellipsis") { [weak self] in self?.showTools() }
    }

    private func addMovingTools(to palette: ToolPalette) {
        for (mode, icon) in selectionModes {
            palette.add(mode.rawValue, icon) { [weak self] in self?.activateMode(mode) }
        }
        if document.activePhoto != nil {
            palette.add("Crop image", "crop") { [weak self] in
                guard let self else { return }
                guard let selection = self.document.selection, !selection.inverted else {
                    self.activateMode(.rectangleSelection)
                    self.viewport.canvas.onInteractionHint?("Drag a rectangle, then choose Crop image")
                    return
                }
                do {
                    try self.document.cropActivePhoto(to: selection.path.boundingBoxOfPath)
                    self.document.selection = nil
                    self.viewport.canvas.refreshSelection()
                    self.viewport.canvas.onInteractionHint?(nil)
                    self.changed()
                } catch { self.showError(error) }
            }
        }
        palette.add("Reselect previous area", "arrow.uturn.backward.circle") { [weak self] in
            guard let self else { return }
            guard self.document.restoreSelection() else { self.showError(DrawingError.message("Make a selection in this drawing first.")); return }
            self.activateMode(.lasso)
            self.viewport.canvas.refreshSelection()
        }
        palette.add("Transform", "perspective") { [weak self] in self?.showTransformEditor() }
        palette.add("Selection actions", "ellipsis") { [weak self] in self?.selectionActions() }
        palette.add(navigator == nil ? "Canvas preview" : "Hide canvas preview", "picture.in.picture") { [weak self] in self?.toggleCanvasPreview() }
        palette.add("Fit canvas", "arrow.up.left.and.arrow.down.right") { [weak self] in self?.viewport.fit() }
    }

    func activateMode(_ mode: CanvasMode) {
        if mode == .transform { showTransformEditor(); return }
        finishCanvasInteraction()
        if viewport.canvas.tool == .eraser { toggleEraser() }
        viewport.canvas.pixelErasing = false
        viewport.canvas.mode = mode
        if pixelTool { viewport.canvas.brushSize = gridSize }
        else { viewport.canvas.brushSize = CGFloat(sharedControls.currentDrawingBrush().size) }
        updateToolValue()
    }

    @objc private func trackTools(_ recognizer: UILongPressGestureRecognizer) {
        switch recognizer.state {
        case .began: openPalette(moving: recognizer.view === movingToolsButton)
        case .changed: palette?.track(recognizer.location(in: palette))
        case .ended: palette?.finish(recognizer.location(in: palette))
        case .cancelled, .failed: palette?.onClose?()
        default: break
        }
    }
    private func updateHistory() { undoButton.isEnabled = document.canUndo; redoButton.isEnabled = document.canRedo }
    func changed(redraw: Bool = true) {
        (timelinePanel?.topViewController as? TimelineController)?.refreshFrames()
        recorder.capture(document)
        if viewport.canvas.bounds.size != document.bounds.size {
            viewport.setZoomScale(1, animated: false)
            viewport.canvas.bounds = document.bounds; viewport.contentSize = document.bounds.size; viewport.fit()
        }
        if redraw { viewport.canvas.refresh() }
        navigator?.refresh()
        updateHistory()
        saveWork?.cancel()
        guard saveFailure == nil else { return }
        let work = DispatchWorkItem { [weak self] in self?.saveNow() }
        saveWork = work; DispatchQueue.main.asyncAfter(deadline: .now() + 0.8, execute: work)
    }
    @objc private func backgrounded() {
        finishCanvasInteraction()
        guard saveFailure == nil else { return }
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
        guard !viewport.canvas.isDrawing, saveFailure == nil else { return }
        refreshDrawingColors()
        // The latest snapshot replaces any pending save, bounding queued artwork retention.
        pending = document.snapshot()
        startSaveIfNeeded()
    }
    private func startSaveIfNeeded() {
        guard !saving, let snapshot = pending else { return }
        pending = nil; saving = true
        store.queue.async {
            let result = Result { try self.store.save(snapshot) }
            DispatchQueue.main.async {
                self.saving = false
                switch result {
                case .failure(let error):
                    let leaving = self.closeAfterSave
                    self.saveWork?.cancel()
                    self.pending = nil
                    self.closeAfterSave = false
                    self.saveFailure = error.localizedDescription
                    self.saveStatus.isHidden = false
                    self.view.bringSubviewToFront(self.saveStatus)
                    self.view.isUserInteractionEnabled = true
                    self.endBackgroundTask()
                    if leaving { self.showSaveRecovery(leaving: true) }
                case .success:
                    self.saveStatus.isHidden = true
                    if self.pending != nil { self.startSaveIfNeeded() }
                    else {
                        self.endBackgroundTask()
                        if self.closeAfterSave { self.navigationController?.popViewController(animated: true) }
                    }
                }
            }
        }
    }
    private func close() {
        finishCanvasInteraction()
        if saveFailure != nil { showSaveRecovery(leaving: true); return }
        closeAfterSave = true
        view.isUserInteractionEnabled = false
        saveNow()
    }
    private func showSaveRecovery(leaving: Bool) {
        guard let saveFailure, presentedViewController == nil else { return }
        let detail = saveFailure + "\n\nYour edits are still open. Leaving without saving discards changes since the last successful save."
        let alert = UIAlertController(title: "Couldn’t save drawing", message: detail, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: leaving ? "Retry and leave" : "Retry save", style: .default) { [weak self] _ in
            guard let self else { return }
            self.saveFailure = nil
            self.closeAfterSave = leaving
            self.view.isUserInteractionEnabled = !leaving
            self.finishCanvasInteraction()
            self.saveNow()
        })
        alert.addAction(UIAlertAction(title: "Keep drawing", style: .cancel))
        if leaving {
            alert.addAction(UIAlertAction(title: "Leave without saving", style: .destructive) { [weak self] _ in
                self?.navigationController?.popViewController(animated: true)
            })
        }
        present(alert, animated: true)
    }
    private func showImageExport() {
        finishCanvasInteraction()
        let options = StillExportController(record: document.record)
        options.onExport = { [weak self] options, photos in self?.export(options: options, photos: photos) }
        present(EditorSheetNavigationController(rootViewController: options), animated: true)
    }
    private func export(options: StillExport, photos: Bool) {
        finishCanvasInteraction()
        let snapshot = document.snapshot()
        store.queue.async {
            let result = Result { try self.store.exportImage(snapshot, options: options) }
            DispatchQueue.main.async {
                switch result {
                case .failure(let error): self.showError(error)
                case .success(let url):
                    self.deliverArtwork([url], photos: photos)
                }
            }
        }
    }
    private func showLayers() {
        finishCanvasInteraction()
        let layers = LayersController(document: document, sharedControls: sharedControls)
        layers.onImportImage = { [weak self] in self?.importImage() }
        layers.onChange = { [weak self] in self?.changed() }
        presentCanvasPanel(layers)
    }
    func presentCanvasPanel(_ controller: UIViewController) {
        let navigation = EditorSheetNavigationController(rootViewController: controller)
        navigation.overrideUserInterfaceStyle = .dark
        navigation.modalPresentationStyle = .custom
        navigation.transitioningDelegate = self
        present(navigation, animated: false)
    }
    func presentationController(forPresented presented: UIViewController, presenting: UIViewController?,
                                source: UIViewController) -> UIPresentationController? {
        CanvasPanelPresentation(presentedViewController: presented, presenting: presenting)
    }
    private func brushSettings() {
        finishCanvasInteraction()
        showShared(sharedControls.settingsController(eraser: viewport.canvas.tool == .eraser), title: "Brush settings")
    }
    fileprivate func updateColorSwatch(_ color: UIColor) {
        let image = UIGraphicsImageRenderer(size: CGSize(width: 30, height: 30)).image { _ in
            let ring = UIBezierPath(ovalIn: CGRect(x: 1, y: 1, width: 28, height: 28))
            color.setFill(); ring.fill(); UIColor.white.setStroke(); ring.lineWidth = 2; ring.stroke()
        }
        colorButton?.setImage(image.withRenderingMode(.alwaysOriginal), for: .normal)
    }
    func showShared(_ controller: UIViewController, title: String) {
        controller.title = title
        controller.navigationItem.rightBarButtonItem = controller.sheetDoneButton()
        let navigation = EditorSheetNavigationController(rootViewController: controller)
        navigation.modalPresentationStyle = .pageSheet
        navigation.sheetPresentationController?.detents = [.large()]
        navigation.sheetPresentationController?.prefersGrabberVisible = true
        present(navigation, animated: true)
    }
    func applyBrush(_ brush: NativeBrush) {
        finishCanvasInteraction()
        let keepEraser = viewport.canvas.tool == .eraser && viewport.canvas.brush.id == brush.id
        viewport.canvas.mode = .paint
        viewport.canvas.brush = brush
        viewport.canvas.brushSize = CGFloat(brush.size)
        viewport.canvas.brushOpacity = CGFloat(brush.opacity)
        if keepEraser { viewport.canvas.tool = .eraser }
        else {
            switch brush.engine {
            case "PENCIL": viewport.canvas.tool = .pencil
            case "AIRBRUSH": viewport.canvas.tool = .airbrush
            default: viewport.canvas.tool = .ink
            }
        }
        updateToolValue()
        if viewport.canvas.tool != .eraser { previousTool = viewport.canvas.tool }
        let defaults = UserDefaults.standard
        defaults.set(brush.id, forKey: "brush.id"); defaults.set(brush.size, forKey: "brush.size")
        defaults.set(brush.opacity, forKey: "brush.opacity"); defaults.set(sharedControls.brushData(), forKey: "brush.data")
    }
    private func importImage() {
        finishCanvasInteraction()
        choosePhoto { [weak self] url, name in
            let source = try PhotoSource(file: url)
            return {
                guard let self else { return }
                try self.document.importPhoto(source, name: name)
                self.changed()
            }
        }
    }

    func choosePhoto(read: @escaping (URL, String) throws -> (() throws -> Void)) {
        readPickedPhoto = read
        if UserDefaults.standard.useFilesForImages {
            let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.image], asCopy: true)
            imageFilePicker = picker
            picker.delegate = self
            present(picker, animated: true)
            return
        }
        var config = PHPickerConfiguration()
        config.filter = .images
        config.selectionLimit = 1
        config.preferredAssetRepresentationMode = .current
        let picker = PHPickerViewController(configuration: config)
        picker.delegate = self
        present(picker, animated: true)
    }

    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        let read = readPickedPhoto
        readPickedPhoto = nil
        picker.dismiss(animated: true) {
            guard let provider = results.first?.itemProvider, let read else { return }
            guard let type = provider.registeredTypeIdentifiers.first(where: { UTType($0)?.conforms(to: .image) == true }) else {
                self.showError(DrawingError.message("Choose a readable photo."))
                return
            }
            let name = provider.suggestedName.map { ($0 as NSString).deletingPathExtension } ?? "Photo"
            self.view.isUserInteractionEnabled = false
            provider.loadFileRepresentation(forTypeIdentifier: type) { url, error in
                let edit = Result {
                    if let error { throw error }
                    guard let url else { throw DrawingError.message("The selected photo could not be loaded.") }
                    return try read(url, name)
                }
                self.finishImageImport(edit)
            }
        }
    }

    nonisolated private func finishImageImport(_ edit: Result<() throws -> Void, Error>) {
        DispatchQueue.main.async {
            self.view.isUserInteractionEnabled = true
            do { try edit.get()() }
            catch { self.showError(error) }
        }
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        if controller === imageFilePicker { readPickedPhoto = nil }
        importingMedia = false
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        if controller === imageFilePicker {
            let read = readPickedPhoto
            readPickedPhoto = nil
            guard let read, let url = urls.first else { return }
            view.isUserInteractionEnabled = false
            store.queue.async {
                let scoped = url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                self.finishImageImport(Result { try read(url, url.deletingPathExtension().lastPathComponent) })
            }
            return
        }
        let media = importingMedia
        importingMedia = false
        guard let url = urls.first else { return }
        if media { addMediaFile(url) } else { importBrushFile(url) }
    }
    override var keyCommands: [UIKeyCommand]? {
        [UIKeyCommand(input: "z", modifierFlags: .command, action: #selector(keyUndo)),
         UIKeyCommand(input: "z", modifierFlags: [.command, .shift], action: #selector(keyRedo)),
         UIKeyCommand(input: "0", modifierFlags: .command, action: #selector(keyFit))]
    }
    @objc private func keyUndo() { finishCanvasInteraction(); document.undo(); changed() }
    @objc private func keyRedo() { finishCanvasInteraction(); document.redo(); changed() }
    @objc private func keyFit() { viewport.fit() }

}

private extension NSLayoutConstraint {
    func withPriority(_ priority: UILayoutPriority) -> NSLayoutConstraint { self.priority = priority; return self }
}

// Kotlin retains this adapter; the adapter must not retain the editor in return.
private final class EditorActions: NSObject, AppleEditorActions {
    weak var editor: EditorController?
    init(_ editor: EditorController) { self.editor = editor }
    func strokePreview(brush: NativeBrush, eraser: Bool) -> Data? { try? BrushSpecimen.image(brush, eraser: eraser) }
    func eraserChanged() { editor?.activateEraserBrush() }
    func brushChanged(brush: NativeBrush) { editor?.applyBrush(brush) }
    func colorChanged(red: Float, green: Float, blue: Float) {
        let color = UIColor(red: CGFloat(red), green: CGFloat(green), blue: CGFloat(blue), alpha: 1)
        editor?.viewport.canvas.brushColor = color
        editor?.updateColorSwatch(color)
    }
    func swatchDrag(x: Float, y: Float, red: Float, green: Float, blue: Float, ended: Bool, cancelled: Bool) {
        let color = UIColor(red: CGFloat(red), green: CGFloat(green), blue: CGFloat(blue), alpha: 1)
        editor?.dragSwatch(CGPoint(x: CGFloat(x), y: CGFloat(y)), color: color, ended: ended, cancelled: cancelled)
    }
    func fingerDrawingChanged(enabled: Bool) {
        editor?.viewport.canvas.fingerDrawing = enabled
        editor?.refreshFingerMode()
        UserDefaults.standard.set(enabled, forKey: "fingerDrawing")
    }
}
