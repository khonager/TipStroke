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
    private var drawings: [DocumentRecord] = []
    private let thumbnails = NSCache<NSUUID, UIImage>()
    private let store = ProjectStore.shared
    private var loading = false
    override func viewDidLoad() {
        super.viewDidLoad()
        title = "TipStroke"
        thumbnails.countLimit = 80
        tableView.rowHeight = 100
        navigationController?.navigationBar.prefersLargeTitles = true
        navigationItem.rightBarButtonItem = UIBarButtonItem(barButtonSystemItem: .add, target: self, action: #selector(newDrawing))
        tableView.register(UITableViewCell.self, forCellReuseIdentifier: "drawing")
        refreshControl = UIRefreshControl()
        refreshControl?.addTarget(self, action: #selector(reload), for: .valueChanged)
        let empty = UILabel()
        empty.text = "Your drawings stay on this device.\nTap + to open a new canvas."
        empty.numberOfLines = 0; empty.textAlignment = .center; empty.textColor = .secondaryLabel
        tableView.backgroundView = empty
    }
    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        navigationController?.setNavigationBarHidden(false, animated: animated)
        reload()
    }
    @objc private func reload() {
        store.queue.async {
            let result = Result { try self.store.list() }
            DispatchQueue.main.async {
                self.refreshControl?.endRefreshing()
                switch result {
                case .success(let drawings): self.thumbnails.removeAllObjects(); self.drawings = drawings; self.tableView.reloadData(); self.tableView.backgroundView?.isHidden = !drawings.isEmpty
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
    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { drawings.count }
    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "drawing", for: indexPath)
        let drawing = drawings[indexPath.row]
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
                       self.drawings.indices.contains(indexPath.row), self.drawings[indexPath.row].id == drawing.id {
                        self.tableView.reloadRows(at: [indexPath], with: .none)
                    }
                }
            }
        }
        cell.contentConfiguration = content; cell.accessoryType = .disclosureIndicator
        return cell
    }
    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        guard !loading else { return }
        loading = true
        let id = drawings[indexPath.row].id
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
        let drawing = drawings[indexPath.row]
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
        let drawing = drawings[indexPath.row]
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
        let configuration = UISwipeActionsConfiguration(actions: [action]); configuration.performsFirstActionWithFullSwipe = false
        return configuration
    }
}
