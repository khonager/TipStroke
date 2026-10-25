import UIKit

final class LayersController: UITableViewController {
    let document: RasterDocument
    var onChange: (() -> Void)?
    init(document: RasterDocument) { self.document = document; super.init(style: .insetGrouped) }
    required init?(coder: NSCoder) { fatalError("Use init(document:)") }
    override func viewDidLoad() {
        super.viewDidLoad(); title = "Layers"
        navigationItem.leftBarButtonItem = UIBarButtonItem(barButtonSystemItem: .add, target: self, action: #selector(addLayer))
        navigationItem.rightBarButtonItem = UIBarButtonItem(systemItem: .done, primaryAction: UIAction { [weak self] _ in self?.dismiss(animated: true) })
        tableView.register(UITableViewCell.self, forCellReuseIdentifier: "layer")
        tableView.allowsSelectionDuringEditing = true
        setEditing(true, animated: false)
    }
    private func index(_ path: IndexPath) -> Int { document.record.layers.count - 1 - path.row }
    @objc private func addLayer() {
        guard document.addLayer() else { showError(DrawingError.message("A drawing can contain up to 32 layers.")); return }
        changed()
    }
    private func changed() { tableView.reloadData(); onChange?() }
    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { document.record.layers.count }
    override func tableView(_ tableView: UITableView, titleForFooterInSection section: Int) -> String? {
        "Top layers paint above lower layers. Tap a row for settings; drag its handle to reorder. Deleting a layer clears stroke undo history."
    }
    override func tableView(_ tableView: UITableView, cellForRowAt path: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "layer", for: path)
        let layer = document.record.layers[index(path)]
        var content = cell.defaultContentConfiguration()
        content.text = layer.name
        content.secondaryText = "\(layer.visible ? "Visible" : "Hidden") · \(Int(layer.opacity * 100))%"
        content.image = UIImage(systemName: layer.id == document.activeLayer ? "checkmark.circle.fill" : "circle")
        cell.contentConfiguration = content
        return cell
    }
    override func tableView(_ tableView: UITableView, didSelectRowAt path: IndexPath) {
        let offset = index(path)
        document.record.selectedLayer = document.record.layers[offset].id
        changed()
        let settings = LayerSettingsController(document: document, id: document.activeLayer)
        settings.onChange = { [weak self] in self?.changed() }
        navigationController?.pushViewController(settings, animated: true)
    }
    override func tableView(_ tableView: UITableView, canMoveRowAt indexPath: IndexPath) -> Bool { true }
    override func tableView(_ tableView: UITableView, moveRowAt source: IndexPath, to destination: IndexPath) {
        let sourceIndex = index(source); let destinationIndex = index(destination)
        let layer = document.record.layers.remove(at: sourceIndex)
        document.record.layers.insert(layer, at: destinationIndex)
        changed()
    }
    override func tableView(_ tableView: UITableView, editingStyleForRowAt indexPath: IndexPath) -> UITableViewCell.EditingStyle {
        document.record.layers.count > 1 ? .delete : .none
    }
    override func tableView(_ tableView: UITableView, commit editingStyle: UITableViewCell.EditingStyle, forRowAt path: IndexPath) {
        guard editingStyle == .delete else { return }
        let layer = document.record.layers[index(path)]
        let alert = UIAlertController(title: "Delete \(layer.name)?", message: "This also clears stroke undo history.", preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        alert.addAction(UIAlertAction(title: "Delete", style: .destructive) { [weak self] _ in
            self?.document.deleteLayer(layer.id); self?.changed()
        })
        present(alert, animated: true)
    }
}

final class LayerSettingsController: UIViewController {
    let document: RasterDocument
    let id: UUID
    var onChange: (() -> Void)?
    init(document: RasterDocument, id: UUID) { self.document = document; self.id = id; super.init(nibName: nil, bundle: nil) }
    required init?(coder: NSCoder) { fatalError("Use init(document:id:)") }
    private func update(_ change: (inout LayerRecord) -> Void) {
        guard let index = document.record.layers.firstIndex(where: { $0.id == id }) else { return }
        change(&document.record.layers[index]); onChange?()
    }
    override func viewDidLoad() {
        super.viewDidLoad(); title = "Layer settings"; view.backgroundColor = .systemBackground
        guard let layer = document.record.layers.first(where: { $0.id == id }) else { return }
        let name = UITextField(); name.borderStyle = .roundedRect; name.text = layer.name; name.accessibilityLabel = "Layer name"
        name.addAction(UIAction { [weak self, weak name] _ in
            let text = (name?.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            if !text.isEmpty { self?.update { $0.name = text } }
        }, for: .editingChanged)
        let visible = UISwitch(); visible.isOn = layer.visible; visible.accessibilityLabel = "Layer visible"
        visible.addAction(UIAction { [weak self, weak visible] _ in self?.update { $0.visible = visible?.isOn ?? true } }, for: .valueChanged)
        let label = UILabel(); label.text = "Visible"
        let row = UIStackView(arrangedSubviews: [label, visible]); row.distribution = .equalSpacing
        let opacityLabel = UILabel(); opacityLabel.text = "Opacity"
        let opacity = UISlider(); opacity.minimumValue = 0; opacity.maximumValue = 1; opacity.value = Float(layer.opacity); opacity.accessibilityLabel = "Layer opacity"
        opacity.addAction(UIAction { [weak self, weak opacity] _ in self?.update { $0.opacity = Double(opacity?.value ?? 1) } }, for: .valueChanged)
        let stack = UIStackView(arrangedSubviews: [name, row, opacityLabel, opacity]); stack.axis = .vertical; stack.spacing = 20
        stack.translatesAutoresizingMaskIntoConstraints = false; view.addSubview(stack)
        NSLayoutConstraint.activate([stack.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 24),
            stack.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor, constant: 24),
            stack.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -24)])
    }
}
