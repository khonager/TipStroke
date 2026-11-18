import UIKit
import TipStrokeShared

extension RasterDocument {
    func colorizeOutput() -> UUID? {
        if record.layers.first(where: { $0.id == activeLayer })?.colorizeMask != nil { return activeLayer }
        return record.layers.first { layer in
            guard let text = layer.colorizeMask, let recipe = try? NativeColorizeRecipe.companion.decode(text: text) else { return false }
            return recipe.lineArtId == activeLayer.uuidString || recipe.hintsId == activeLayer.uuidString
        }?.id
    }
    func colorizeRecipe(_ output: UUID) throws -> NativeColorizeRecipe {
        guard let text = record.layers.first(where: { $0.id == output })?.colorizeMask else { throw DrawingError.message("Select a colorize output or its input layers.") }
        let recipe = try NativeColorizeRecipe.companion.decode(text: text)
        guard record.layers.contains(where: { $0.id.uuidString == recipe.lineArtId }), record.layers.contains(where: { $0.id.uuidString == recipe.hintsId }) else {
            throw DrawingError.message("A colorize input layer is missing. The existing output has been preserved.")
        }
        return recipe
    }
    func createColorizeMask() throws -> UUID {
        guard record.animation == nil, record.layers.count <= 30,
              let index = record.layers.firstIndex(where: { $0.id == activeLayer }), record.layers[index].visible else {
            throw DrawingError.message("Colorize requires a visible line-art layer in a still drawing and two free layer slots.")
        }
        var output = LayerRecord(), hints = LayerRecord()
        output.name = "Colorize mask"; hints.name = "Colorize hints"
        output.colorizeMask = NativeColorizeRecipe(lineArtId: activeLayer.uuidString, hintsId: hints.id.uuidString, gap: 1, threshold: 0.2).encode()
        beginMetadataEdit()
        record.layers.insert(hints, at: index + 1); record.layers.insert(output, at: index)
        record.selectedLayer = hints.id; endMetadataEdit()
        return output.id
    }
    func applyColorize(_ tiles: [TileKey: CGImage], output: UUID, recipe: NativeColorizeRecipe) throws {
        guard record.animation == nil, let index = record.layers.firstIndex(where: { $0.id == output }), !record.layers[index].locked,
              let hints = record.layers.firstIndex(where: { $0.id.uuidString == recipe.hintsId }) else { throw DrawingError.message("The colorize output is unavailable or locked.") }
        _ = try colorizeRecipe(output)
        let oldCount = pixels[output]?.count ?? 0
        guard (tiles.count + oldCount) * Self.tileBytes + 256 <= Self.historyBudget,
              residentTileCount - oldCount + tiles.count <= Self.maximumTiles else { throw DrawingError.message("The generated color exceeds the undo or painting budget.") }
        beginMetadataEdit()
        pixels[output] = tiles
        record.layers[index].colorizeMask = recipe.encode(); record.layers[index].visible = true
        record.layers[hints].visible = false; record.selectedLayer = output
        endMetadataEdit(force: true)
    }
    static func colorizeTiles(_ snapshot: DocumentSnapshot, recipe: NativeColorizeRecipe) throws -> [TileKey: CGImage] {
        guard let lineID = UUID(uuidString: recipe.lineArtId), let hintsID = UUID(uuidString: recipe.hintsId) else { throw DrawingError.message("Invalid colorize inputs.") }
        let width = snapshot.record.width, height = snapshot.record.height
        let step = (max(width, height) + 1023) / 1024
        let w = (width + step - 1) / step, h = (height + step - 1) / step
        var ink = [UInt8](repeating: 0, count: w * h), hints = [Int32](repeating: 0, count: w * h)
        for (id, isInk) in [(lineID, true), (hintsID, false)] {
            for (key, image) in snapshot.pixels[id] ?? [:] {
                let context = try Self.context(width: 256, height: 256)
                Self.draw(image, in: context, rect: CGRect(x: 0, y: 0, width: 256, height: 256))
                let bytes = context.data!.assumingMemoryBound(to: UInt8.self)
                for y in 0..<min(256, height - key.y * 256) { for x in 0..<min(256, width - key.x * 256) {
                    let p = (y * 256 + x) * 4, a = Int(bytes[p + 3])
                    let index = ((key.y * 256 + y) / step) * w + (key.x * 256 + x) / step
                    if isInk {
                        let light = (Int(bytes[p]) * 54 + Int(bytes[p + 1]) * 183 + Int(bytes[p + 2]) * 19) / 256
                        ink[index] = max(ink[index], UInt8(max(0, a - light)))
                    } else if a > Int(UInt32(bitPattern: hints[index]) >> 24) {
                        hints[index] = argb(UInt32(bytes[p]) << 24 | UInt32(bytes[p + 1]) << 16 | UInt32(bytes[p + 2]) << 8 | UInt32(a))
                    }
                } }
            }
        }
        let sharedInk = KotlinByteArray(size: Int32(w * h)), sharedHints = KotlinIntArray(size: Int32(w * h))
        for i in ink.indices { sharedInk.set(index: Int32(i), value: Int8(bitPattern: ink[i])); sharedHints.set(index: Int32(i), value: hints[i]) }
        let labels = try NativeRasterEffects().colorize(width: Int32(w), height: Int32(h), ink: sharedInk, hints: sharedHints, gap: recipe.gap, threshold: recipe.threshold)
        var output: [TileKey: CGImage] = [:]
        for key in TileKey.intersecting(CGRect(x: 0, y: 0, width: width, height: height), width: width, height: height) {
            let context = try Self.context(width: 256, height: 256), bytes = context.data!.assumingMemoryBound(to: UInt8.self)
            var nonempty = false
            for y in 0..<min(256, height - key.y * 256) { for x in 0..<min(256, width - key.x * 256) {
                let value = labels.get(index: Int32(((key.y * 256 + y) / step) * w + (key.x * 256 + x) / step))
                nonempty = nonempty || value != 0; writeARGB(value, to: bytes, index: y * 256 + x)
            } }
            if nonempty {
                guard output.count < 64 else { throw DrawingError.message("Colorize exceeds the undo budget. Use a smaller drawing or transparent hints to leave regions empty.") }
                guard let image = context.makeImage() else { throw DrawingError.message("Could not generate color tiles.") }
                output[key] = image
            }
        }
        return output
    }
}

extension EditorController {
    func colorizeMenu() {
        viewport.canvas.cancelStroke()
        let output = document.colorizeOutput()
        let menu = UIAlertController(title: "Colorize mask", message: "Paint short color hints inside line art, then generate. Magenta marks transparent regions. Analysis is limited to 1024 pixels on the longest side; output stays at canvas resolution.", preferredStyle: .actionSheet)
        menu.addAction(UIAlertAction(title: output == nil ? "Create mask & paint hints" : "Edit color hints", style: .default) { [weak self] _ in
            guard let self else { return }
            do {
                let id = try output ?? self.document.createColorizeMask()
                let recipe = try self.document.colorizeRecipe(id)
                self.document.beginMetadataEdit()
                if let index = self.document.record.layers.firstIndex(where: { $0.id.uuidString == recipe.hintsId }) {
                    self.document.record.selectedLayer = self.document.record.layers[index].id
                    self.document.record.layers[index].visible = true; self.document.record.layers[index].locked = false
                }
                self.document.endMetadataEdit()
                self.document.selection = nil; self.document.paintInside = false; self.document.mirrorCopies = 0
                let canvas = self.viewport.canvas
                canvas.mode = .paint; canvas.elasticLine = false; canvas.perspectiveMode = 0
                canvas.tool = .ink; canvas.brush = NativeBrush.companion.builtIn(id: "ink-v1"); canvas.brushSize = 16; canvas.brushOpacity = 1
                canvas.refreshSelection(); self.changed()
            } catch { self.showError(error) }
        })
        if let output {
            menu.addAction(UIAlertAction(title: "Preview generation…", style: .default) { [weak self] _ in self?.colorizeSettings(output) })
            menu.addAction(UIAlertAction(title: "Use transparent hint color", style: .default) { [weak self] _ in self?.viewport.canvas.brushColor = .magenta })
        }
        menu.addAction(UIAlertAction(title: "Cancel", style: .cancel)); anchor(menu); present(menu, animated: true)
    }
    private func colorizeSettings(_ output: UUID) {
        do {
            let recipe = try document.colorizeRecipe(output)
            let alert = UIAlertController(title: "Generate colors", message: "Gap closing: 0–4 analysis pixels\nInk threshold: 0.02–1", preferredStyle: .alert)
            alert.addTextField { $0.text = String(recipe.gap); $0.placeholder = "Gap closing"; $0.accessibilityLabel = "Gap closing"; $0.keyboardType = .numberPad }
            alert.addTextField { $0.text = String(recipe.threshold); $0.placeholder = "Ink threshold"; $0.accessibilityLabel = "Ink threshold"; $0.keyboardType = .decimalPad }
            alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
            alert.addAction(UIAlertAction(title: "Preview", style: .default) { [weak self, weak alert] _ in
                guard let self, let fields = alert?.textFields, let gap = Int32(fields[0].text ?? ""), let threshold = Float(fields[1].text ?? ""),
                    (0...4).contains(gap), threshold.isFinite, (0.02...1).contains(threshold) else { return }
                let updated = NativeColorizeRecipe(lineArtId: recipe.lineArtId, hintsId: recipe.hintsId, gap: gap, threshold: threshold)
                self.previewColorize(output, recipe: updated)
            })
            present(alert, animated: true)
        } catch { showError(error) }
    }
    private func previewColorize(_ output: UUID, recipe: NativeColorizeRecipe) {
        let snapshot = document.snapshot()
        view.isUserInteractionEnabled = false
        ProjectStore.shared.queue.async {
            let result = Result { try RasterDocument.colorizeTiles(snapshot, recipe: recipe) }
            DispatchQueue.main.async {
                self.view.isUserInteractionEnabled = true
                do {
                    let tiles = try result.get()
                    var preview = snapshot; preview.pixels[output] = tiles
                    for i in preview.record.layers.indices {
                        if preview.record.layers[i].id.uuidString == recipe.hintsId { preview.record.layers[i].visible = false }
                        if preview.record.layers[i].id == output { preview.record.layers[i].visible = true }
                    }
                    self.viewport.canvas.previewSnapshot = preview; self.viewport.canvas.refresh()
                    let alert = UIAlertController(title: "Colorize preview", message: "Apply generated colors? Your line art and editable hints are preserved.", preferredStyle: .alert)
                    alert.addAction(UIAlertAction(title: "Cancel", style: .cancel) { _ in self.viewport.canvas.previewSnapshot = nil; self.viewport.canvas.refresh() })
                    alert.addAction(UIAlertAction(title: "Apply", style: .default) { _ in
                        self.viewport.canvas.previewSnapshot = nil
                        do { try self.document.applyColorize(tiles, output: output, recipe: recipe); self.changed() }
                        catch { self.viewport.canvas.refresh(); self.showError(error) }
                    })
                    self.present(alert, animated: true)
                } catch { self.showError(error) }
            }
        }
    }
}
