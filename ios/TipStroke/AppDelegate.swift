import UIKit
import TipStrokeShared

@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        let window = UIWindow(frame: UIScreen.main.bounds)
        window.rootViewController = UINavigationController(rootViewController: GalleryController())
        window.tintColor = .systemOrange
        window.makeKeyAndVisible()
        self.window = window
        return true
    }
}

extension UIViewController {
    func showError(_ error: Error) {
        let alert = UIAlertController(title: "TipStroke", message: error.localizedDescription, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "OK", style: .default))
        if presentedViewController == nil { present(alert, animated: true) }
        else { presentedViewController?.present(alert, animated: true) }
    }
}

final class GalleryController: UITableViewController, AppleNewDrawingActions {
    private let thumbnails = NSCache<NSUUID, UIImage>()
    private let store = ProjectStore.shared
    private var loading = false
    var pickingExports = false
    private var exportingDrawings = false
    var layout = UserDefaults.standard.data(forKey: "gallery.stacks").flatMap { try? JSONDecoder().decode(GalleryStacks.self, from: $0) } ?? GalleryStacks()
    var openStack: UUID?
    var displayed: [UUID] { openStack.flatMap { layout.stacks[$0]?.members } ?? layout.order }
    private var records: [UUID: DocumentRecord] = [:]
    func saveLayout() {
        if let data = try? JSONEncoder().encode(layout) { UserDefaults.standard.set(data, forKey: "gallery.stacks") }
        if let openStack, layout.stacks[openStack] == nil { self.openStack = nil }
        updateGalleryActions()
        tableView.reloadData()
    }
    override func viewDidLoad() {
        super.viewDidLoad()
        title = "TipStroke"
        thumbnails.countLimit = 80
        tableView.rowHeight = 100
        tableView.dragInteractionEnabled = true; tableView.dragDelegate = self; tableView.dropDelegate = self
        navigationItem.largeTitleDisplayMode = .never
        tableView.allowsMultipleSelectionDuringEditing = true
        updateGalleryActions()

        tableView.register(UITableViewCell.self, forCellReuseIdentifier: "drawing")
        refreshControl = UIRefreshControl()
        refreshControl?.addTarget(self, action: #selector(reload), for: .valueChanged)
        let empty = UILabel()
        empty.text = "Your drawings stay on this device.\nTap + to open a new canvas."
        empty.numberOfLines = 0; empty.textAlignment = .center; empty.textColor = .secondaryLabel
        tableView.backgroundView = empty
    }
    private func updateGalleryActions() {
        title = openStack.flatMap { layout.stacks[$0]?.name } ?? "TipStroke"
        if pickingExports {
            navigationItem.leftBarButtonItem = UIBarButtonItem(systemItem: .cancel, primaryAction: UIAction { [weak self] _ in self?.setPicking(false) })
            let share = UIBarButtonItem(image: UIImage(systemName: "square.and.arrow.up"), menu: UIMenu(children: [
                UIAction(title: "Share PNG") { [weak self] _ in self?.shareSelected() },
                UIAction(title: "Share TipStroke project") { [weak self] _ in self?.shareSelected(asProject: true) }
            ]))
            share.isEnabled = !(tableView.indexPathsForSelectedRows ?? []).isEmpty && !exportingDrawings
            share.accessibilityLabel = "Share selected drawings"
            navigationItem.rightBarButtonItems = [share]
        } else {
            let open = UIBarButtonItem(image: UIImage(systemName: "doc.badge.plus"), primaryAction: UIAction { [weak self] _ in self?.chooseProjectPackage() })
            open.accessibilityLabel = "Import TipStroke project"
            navigationItem.leftBarButtonItem = openStack == nil ? open : UIBarButtonItem(title: "Gallery", primaryAction: UIAction { [weak self] _ in
                self?.openStack = nil; self?.saveLayout()
            })
            let select = UIBarButtonItem(image: UIImage(systemName: "checkmark.circle"), primaryAction: UIAction { [weak self] _ in self?.setPicking(true) })
            select.accessibilityLabel = "Select drawings"
            navigationItem.rightBarButtonItems = [UIBarButtonItem(barButtonSystemItem: .add, target: self, action: #selector(newDrawing)), select]
        }
    }
    private func setPicking(_ enabled: Bool) {
        guard !exportingDrawings else { return }
        pickingExports = enabled
        tableView.setEditing(enabled, animated: true)
        refreshControl?.isEnabled = !enabled
        updateGalleryActions()
    }
    private func shareSelected(asProject: Bool = false) {
        let ids = (tableView.indexPathsForSelectedRows ?? []).sorted().flatMap { path -> [UUID] in
            let id = displayed[path.row]
            return layout.stacks[id]?.members ?? [id]
        }
        guard !ids.isEmpty, !exportingDrawings else { return }
        exportingDrawings = true; updateGalleryActions()
        store.queue.async {
            var urls: [URL] = []
            do {
                if asProject { urls = [try self.store.exportPackage(ids)] }
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
    override func tableView(_ tableView: UITableView, didDeselectRowAt indexPath: IndexPath) {
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
                self.refreshControl?.endRefreshing()
                switch result {
                case .success(let drawings):
                    self.thumbnails.removeAllObjects()
                    self.records = Dictionary(uniqueKeysWithValues: drawings.map { ($0.id, $0) })
                    self.layout.reconcile(drawings.map(\.id)); self.saveLayout()
                    self.tableView.backgroundView?.isHidden = !drawings.isEmpty
                case .failure(let error): self.showError(error)
                }
            }
        }
    }
    @objc private func newDrawing() {
        let screen = view.window?.screen ?? UIScreen.main
        let controller = AppleNewDrawingBridge(actions: self).controller(
            screenWidth: Int32(screen.bounds.width * screen.scale),
            screenHeight: Int32(screen.bounds.height * screen.scale))
        controller.modalPresentationStyle = .pageSheet
        controller.sheetPresentationController?.detents = [.large()]
        controller.sheetPresentationController?.prefersGrabberVisible = true
        present(controller, animated: true)
    }
    func cancelNewDrawing() { dismiss(animated: true) }
    func createDrawing(name: String, width: Int32, height: Int32) {
        guard !loading, (16...4096).contains(Int(width)), (16...4096).contains(Int(height)) else { return }
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
    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { displayed.count }
    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "drawing", for: indexPath)
        let id = displayed[indexPath.row]
        if let stack = layout.stacks[id] {
            var content = cell.defaultContentConfiguration()
            content.text = stack.name
            content.secondaryText = "\(stack.members.count) drawings"
            content.image = UIImage(systemName: "square.stack.3d.up")
            cell.contentConfiguration = content; cell.accessoryType = .disclosureIndicator
            return cell
        }
        guard let drawing = records[id] else { return cell }
        var content = cell.defaultContentConfiguration()
        content.text = drawing.name
        content.secondaryText = "\(drawing.width) × \(drawing.height) · \(drawing.layers.count) layers"
        content.image = thumbnails.object(forKey: drawing.id as NSUUID) ?? UIImage(systemName: "scribble.variable")
        content.imageProperties.maximumSize = CGSize(width: 80, height: 80)
        if thumbnails.object(forKey: drawing.id as NSUUID) == nil {
            store.queue.async {
                let image = try? self.store.thumbnail(drawing.id)
                DispatchQueue.main.async {
                    guard let image else { return }; self.thumbnails.setObject(image, forKey: drawing.id as NSUUID)
                    if let visible = self.tableView.indexPathsForVisibleRows, visible.contains(indexPath),
                       self.displayed.indices.contains(indexPath.row), self.displayed[indexPath.row] == drawing.id {
                        self.tableView.reloadRows(at: [indexPath], with: .none)
                    }
                }
            }
        }
        cell.contentConfiguration = content; cell.accessoryType = .disclosureIndicator
        return cell
    }
    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        if pickingExports { updateGalleryActions(); return }
        tableView.deselectRow(at: indexPath, animated: true)
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
    override func tableView(_ tableView: UITableView, leadingSwipeActionsConfigurationForRowAt indexPath: IndexPath) -> UISwipeActionsConfiguration? {
        let id = displayed[indexPath.row]
        if layout.stacks[id] != nil {
            let rename = UIContextualAction(style: .normal, title: "Rename stack") { _, _, done in
                let alert = UIAlertController(title: "Rename stack", message: nil, preferredStyle: .alert)
                alert.addTextField { $0.text = self.layout.stacks[id]?.name }
                alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
                alert.addAction(UIAlertAction(title: "Save", style: .default) { [weak alert] _ in
                    let name = alert?.textFields?.first?.text?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                    if !name.isEmpty { self.layout.stacks[id]?.name = name; self.saveLayout() }
                })
                self.present(alert, animated: true); done(true)
            }
            return UISwipeActionsConfiguration(actions: [rename])
        }
        guard let drawing = records[id] else { return nil }
        let duplicate = UIContextualAction(style: .normal, title: "Duplicate") { [weak self] _, _, done in
            guard let self else { done(false); return }
            self.store.queue.async {
                let result = Result { try self.store.duplicate(drawing.id) }
                DispatchQueue.main.async {
                    if case .failure(let error) = result { self.showError(error); done(false) }
                    else { done(true); self.reload() }
                }
            }
        }
        let rename = UIContextualAction(style: .normal, title: "Rename") { [weak self] _, _, done in
            guard let self else { done(false); return }
            let alert = UIAlertController(title: "Rename drawing", message: nil, preferredStyle: .alert)
            alert.addTextField { $0.text = drawing.name }
            alert.addAction(UIAlertAction(title: "Cancel", style: .cancel) { _ in done(false) })
            alert.addAction(UIAlertAction(title: "Save", style: .default) { [weak alert] _ in
                let name = (alert?.textFields?.first?.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                guard !name.isEmpty else { done(false); return }
                self.store.queue.async {
                    let result = Result { try self.store.rename(drawing.id, name: name) }
                    DispatchQueue.main.async {
                        if case .failure(let error) = result { self.showError(error); done(false) }
                        else { done(true); self.reload() }
                    }
                }
            })
            self.present(alert, animated: true)
        }
        return UISwipeActionsConfiguration(actions: [rename, duplicate])
    }
    override func tableView(_ tableView: UITableView, trailingSwipeActionsConfigurationForRowAt indexPath: IndexPath) -> UISwipeActionsConfiguration? {
        let id = displayed[indexPath.row]
        if layout.stacks[id] != nil {
            return UISwipeActionsConfiguration(actions: [UIContextualAction(style: .normal, title: "Unstack") { _, _, done in
                self.layout.unstack(id); self.saveLayout(); done(true)
            }])
        }
        guard let drawing = records[id] else { return nil }
        let action = UIContextualAction(style: .destructive, title: "Delete") { [weak self] _, _, completion in
            guard let self else { completion(false); return }
            let alert = UIAlertController(title: "Delete \(drawing.name)?", message: "This removes the drawing from this device.", preferredStyle: .alert)
            alert.addAction(UIAlertAction(title: "Cancel", style: .cancel) { _ in completion(false) })
            alert.addAction(UIAlertAction(title: "Delete", style: .destructive) { _ in
                self.store.queue.async {
                    let result = Result { try self.store.delete(drawing.id) }
                    DispatchQueue.main.async {
                        if case .failure(let error) = result { self.showError(error); completion(false) }
                        else { completion(true); self.reload() }
                    }
                }
            })
            self.present(alert, animated: true)
        }
        var actions = [action]
        if let parent = openStack {
            actions.append(UIContextualAction(style: .normal, title: "Move out") { _, _, done in
                self.layout.moveOut(id, from: parent); self.saveLayout(); done(true)
            })
        }
        let configuration = UISwipeActionsConfiguration(actions: actions); configuration.performsFirstActionWithFullSwipe = false
        return configuration
    }
}
