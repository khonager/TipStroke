import UIKit
import TipStrokeShared

final class LayersController: UITableViewController, UITableViewDragDelegate, UITableViewDropDelegate {
    private var previews: [UUID: UIImage] = [:]
    private var generation = 0
    let document: RasterDocument
    var onChange: (() -> Void)?
    var onImportImage: (() -> Void)?
    let sharedControls: AppleEditorBridge
    init(document: RasterDocument, sharedControls: AppleEditorBridge) {
        self.document = document; self.sharedControls = sharedControls; super.init(style: .insetGrouped)
    }
    required init?(coder: NSCoder) { fatalError("Use init(document:)") }
    override func viewDidLoad() {
        super.viewDidLoad(); title = "Layers"
        navigationItem.leftBarButtonItem = UIBarButtonItem(barButtonSystemItem: .add, target: self, action: #selector(addLayer))
        var configuration = UIButton.Configuration.plain()
        configuration.title = "Import image"
        configuration.image = UIImage(systemName: "photo.badge.plus")
        configuration.imagePadding = 8
        let importButton = UIButton(configuration: configuration)
        importButton.addAction(UIAction { [weak self] _ in
            guard let self else { return }
            self.dismiss(animated: true, completion: self.onImportImage)
        }, for: .touchUpInside)
        importButton.frame = CGRect(x: 0, y: 0, width: tableView.bounds.width, height: 44)
        importButton.isEnabled = document.record.layers.count < 32
        tableView.tableHeaderView = importButton
        navigationItem.rightBarButtonItem = sheetDoneButton()
        let blend = UIBarButtonItem(title: "Blend", menu: UIMenu(children: [
            UIDeferredMenuElement.uncached { [weak self] complete in
                guard let self, let layer = self.document.record.layers.first(where: { $0.id == self.document.activeLayer }) else {
                    complete([]); return
                }
                complete(LayerBlendMode.entries.map { mode in
                    UIAction(title: mode.label, state: mode.name == layer.blendMode ? .on : .off) { [weak self] _ in
                        guard let self, let i = self.document.record.layers.firstIndex(where: { $0.id == layer.id }),
                              self.document.record.layers[i].blendMode != mode.name else { return }
                        self.document.beginMetadataEdit()
                        self.document.record.layers[i].blendMode = mode.name
                        self.document.endMetadataEdit()
                        self.changed()
                    }
                })
            }
        ]))
        blend.accessibilityLabel = "Choose blend mode"
        if let done = navigationItem.rightBarButtonItem { navigationItem.rightBarButtonItems = [done, blend] }
        let multi = UIBarButtonItem(image: UIImage(systemName: "checkmark.circle"), menu: UIMenu(children: [
            UIDeferredMenuElement.uncached { [weak self] complete in
                guard let self else { complete([]); return }
                let ids = self.document.editingLayerIDs
                var actions: [UIMenuElement] = self.document.record.layers.reversed().map { layer in
                    UIAction(title: layer.name, attributes: layer.id == self.document.activeLayer ? .disabled : [],
                             state: ids.contains(layer.id) ? .on : .off) { _ in
                        var next = ids
                        if !next.insert(layer.id).inserted { next.remove(layer.id) }
                        self.document.chooseLayers(next); self.tableView.reloadData()
                    }
                }
                if ids.count > 1 {
                    actions.append(UIAction(title: "Group selected layers") { _ in self.document.groupSelection(); self.changed() })
                }
                if let group = self.document.record.layers.first(where: { $0.id == self.document.activeLayer })?.editGroup {
                    let members = Set(self.document.record.layers.filter { $0.editGroup == group }.map(\.id))
                    actions.append(UIAction(title: "Select group") { _ in self.document.chooseLayers(members); self.tableView.reloadData() })
                    actions.append(UIAction(title: "Ungroup") { _ in self.document.chooseLayers(members); self.document.ungroupSelection(); self.changed() })
                }
                complete(actions)
            }
        ]))
        multi.accessibilityLabel = "Select multiple layers"
        if let add = navigationItem.leftBarButtonItem { navigationItem.leftBarButtonItems = [add,multi] }
        tableView.register(UITableViewCell.self, forCellReuseIdentifier: "layer")
        tableView.allowsSelectionDuringEditing = true
        tableView.dragInteractionEnabled = true
        tableView.dragDelegate = self
        tableView.dropDelegate = self
    }
    private var expanded: UUID?
    private func index(_ path: IndexPath) -> Int { document.record.layers.count - 1 - path.row }
    @objc private func addLayer() {
        guard document.addLayer() else { showError(DrawingError.message("A drawing can contain up to 32 layers.")); return }
        changed()
    }
    private func changed() {
        (tableView.tableHeaderView as? UIButton)?.isEnabled = document.record.layers.count < 32
        generation += 1; previews.removeAll(); tableView.reloadData(); onChange?()
    }
    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { document.record.layers.count }
    override func tableView(_ tableView: UITableView, titleForFooterInSection section: Int) -> String? {
        "Top layers paint above lower layers. Swipe right to select layers. Drop onto a thumbnail to group, or beside the name to reorder. Layer edits can be undone from the canvas."
    }
    override func tableView(_ tableView: UITableView, cellForRowAt path: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "layer", for: path)
        let layer = document.record.layers[index(path)]
        var content = cell.defaultContentConfiguration()
        content.text = layer.name
        content.secondaryText = "\(layer.visible ? "Visible" : "Hidden") · \(Int(layer.opacity * 100))%"
        content.secondaryText = (content.secondaryText ?? "") + " · " + LayerBlendMode.companion.fromStorage(value: layer.blendMode).label
        content.image = previews[layer.id] ?? UIImage(systemName: "photo")
        content.imageProperties.reservedLayoutSize = CGSize(width: 40, height: 36)
        content.imageProperties.maximumSize = CGSize(width: 40, height: 36)
        cell.accessoryType = document.editingLayerIDs.contains(layer.id) ? .checkmark : .none
        if previews[layer.id] == nil && expanded != layer.id { loadPreview(layer, path: path) }
        if layer.editGroup != nil { content.secondaryText = (content.secondaryText ?? "") + " · Group" }
        cell.contentView.viewWithTag(104)?.removeFromSuperview()
        if expanded == layer.id {
            cell.contentConfiguration = nil
            let name = UITextField()
            name.borderStyle = .roundedRect; name.text = layer.name; name.accessibilityLabel = "Layer name"
            name.returnKeyType = .done
            name.addAction(UIAction { [weak name] _ in name?.resignFirstResponder() }, for: .editingDidEndOnExit)
            name.addAction(UIAction { [weak self, weak name] _ in
                guard let self, let text = name?.text?.trimmingCharacters(in: .whitespacesAndNewlines),
                      let i = self.document.record.layers.firstIndex(where: { $0.id == layer.id }) else { return }
                guard !text.isEmpty, text != self.document.record.layers[i].name else {
                    name?.text = self.document.record.layers[i].name; return
                }
                self.document.beginMetadataEdit(); self.document.record.layers[i].name = text
                self.document.endMetadataEdit(); self.onChange?()
            }, for: .editingDidEnd)
            let visibility = UIButton(type: .system)
            visibility.setImage(UIImage(systemName: layer.visible ? "eye" : "eye.slash"), for: .normal)
            visibility.accessibilityLabel = layer.visible ? "Hide layer" : "Show layer"
            visibility.addAction(UIAction { [weak self] _ in
                guard let self, let i = self.document.record.layers.firstIndex(where: { $0.id == layer.id }) else { return }
                self.document.beginMetadataEdit(); self.document.record.layers[i].visible.toggle()
                self.document.endMetadataEdit(); self.changed()
            }, for: .touchUpInside)
            let more = UIButton(type: .system)
            more.setImage(UIImage(systemName: "slider.horizontal.3"), for: .normal); more.accessibilityLabel = "Layer settings"
            more.addAction(UIAction { [weak self] _ in self?.openSettings(layer.id) }, for: .touchUpInside)
            let row = UIStackView(arrangedSubviews: [name, visibility, more]); row.tag = 104; row.spacing = 8
            row.translatesAutoresizingMaskIntoConstraints = false; cell.contentView.addSubview(row)
            let margins = cell.contentView.layoutMarginsGuide
            NSLayoutConstraint.activate([row.leadingAnchor.constraint(equalTo: margins.leadingAnchor),
                row.trailingAnchor.constraint(equalTo: margins.trailingAnchor), row.centerYAnchor.constraint(equalTo: margins.centerYAnchor),
                visibility.widthAnchor.constraint(equalToConstant: 44), more.widthAnchor.constraint(equalToConstant: 44)])
        } else { cell.contentConfiguration = content }
        return cell
    }
    override func tableView(_ tableView: UITableView, didSelectRowAt path: IndexPath) {
        let offset = index(path)
        document.record.selectedLayer = document.record.layers[offset].id
        expanded = expanded == document.activeLayer ? nil : document.activeLayer
        document.chooseLayers([])
        changed()
    }
    private func openSettings(_ id: UUID) {
        let settings = LayerSettingsController(document: document, id: id, sharedControls: sharedControls)
        settings.onChange = { [weak self] in self?.changed() }
        navigationController?.pushViewController(settings, animated: true)
    }
    private func loadPreview(_ layer: LayerRecord, path: IndexPath) {
        let version = generation
        var record = document.record
        var visible = layer; visible.visible = true; visible.opacity = 1; visible.clipSource = nil
        record.layers = [visible]; record.media = nil; record.backgroundOpacity = 0
        let snapshot = DocumentSnapshot(record: record, pixels: [layer.id: document.pixels[layer.id] ?? [:]])
        ProjectStore.shared.queue.async { [weak self] in
            guard let image = try? ProjectStore.thumbnail(snapshot) else { return }
            DispatchQueue.main.async {
                guard let self, self.generation == version else { return }
                self.previews[layer.id] = UIImage(cgImage: image)
                if let cell = self.tableView.cellForRow(at: path), var config = cell.contentConfiguration as? UIListContentConfiguration {
                    config.image = self.previews[layer.id]; cell.contentConfiguration = config
                }
            }
        }
    }
    override func tableView(_ tableView: UITableView, leadingSwipeActionsConfigurationForRowAt path: IndexPath) -> UISwipeActionsConfiguration? {
        let id = document.record.layers[index(path)].id
        let action = UIContextualAction(style: .normal, title: document.editingLayerIDs.contains(id) ? "Deselect" : "Select") { _, _, done in
            var ids = self.document.editingLayerIDs
            if ids.contains(id), ids.count > 1 {
                ids.remove(id)
                if self.document.activeLayer == id { self.document.record.selectedLayer = self.document.record.layers.first { ids.contains($0.id) }?.id }
            } else { ids.insert(id) }
            self.document.chooseLayers(ids); done(true); self.changed()
        }
        return UISwipeActionsConfiguration(actions: [action])
    }
    func tableView(_ tableView: UITableView, itemsForBeginning session: UIDragSession, at path: IndexPath) -> [UIDragItem] {
        let layer = document.record.layers[index(path)]
        let item = UIDragItem(itemProvider: NSItemProvider(object: layer.name as NSString))
        item.localObject = layer.id
        return [item]
    }
    private func target(_ session: UIDropSession) -> (UUID, Bool, Bool)? {
        let point = session.location(in: tableView)
        guard let path = tableView.indexPathForRow(at: point), let cell = tableView.cellForRow(at: path) else { return nil }
        let local = cell.convert(point, from: tableView)
        let group = expanded != document.record.layers[index(path)].id && local.x < cell.layoutMargins.left + 44
        if group && abs(local.y - cell.bounds.midY) > 19 { return nil }
        return (document.record.layers[index(path)].id, group, local.y < cell.bounds.midY)
    }
    func tableView(_ tableView: UITableView, dropSessionDidUpdate session: UIDropSession, withDestinationIndexPath destinationIndexPath: IndexPath?) -> UITableViewDropProposal {
        guard session.localDragSession != nil, let id = session.items.first?.localObject as? UUID,
              let (other, group, _) = target(session), other != id else { return UITableViewDropProposal(operation: .cancel) }
        return UITableViewDropProposal(operation: .move, intent: group ? .insertIntoDestinationIndexPath : .insertAtDestinationIndexPath)
    }
    func tableView(_ tableView: UITableView, performDropWith coordinator: UITableViewDropCoordinator) {
        guard let id = coordinator.items.first?.dragItem.localObject as? UUID,
              let source = document.record.layers.firstIndex(where: { $0.id == id }),
              let (target, group, above) = self.target(coordinator.session), target != id,
              let dest = document.record.layers.firstIndex(where: { $0.id == target }) else { return }
        document.beginMetadataEdit()
        let targetGroup = document.record.layers[dest].editGroup
        var layer = document.record.layers.remove(at: source)
        let members = document.record.layers.indices.filter {
            document.record.layers[$0].id == target || (targetGroup != nil && document.record.layers[$0].editGroup == targetGroup)
        }
        if group {
            let joined = targetGroup ?? UUID()
            layer.editGroup = joined
            if let i = document.record.layers.firstIndex(where: { $0.id == target }) { document.record.layers[i].editGroup = joined }
        } else { layer.editGroup = nil }
        let insert = group || above ? members.last! + 1 : members.first!
        document.record.layers.insert(layer, at: insert)
        for index in document.record.layers.indices {
            if let source = document.record.layers[index].clipSource,
               !document.record.layers.prefix(index).contains(where: { $0.id == source }) {
                document.record.layers[index].clipSource = nil
            }
        }
        document.endMetadataEdit()
        changed()
    }
    override func tableView(_ tableView: UITableView, editingStyleForRowAt indexPath: IndexPath) -> UITableViewCell.EditingStyle {
        document.record.layers.count > 1 ? .delete : .none
    }
    override func tableView(_ tableView: UITableView, commit editingStyle: UITableViewCell.EditingStyle, forRowAt path: IndexPath) {
        guard editingStyle == .delete else { return }
        let layer = document.record.layers[index(path)]
        let alert = UIAlertController(title: "Delete \(layer.name)?", message: "You can undo this from the canvas.", preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        alert.addAction(UIAlertAction(title: "Delete", style: .destructive) { [weak self] _ in
            guard let self else { return }
            do { try self.document.deleteLayer(layer.id); self.changed() }
            catch { self.showError(error) }
        })
        present(alert, animated: true)
    }
}

final class LayerSettingsController: UIViewController, AppleLayerBlendActions {
    func blendChanged(mode: String) { update { $0.blendMode = mode } }
    let document: RasterDocument
    let id: UUID
    var onChange: (() -> Void)?
    let sharedControls: AppleEditorBridge
    private var editingMetadata = false
    init(document: RasterDocument, id: UUID, sharedControls: AppleEditorBridge) {
        self.document = document; self.id = id; self.sharedControls = sharedControls; super.init(nibName: nil, bundle: nil)
    }
    required init?(coder: NSCoder) { fatalError("Use init(document:id:)") }
    private func update(_ change: (inout LayerRecord) -> Void) {
        guard let index = document.record.layers.firstIndex(where: { $0.id == id }) else { return }
        if !editingMetadata { document.beginMetadataEdit() }
        let wasReference = document.record.layers[index].reference
        change(&document.record.layers[index])
        if !wasReference && document.record.layers[index].reference {
            for other in document.record.layers.indices where other != index { document.record.layers[other].reference = false }
        }
        if !editingMetadata { document.endMetadataEdit() }
        onChange?()
    }
    override func viewDidLoad() {
        super.viewDidLoad(); title = "Layer settings"; view.backgroundColor = .systemBackground
        guard let layer = document.record.layers.first(where: { $0.id == id }) else { return }
        if layer.photo != nil {
            navigationItem.rightBarButtonItem = UIBarButtonItem(title: "Edit photo", primaryAction: UIAction { [weak self] _ in
                guard let self else { return }
                self.endAdjustment()
                do {
                    let editor = try TransformController(document: self.document, session: SelectionTransformSession(document: self.document))
                    editor.onApply = { [weak self] in self?.onChange?() }
                    let navigation = EditorSheetNavigationController(rootViewController: editor)
                    navigation.modalPresentationStyle = .fullScreen
                    self.present(navigation, animated: true)
                } catch { self.showError(error) }
            })
        }
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
        opacity.addAction(UIAction { [weak self] _ in self?.beginAdjustment() }, for: .touchDown)
        opacity.addAction(UIAction { [weak self] _ in self?.endAdjustment() }, for: [.touchUpInside, .touchUpOutside, .touchCancel])
        name.addAction(UIAction { [weak self] _ in self?.beginAdjustment() }, for: .editingDidBegin)
        name.addAction(UIAction { [weak self] _ in self?.endAdjustment() }, for: .editingDidEnd)
        func toggle(_ title: String, enabled: Bool, change: @escaping (inout LayerRecord, Bool) -> Void) -> UIView {
            let label = UILabel(); label.text = title
            let control = UISwitch(); control.isOn = enabled; control.accessibilityLabel = title
            control.addAction(UIAction { [weak self, weak control] _ in
                self?.update { change(&$0, control?.isOn ?? false) }
            }, for: .valueChanged)
            let row = UIStackView(arrangedSubviews: [label, control]); row.distribution = .equalSpacing
            return row
        }
        let lock = toggle("Lock layer", enabled: layer.locked) { $0.locked = $1 }
        let alpha = toggle("Lock transparency", enabled: layer.alphaLocked) { $0.alphaLocked = $1 }
        let blend = UIButton(type: .system); blend.setTitle("Blend mode", for: .normal)
        blend.addAction(UIAction { [weak self] _ in
            guard let self, let layer = self.document.record.layers.first(where: { $0.id == self.id }) else { return }
            self.beginAdjustment()
            let controller = self.sharedControls.blendController(layerId: self.id.uuidString, modeName: layer.blendMode, callbacks: self)
            self.navigationController?.pushViewController(controller, animated: true)
        }, for: .touchUpInside)
        let duplicate = UIButton(type: .system); duplicate.setTitle("Duplicate layer", for: .normal)
        duplicate.addAction(UIAction { [weak self] _ in
            guard let self else { return }
            do { try self.document.duplicateLayer(self.id); self.onChange?(); self.navigationController?.popViewController(animated: true) }
            catch { self.showError(error) }
        }, for: .touchUpInside)
        let reference = toggle("Use as Reference strokes", enabled: layer.reference) { $0.reference = $1 }
        let clip = UIButton(type: .system); clip.setTitle("Clipping source", for: .normal)
        clip.addAction(UIAction { [weak self] _ in
            guard let self, let index = self.document.record.layers.firstIndex(where: { $0.id == self.id }) else { return }
            let menu = UIAlertController(title: "Clip to layer", message: "Paint is visible only where the source layer has opacity.", preferredStyle: .actionSheet)
            menu.addAction(UIAlertAction(title: "None", style: .default) { _ in self.update { $0.clipSource = nil } })
            for source in self.document.record.layers.prefix(index) {
                menu.addAction(UIAlertAction(title: source.name, style: .default) { _ in self.update { $0.clipSource = source.id } })
            }
            menu.addAction(UIAlertAction(title: "Cancel", style: .cancel))
            menu.popoverPresentationController?.sourceView = clip; menu.popoverPresentationController?.sourceRect = clip.bounds
            self.present(menu, animated: true)
        }, for: .touchUpInside)
        let merge = UIButton(type: .system); merge.setTitle("Merge down", for: .normal)
        merge.isEnabled = document.record.animation == nil && document.record.layers.first?.id != id
        merge.addAction(UIAction { [weak self] _ in
            guard let self else { return }
            do { try self.document.mergeDown(self.id); self.onChange?(); self.navigationController?.popViewController(animated: true) }
            catch { self.showError(error) }
        }, for: .touchUpInside)
        let stack = UIStackView(arrangedSubviews: [name, row, opacityLabel, opacity, lock, alpha, reference, blend, clip, duplicate, merge]); stack.axis = .vertical; stack.spacing = 20
        let scroll = UIScrollView(); scroll.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(scroll); scroll.addSubview(stack); stack.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            scroll.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            scroll.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor),
            scroll.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor),
            scroll.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor),
            stack.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor, constant: 24),
            stack.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor, constant: -24),
            stack.leadingAnchor.constraint(equalTo: scroll.contentLayoutGuide.leadingAnchor, constant: 24),
            stack.trailingAnchor.constraint(equalTo: scroll.contentLayoutGuide.trailingAnchor, constant: -24),
            stack.widthAnchor.constraint(equalTo: scroll.frameLayoutGuide.widthAnchor, constant: -48)
        ])
    }
    private func beginAdjustment() {
        guard !editingMetadata else { return }
        document.beginMetadataEdit(); editingMetadata = true
    }
    private func endAdjustment() {
        guard editingMetadata else { return }
        document.endMetadataEdit(); editingMetadata = false; onChange?()
    }
    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated); endAdjustment()
    }
    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        if isMovingFromParent || navigationController?.isBeingDismissed == true { endAdjustment() }
    }
    deinit { if editingMetadata { document.endMetadataEdit() } }

}
