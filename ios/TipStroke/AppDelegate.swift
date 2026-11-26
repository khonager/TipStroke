import UIKit
import TipStrokeShared

@main
final class AppDelegate: UIResponder, UIApplicationDelegate {}

final class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?

    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options connectionOptions: UIScene.ConnectionOptions) {
        guard let windowScene = scene as? UIWindowScene else { return }
        let window = UIWindow(windowScene: windowScene)
        window.rootViewController = UINavigationController(rootViewController: GalleryController())
        window.tintColor = .systemOrange
        self.window = window
        window.makeKeyAndVisible()
    }
}

/// Editor sheets reserve a solid header above both UIKit and Compose content.
final class EditorSheetNavigationController: UINavigationController {
    override func viewDidLoad() {
        super.viewDidLoad()
        let appearance = UINavigationBarAppearance()
        appearance.configureWithOpaqueBackground()
        appearance.backgroundColor = .secondarySystemBackground
        appearance.shadowColor = .separator
        navigationBar.standardAppearance = appearance
        navigationBar.scrollEdgeAppearance = appearance
        navigationBar.compactAppearance = appearance
        navigationBar.compactScrollEdgeAppearance = appearance
        navigationBar.isTranslucent = false
        navigationBar.tintColor = .label
    }
}

final class CanvasPanelPresentation: UIPresentationController {
    private let backdrop = UIControl()
    override var shouldRemovePresentersView: Bool { false }
    override var frameOfPresentedViewInContainerView: CGRect {
        guard let containerView else { return .zero }
        let safe = containerView.bounds.inset(by: containerView.safeAreaInsets).insetBy(dx: 10, dy: 10)
        let width = min(352, safe.width * 0.94)
        let height = min(540, safe.height * 0.76)
        let x = safe.width > safe.height ? safe.maxX - width : safe.midX - width / 2
        let y = safe.midY - height / 2
        return CGRect(x: x, y: y, width: width, height: height)
    }
    override func presentationTransitionWillBegin() {
        guard let containerView else { return }
        backdrop.frame = containerView.bounds
        backdrop.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        backdrop.addAction(UIAction { [weak self] _ in
            guard let controller = self?.presentedViewController, !controller.isModalInPresentation else { return }
            controller.dismiss(animated: false)
        }, for: .touchUpInside)
        containerView.insertSubview(backdrop, at: 0)
        presentedView?.layer.cornerRadius = 16
        presentedView?.clipsToBounds = true
    }
    override func containerViewWillLayoutSubviews() {
        super.containerViewWillLayoutSubviews()
        presentedView?.frame = frameOfPresentedViewInContainerView
    }
}

extension UIViewController {
    func sheetDoneButton() -> UIBarButtonItem {
        let item = UIBarButtonItem(title: "Done", primaryAction: UIAction { [weak self] _ in self?.dismiss(animated: true) })
        if #available(iOS 26.0, *) { item.hidesSharedBackground = true }
        return item
    }

    func showError(_ error: Error) {
        let alert = UIAlertController(title: "TipStroke", message: error.localizedDescription, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "OK", style: .default))
        if presentedViewController == nil { present(alert, animated: true) }
        else { presentedViewController?.present(alert, animated: true) }
    }
}

private enum ImagePickerSetting {
    static let key = "useFilesForImages"
}

extension UserDefaults {
    var useFilesForImages: Bool {
        get { bool(forKey: ImagePickerSetting.key) }
        set { set(newValue, forKey: ImagePickerSetting.key) }
    }
}

final class GalleryController: UICollectionViewController, UICollectionViewDelegateFlowLayout, AppleNewDrawingActions {
    init() { super.init(collectionViewLayout: UICollectionViewFlowLayout()) }
    required init?(coder: NSCoder) { fatalError("Use init()") }
    private let thumbnails = NSCache<NSUUID, UIImage>()
    private let store = ProjectStore.shared
    var loading = false
    var pickingExports = false
    private var exportingDrawings = false
    var layout = UserDefaults.standard.data(forKey: "gallery.stacks").flatMap { try? JSONDecoder().decode(GalleryStacks.self, from: $0) } ?? GalleryStacks()
    var openStack: UUID?
    var displayed: [UUID] {
        guard !records.isEmpty else { return [] }
        return openStack.flatMap { layout.stacks[$0]?.members } ?? layout.order
    }
    private var records: [UUID: DocumentRecord] = [:]
    func saveLayout() {
        if let data = try? JSONEncoder().encode(layout) { UserDefaults.standard.set(data, forKey: "gallery.stacks") }
        if let openStack, layout.stacks[openStack] == nil { self.openStack = nil }
        updateGalleryActions()
        collectionView.reloadData()
    }
    override func viewDidLoad() {
        super.viewDidLoad()
        title = "TipStroke"
        thumbnails.countLimit = 80
        collectionView.addGestureRecognizer(UIPinchGestureRecognizer(target: self, action: #selector(pinchCard(_:))))
        collectionView.alwaysBounceVertical = true
        if let flow = collectionViewLayout as? UICollectionViewFlowLayout {
            flow.sectionInset = UIEdgeInsets(top: 8, left: 8, bottom: 8, right: 8)
            flow.minimumInteritemSpacing = 8
            flow.minimumLineSpacing = 12
        }
        collectionView.dragInteractionEnabled = true; collectionView.dragDelegate = self; collectionView.dropDelegate = self
        navigationItem.largeTitleDisplayMode = .never

        updateGalleryActions()

        collectionView.register(GalleryTile.self, forCellWithReuseIdentifier: "drawing")
        collectionView.refreshControl = UIRefreshControl()
        collectionView.refreshControl?.addTarget(self, action: #selector(reload), for: .valueChanged)
        let empty = UILabel()
        empty.text = "Your drawings stay on this device.\nTap + to open a new canvas."
        empty.numberOfLines = 0; empty.textAlignment = .center; empty.textColor = .secondaryLabel
        empty.isHidden = true
        collectionView.backgroundView = empty
    }
    private func updateGalleryActions() {
        title = openStack.flatMap { layout.stacks[$0]?.name } ?? "TipStroke"
        if pickingExports {
            navigationItem.leftBarButtonItem = UIBarButtonItem(systemItem: .cancel, primaryAction: UIAction { [weak self] _ in self?.setPicking(false) })
            let share = UIBarButtonItem(image: UIImage(systemName: "ellipsis.circle"), menu: UIMenu(children: [
                UIAction(title: "Share PNG") { [weak self] _ in self?.shareSelected() },
                UIAction(title: "Share TipStroke project") { [weak self] _ in self?.shareSelected(asProject: true) },
                UIAction(title: "Duplicate") { [weak self] _ in self?.changeSelected(delete: false) },
                UIAction(title: "Delete", attributes: .destructive) { [weak self] _ in self?.changeSelected(delete: true) }
            ]))
            share.isEnabled = !(collectionView.indexPathsForSelectedItems ?? []).isEmpty && !exportingDrawings
            share.accessibilityLabel = "Selected drawing actions"
            let all = !displayed.isEmpty && collectionView.indexPathsForSelectedItems?.count == displayed.count
            let toggle = UIBarButtonItem(title: all ? "Deselect all" : "Select all", primaryAction: UIAction { [weak self] _ in
                guard let self else { return }
                for row in self.displayed.indices {
                    let path = IndexPath(row: row, section: 0)
                    if all { self.collectionView.deselectItem(at: path, animated: false) }
                    else { self.collectionView.selectItem(at: path, animated: false, scrollPosition: []) }
                }
                self.updateGalleryActions()
            })
            toggle.isEnabled = !exportingDrawings
            navigationItem.rightBarButtonItems = [share, toggle]
        } else {
            let open = UIBarButtonItem(image: UIImage(systemName: "doc.badge.plus"), menu: UIMenu(children: [
                UIAction(title: "From image") { [weak self] _ in self?.chooseGalleryImage() },
                UIAction(title: "Import project, GIF or video") { [weak self] _ in self?.chooseGalleryFile() }
            ]))
            open.accessibilityLabel = "Import"
            let size = UIBarButtonItem(image: UIImage(systemName: "square.grid.2x2"), menu: UIMenu(children:
                [("Compact", 156), ("Medium", 220), ("Large", 320)].map { label, width in
                    UIAction(title: label, state: CGFloat(width) == minimumWidth ? .on : .off) { [weak self] _ in
                        UserDefaults.standard.set(width, forKey: "gallery.width")
                        self?.collectionViewLayout.invalidateLayout()
                        self?.updateGalleryActions()
                    }
                }))
            size.accessibilityLabel = "Preview size"
            let leading = openStack == nil ? open : UIBarButtonItem(title: "Gallery", primaryAction: UIAction { [weak self] _ in
                self?.openStack = nil; self?.saveLayout()
            })
            navigationItem.leftBarButtonItems = [leading, size]
            let select = UIBarButtonItem(image: UIImage(systemName: "checkmark.circle"), primaryAction: UIAction { [weak self] _ in self?.setPicking(true) })
            select.accessibilityLabel = "Select drawings"
            let settings = UIBarButtonItem(image: UIImage(systemName: "gearshape"), primaryAction: UIAction { [weak self] _ in
                self?.navigationController?.pushViewController(GallerySettingsController(style: .insetGrouped), animated: true)
            })
            settings.accessibilityLabel = "Settings"
            navigationItem.rightBarButtonItems = [UIBarButtonItem(barButtonSystemItem: .add, target: self, action: #selector(newDrawing)), select, settings]
        }
    }
    private func setPicking(_ enabled: Bool) {
        guard !exportingDrawings else { return }
        pickingExports = enabled
        for path in collectionView.indexPathsForSelectedItems ?? [] { collectionView.deselectItem(at: path, animated: false) }
        collectionView.allowsMultipleSelection = enabled
        collectionView.reloadData()
        collectionView.refreshControl?.isEnabled = !enabled
        updateGalleryActions()
    }
    private var selectedDrawingIDs: [UUID] {
        (collectionView.indexPathsForSelectedItems ?? []).sorted().flatMap { path -> [UUID] in
            let id = displayed[path.row]
            return layout.stacks[id]?.members ?? [id]
        }
    }
    private func changeSelected(delete: Bool) {
        let ids = selectedDrawingIDs
        guard !ids.isEmpty, !exportingDrawings else { return }
        func perform() {
            exportingDrawings = true
            updateGalleryActions()
            store.queue.async {
                var failed = 0
                for id in ids {
                    do {
                        if delete { try self.store.delete(id) }
                        else { _ = try self.store.duplicate(id) }
                    } catch { failed += 1 }
                }
                let count = failed
                DispatchQueue.main.async {
                    self.exportingDrawings = false
                    self.setPicking(false)
                    self.reload()
                    if count > 0 { self.showError(DrawingError.message("Could not finish the action for \(count) drawing(s).")) }
                }
            }
        }
        if delete {
            let alert = UIAlertController(title: "Delete \(ids.count) drawing(s)?", message: "This removes the selected drawings from this device.", preferredStyle: .alert)
            alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
            alert.addAction(UIAlertAction(title: "Delete", style: .destructive) { _ in perform() })
            present(alert, animated: true)
        } else { perform() }
    }
    private func shareSelected(asProject: Bool = false) {
        let ids = selectedDrawingIDs
        guard !ids.isEmpty, !exportingDrawings else { return }
        let selectedLayout = layout
        exportingDrawings = true; updateGalleryActions()
        store.queue.async {
            var urls: [URL] = []
            do {
                if asProject { urls = [try self.store.exportPackage(ids, layout: selectedLayout)] }
                else {
                    for id in ids { try autoreleasepool { urls.append(try self.store.exportPNG(self.store.load(id))) } }
                }
                let files = urls
                DispatchQueue.main.async {
                    self.exportingDrawings = false; self.setPicking(false)
                    let sheet = UIActivityViewController(activityItems: files, applicationActivities: nil)
                    sheet.popoverPresentationController?.barButtonItem = self.navigationItem.rightBarButtonItems?.last
                    sheet.completionWithItemsHandler = { _, _, _, _ in files.forEach { try? FileManager.default.removeItem(at: $0) } }
                    self.present(sheet, animated: true)
                }
            } catch {
                urls.forEach { try? FileManager.default.removeItem(at: $0) }
                DispatchQueue.main.async { self.exportingDrawings = false; self.updateGalleryActions(); self.showError(error) }
            }
        }
    }
    override func collectionView(_ collectionView: UICollectionView, didDeselectItemAt indexPath: IndexPath) {
        if pickingExports { updateGalleryActions() }
    }
    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        navigationController?.setNavigationBarHidden(false, animated: animated)
        reload()
    }
    @objc func reload() {
        store.queue.async {
            let result = Result { try self.store.list() }
            DispatchQueue.main.async {
                self.collectionView.refreshControl?.endRefreshing()
                switch result {
                case .success(let drawings):
                    self.thumbnails.removeAllObjects()
                    self.records = Dictionary(uniqueKeysWithValues: drawings.map { ($0.id, $0) })
                    self.layout.reconcile(drawings.map(\.id)); self.saveLayout()
                    self.collectionView.backgroundView?.isHidden = !drawings.isEmpty
                case .failure(let error): self.showError(error)
                }
            }
        }
    }
    @objc private func newDrawing() {
        let screen = view.window?.screen ?? UIScreen.main
        let controller = AppleNewDrawingBridge(actions: self).controller(
            screenWidth: Int32(screen.bounds.width * screen.scale),
            screenHeight: Int32(screen.bounds.height * screen.scale),
            tileBudget: Int32(RasterDocument.maximumTiles))
        controller.modalPresentationStyle = .pageSheet
        controller.sheetPresentationController?.detents = [.large()]
        controller.sheetPresentationController?.prefersGrabberVisible = true
        present(controller, animated: true)
    }
    func cancelNewDrawing() { dismiss(animated: true) }
    func createDrawing(name: String, width: Int32, height: Int32) {
        guard !loading, (16...8192).contains(Int(width)), (16...8192).contains(Int(height)) else { return }
        loading = true
        let document = RasterDocument(name: name, width: Int(width), height: Int(height))
        let snapshot = document.snapshot()
        dismiss(animated: true) {
            self.store.queue.async {
                let result = Result { try self.store.save(snapshot) }
                DispatchQueue.main.async {
                    self.loading = false
                    switch result {
                    case .success: self.navigationController?.pushViewController(EditorController(document: document), animated: true)
                    case .failure(let error): self.showError(error)
                    }
                }
            }
        }
    }
    private var minimumWidth: CGFloat {
        let width = UserDefaults.standard.integer(forKey: "gallery.width")
        return CGFloat([156, 220, 320].contains(width) ? width : 156)
    }
    private weak var galleryPreview: GalleryArtworkPreview?
    @objc private func pinchCard(_ gesture: UIPinchGestureRecognizer) {
        if gesture.state == .began {
            guard !pickingExports, galleryPreview == nil, let host = navigationController?.view,
                  let path = collectionView.indexPathForItem(at: gesture.location(in: collectionView)),
                  let tile = collectionView.cellForItem(at: path) as? GalleryTile,
                  records[displayed[path.item]] != nil, let image = tile.picture.image else { return }
            let id = displayed[path.item]
            let preview = GalleryArtworkPreview(frame: host.bounds, image: image, anchor: tile.picture.convert(tile.picture.bounds, to: host))
            preview.onExpand = { [weak preview] in
                self.store.queue.async {
                    guard let image = try? ProjectStore.thumbnail(self.store.load(id), maxSide: 2048) else { return }
                    DispatchQueue.main.async { preview?.picture.image = UIImage(cgImage: image) }
                }
            }
            host.addSubview(preview)
            galleryPreview = preview
        }
        galleryPreview?.pinch(gesture)
    }
    func collectionView(_ collectionView: UICollectionView, layout collectionViewLayout: UICollectionViewLayout, sizeForItemAt path: IndexPath) -> CGSize {
        let insets = collectionView.adjustedContentInset
        let available = collectionView.bounds.width - insets.left - insets.right - 16
        let columns = max(1, floor((available + 8) / (minimumWidth + 8)))
        let width = max(1, floor((available + 8) / columns) - 8)
        return CGSize(width: width, height: min(260, minimumWidth * 0.78) + 62)
    }
    override func collectionView(_ collectionView: UICollectionView, numberOfItemsInSection section: Int) -> Int { displayed.count }
    override func collectionView(_ collectionView: UICollectionView, cellForItemAt path: IndexPath) -> UICollectionViewCell {
        let cell = collectionView.dequeueReusableCell(withReuseIdentifier: "drawing", for: path) as! GalleryTile
        let id = displayed[path.item]
        cell.accessibilityIdentifier = "gallery-\(id.uuidString)"
        let drawing = records[id], stack = layout.stacks[id]
        cell.name.text = drawing?.name ?? stack?.name
        cell.details.text = drawing.map { "\($0.width) × \($0.height) · " + $0.modified.formatted(date: .numeric, time: .omitted) } ?? "\(stack?.members.count ?? 0) drawings"
        cell.accessibilityLabel = cell.name.text
        cell.menuButton.isHidden = pickingExports
        cell.menuButton.menu = UIMenu(children: galleryActions(id))
        cell.picture.image = stack == nil ? thumbnails.object(forKey: id as NSUUID) : UIImage(systemName: "square.stack.3d.up")
        if stack == nil && cell.picture.image == nil {
            store.queue.async {
                let image = try? self.store.thumbnail(id)
                DispatchQueue.main.async {
                    guard let image else { return }
                    self.thumbnails.setObject(image, forKey: id as NSUUID)
                    if cell.accessibilityIdentifier == "gallery-\(id.uuidString)" { cell.picture.image = image }
                }
            }
        }
        return cell
    }
    override func collectionView(_ collectionView: UICollectionView, didSelectItemAt indexPath: IndexPath) {
        if pickingExports { updateGalleryActions(); return }
        collectionView.deselectItem(at: indexPath, animated: true)
        guard !loading else { return }
        let id = displayed[indexPath.row]
        if layout.stacks[id] != nil { openStack = id; saveLayout(); return }
        loading = true
        store.queue.async {
            let result = Result { try self.store.load(id) }
            DispatchQueue.main.async {
                self.loading = false
                switch result {
                case .success(let snapshot): self.navigationController?.pushViewController(EditorController(document: RasterDocument(snapshot: snapshot)), animated: true)
                case .failure(let error): self.showError(error)
                }
            }
        }
    }
    private func galleryActions(_ id: UUID) -> [UIAction] {
        if let stack = layout.stacks[id] {
            return [UIAction(title: "Rename stack") { _ in self.promptName(stack.name) { self.layout.stacks[id]?.name = $0; self.saveLayout() } },
                    UIAction(title: "Unstack") { _ in self.layout.unstack(id); self.saveLayout() }]
        }
        guard let drawing = records[id] else { return [] }
        var actions = [
            UIAction(title: "Rename") { _ in self.promptName(drawing.name) { name in self.changeDrawing { try self.store.rename(id, name: name) } } },
            UIAction(title: "Duplicate") { _ in self.changeDrawing { try self.store.duplicate(id) } },
            UIAction(title: "Delete", attributes: .destructive) { _ in
                let alert = UIAlertController(title: "Delete \(drawing.name)?", message: "This removes the drawing from this device.", preferredStyle: .alert)
                alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
                alert.addAction(UIAlertAction(title: "Delete", style: .destructive) { _ in self.changeDrawing { try self.store.delete(id) } })
                self.present(alert, animated: true)
            }]
        if let parent = openStack { actions.append(UIAction(title: "Move out of stack") { _ in self.layout.moveOut(id, from: parent); self.saveLayout() }) }
        return actions
    }
    private func promptName(_ name: String, save: @escaping (String) -> Void) {
        let alert = UIAlertController(title: "Rename", message: nil, preferredStyle: .alert)
        alert.addTextField { $0.text = name }
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        alert.addAction(UIAlertAction(title: "Save", style: .default) { [weak alert] _ in
            if let text = alert?.textFields?.first?.text?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty { save(text) }
        })
        present(alert, animated: true)
    }
    private func changeDrawing(_ change: @escaping () throws -> Void) {
        store.queue.async {
            let result = Result { try change() }
            DispatchQueue.main.async {
                self.reload()
                if case .failure(let error) = result { self.showError(error) }
            }
        }
    }
}

final class GalleryTile: UICollectionViewCell {
    let picture = UIImageView(), name = UILabel(), details = UILabel(), menuButton = UIButton(type: .system)
    override var isSelected: Bool { didSet { contentView.layer.borderWidth = isSelected ? 2 : 0 } }
    override init(frame: CGRect) {
        super.init(frame: frame)
        picture.contentMode = .scaleAspectFill
        picture.clipsToBounds = true
        picture.layer.cornerRadius = 8
        picture.backgroundColor = .secondarySystemBackground
        name.font = .preferredFont(forTextStyle: .subheadline)
        details.font = .preferredFont(forTextStyle: .caption2)
        details.textColor = .secondaryLabel
        details.numberOfLines = 2
        menuButton.setImage(UIImage(systemName: "ellipsis"), for: .normal)
        menuButton.showsMenuAsPrimaryAction = true
        menuButton.accessibilityLabel = "Drawing actions"
        contentView.layer.borderColor = tintColor.cgColor
        for item: UIView in [picture, name, details, menuButton] { contentView.addSubview(item) }
    }
    required init?(coder: NSCoder) { fatalError("Use init(frame:)") }
    override func layoutSubviews() {
        super.layoutSubviews()
        let width = contentView.bounds.width, height = max(0, contentView.bounds.height - 62)
        picture.frame = CGRect(x: 0, y: 0, width: width, height: height)
        name.frame = CGRect(x: 2, y: height + 3, width: width - 44, height: 22)
        details.frame = CGRect(x: 2, y: height + 25, width: width - 44, height: 34)
        menuButton.frame = CGRect(x: width - 44, y: height, width: 44, height: 48)
    }
}
