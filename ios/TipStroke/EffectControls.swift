import UIKit

extension EditorController {
    func effectMenu() {
        let menu = UIAlertController(title: "Blur & bloom", message: "Affects the active paint layer and current selection. Preview before applying.", preferredStyle: .actionSheet)
        for (index, title) in ["Gaussian blur", "Motion blur", "Perspective blur", "Bloom"].enumerated() {
            menu.addAction(UIAlertAction(title: title, style: .default) { [weak self] _ in self?.effectSettings(index, title: title) })
        }
        menu.addAction(UIAlertAction(title: "Cancel", style: .cancel)); anchor(menu); present(menu, animated: true)
    }
    private func effectSettings(_ kind: Int, title: String) {
        let alert = UIAlertController(title: title, message: "Radius: 1–32 document pixels" + (kind == 1 ? "\nAngle: −180…180°" : kind == 2 ? "\nCenter X and Y: 0…1" : kind == 3 ? "\nThreshold: 0…1 · Intensity: 0…3" : ""), preferredStyle: .alert)
        let values = kind == 1 ? ["8", "0"] : kind == 2 ? ["8", "0.5", "0.5"] : kind == 3 ? ["8", "0.7", "1"] : ["8"]
        let labels = kind == 1 ? ["Radius", "Angle"] : kind == 2 ? ["Radius", "Center X", "Center Y"] : kind == 3 ? ["Radius", "Threshold", "Intensity"] : ["Radius"]
        for (i, value) in values.enumerated() { alert.addTextField { $0.text = value; $0.placeholder = labels[i]; $0.accessibilityLabel = labels[i]; $0.keyboardType = .numbersAndPunctuation } }
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        alert.addAction(UIAlertAction(title: "Preview", style: .default) { [weak self, weak alert] _ in
            guard let self, let fields = alert?.textFields else { return }
            let values = fields.map { Float($0.text ?? "") ?? .nan }
            guard values.allSatisfy({ $0.isFinite }), (1...32).contains(values[0]),
                kind != 1 || (-180...180).contains(values[1]),
                kind != 2 || ((0...1).contains(values[1]) && (0...1).contains(values[2])),
                kind != 3 || ((0...1).contains(values[1]) && (0...3).contains(values[2])) else {
                self.showError(DrawingError.message("Enter values within the displayed ranges.")); return
            }
            var settings = EffectSettings(); settings.kind = kind; settings.radius = Int(values[0])
            if kind == 1 { settings.angle = values[1] }
            if kind == 2 { settings.centerX = values[1]; settings.centerY = values[2] }
            if kind == 3 { settings.threshold = values[1]; settings.intensity = values[2] }
            self.previewEffect(settings, title: title)
        })
        present(alert, animated: true)
    }
    private func previewEffect(_ settings: EffectSettings, title: String) {
        viewport.canvas.cancelStroke()
        let snapshot = document.snapshot(), layer = document.activeLayer, selection = document.selection
        guard let target = snapshot.record.layers.first(where: { $0.id == layer }), target.visible, !target.locked else { showError(DrawingError.message("Select a visible, unlocked paint layer.")); return }
        view.isUserInteractionEnabled = false
        ProjectStore.shared.queue.async {
            let result = Result { try RasterDocument.effectTiles(snapshot, layer: layer, selection: selection, settings: settings) }
            DispatchQueue.main.async {
                self.view.isUserInteractionEnabled = true
                do {
                    let tiles = try result.get()
                    var preview = snapshot
                    for (key, image) in tiles { preview.pixels[layer, default: [:]][key] = image }
                    self.viewport.canvas.previewSnapshot = preview; self.viewport.canvas.refresh()
                    let alert = UIAlertController(title: title, message: "Apply this preview to the active layer?", preferredStyle: .alert)
                    alert.addAction(UIAlertAction(title: "Cancel", style: .cancel) { _ in self.viewport.canvas.previewSnapshot = nil; self.viewport.canvas.refresh() })
                    alert.addAction(UIAlertAction(title: "Apply", style: .default) { _ in
                        self.viewport.canvas.previewSnapshot = nil
                        do { try self.document.replaceTiles(tiles); self.changed() } catch { self.viewport.canvas.refresh(); self.showError(error) }
                    })
                    self.present(alert, animated: true)
                } catch { self.showError(error) }
            }
        }
    }
}
