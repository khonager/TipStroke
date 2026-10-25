import UIKit

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

final class GalleryController: UITableViewController {
    private var drawings: [DocumentRecord] = []
    private let store = ProjectStore.shared
    private var loading = false
    override func viewDidLoad() {
        super.viewDidLoad()
        title = "TipStroke"
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
                case .success(let drawings): self.drawings = drawings; self.tableView.reloadData(); self.tableView.backgroundView?.isHidden = !drawings.isEmpty
                case .failure(let error): self.showError(error)
                }
            }
        }
    }
    @objc private func newDrawing() {
        let alert = UIAlertController(title: "New drawing", message: "Canvas size in pixels (16–4096 per side)", preferredStyle: .alert)
        alert.addTextField { $0.placeholder = "Name"; $0.text = "Untitled" }
        alert.addTextField { $0.placeholder = "Width"; $0.text = "2048"; $0.keyboardType = .numberPad }
        alert.addTextField { $0.placeholder = "Height"; $0.text = "2048"; $0.keyboardType = .numberPad }
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        alert.addAction(UIAlertAction(title: "Create", style: .default) { [weak self, weak alert] _ in
            guard let self, let fields = alert?.textFields else { return }
            let name = (fields[0].text ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            guard !name.isEmpty, let width = Int(fields[1].text ?? ""), let height = Int(fields[2].text ?? ""),
                  (16...4096).contains(width), (16...4096).contains(height) else {
                self.showError(DrawingError.message("Enter a name and dimensions between 16 and 4096.")); return
            }
            let document = RasterDocument(name: name, width: width, height: height)
            let snapshot = document.snapshot()
            self.store.queue.async {
                let result = Result { try self.store.save(snapshot) }
                DispatchQueue.main.async {
                    switch result {
                    case .success: self.navigationController?.pushViewController(EditorController(document: document), animated: true)
                    case .failure(let error): self.showError(error)
                    }
                }
            }
        })
        present(alert, animated: true)
    }
    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { drawings.count }
    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "drawing", for: indexPath)
        let drawing = drawings[indexPath.row]
        var content = cell.defaultContentConfiguration()
        content.text = drawing.name
        content.secondaryText = "\(drawing.width) × \(drawing.height) · \(drawing.layers.count) layers"
        content.image = UIImage(systemName: "scribble.variable")
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
