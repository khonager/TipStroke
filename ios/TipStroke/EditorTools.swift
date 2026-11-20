import UIKit

extension EditorController {
    func refreshDrawingColors() {
        var snapshot = document.snapshot(); snapshot.record.backgroundOpacity = 0
        ProjectStore.shared.queue.async {
            do {
                let image = try ProjectStore.thumbnail(snapshot)
                let context = try RasterDocument.context(width: image.width, height: image.height)
                RasterDocument.draw(image, in: context, rect: CGRect(x: 0, y: 0, width: image.width, height: image.height))
                let bytes = context.data!.assumingMemoryBound(to: UInt8.self)
                var counts: [Int: Int] = [:]
                for index in 0..<(image.width * image.height) where bytes[index * 4 + 3] > 32 {
                    let alpha = Int(bytes[index * 4 + 3])
                    let r = min(255, Int(bytes[index * 4]) * 255 / alpha) / 16
                    let g = min(255, Int(bytes[index * 4 + 1]) * 255 / alpha) / 16
                    let b = min(255, Int(bytes[index * 4 + 2]) * 255 / alpha) / 16
                    counts[r << 8 | g << 4 | b, default: 0] += 1
                }
                let data = counts.sorted { $0.value == $1.value ? $0.key < $1.key : $0.value > $1.value }.prefix(8).map { item in
                    "\(Double((item.key >> 8) & 15) / 15),\(Double((item.key >> 4) & 15) / 15),\(Double(item.key & 15) / 15),1"
                }.joined(separator: ";")
                DispatchQueue.main.async { self.sharedControls.setDrawingColors(data: data) }
            } catch { DispatchQueue.main.async { self.showError(error) } }
        }
    }
    func showTools() {
        viewport.canvas.cancelStroke()
        let menu = UIAlertController(title: "Canvas tools", message: nil, preferredStyle: .actionSheet)
        menu.addAction(UIAlertAction(title: "Tip library · Create and share", style: .default) { [weak self] _ in self?.showBrushLibrary() })
        menu.addAction(UIAlertAction(title: "Manga tips · Screentone & hatching", style: .default) { [weak self] _ in self?.mangaTips() })
        menu.addAction(UIAlertAction(title: "Brush shape / grain sources", style: .default) { [weak self] _ in self?.brushSources() })
        let groups: [(String, [CanvasMode])] = [
            ("Paint tools", [.paint, .fill, .lassoFill, .ribbon, .smudge, .colorPush]),
            ("Pixel tools", [.pixel, .pixelLine, .dither]),
            ("Shapes", [.line, .rectangle, .ellipse, .triangle, .arrow]),
            ("Select an area", [.lasso, .rectangleSelection, .ellipseSelection, .transform])
        ]
        for (title, modes) in groups {
            menu.addAction(UIAlertAction(title: title, style: .default) { [weak self] _ in self?.chooseCanvasMode(modes, title: title) })
        }
        menu.addAction(UIAlertAction(title: viewport.canvas.filledShapes ? "Outline shapes" : "Filled shapes", style: .default) { [weak self] _ in self?.viewport.canvas.filledShapes.toggle() })
        menu.addAction(UIAlertAction(title: "Colorize mask", style: .default) { [weak self] _ in self?.colorizeMenu() })
        menu.addAction(UIAlertAction(title: "Blur & bloom", style: .default) { [weak self] _ in self?.effectMenu() })
        menu.addAction(UIAlertAction(title: "Brush aids", style: .default) { [weak self] _ in self?.brushAids() })
        menu.addAction(UIAlertAction(title: "Selection actions", style: .default) { [weak self] _ in self?.selectionActions() })
        menu.addAction(UIAlertAction(title: "Animation timeline", style: .default) { [weak self] _ in self?.showTimeline() })
        menu.addAction(UIAlertAction(title: "Paper color", style: .default) { [weak self] _ in self?.setPaperColor() })
        menu.addAction(UIAlertAction(title: "Resize canvas", style: .default) { [weak self] _ in self?.resizeCanvas() })
        menu.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        anchor(menu); present(menu, animated: true)
    }
    func showTimeline() {
        viewport.canvas.cancelStroke(); document.enableAnimation(); changed()
        let controller = TimelineController(document: document)
        controller.onChange = { [weak self] in self?.changed() }
        let navigation = UINavigationController(rootViewController: controller)
        navigation.modalPresentationStyle = .pageSheet
        navigation.sheetPresentationController?.detents = [.medium(), .large()]
        present(navigation, animated: true)
    }
    func setPaperColor() {
        let menu = UIAlertController(title: "Paper color", message: nil, preferredStyle: .actionSheet)
        for (name, color) in [("Current brush color", viewport.canvas.brushColor), ("White", UIColor.white), ("Transparent", UIColor.clear)] {
            menu.addAction(UIAlertAction(title: name, style: .default) { [weak self] _ in
                guard let self else { return }
                var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
                color.getRed(&r, green: &g, blue: &b, alpha: &a)
                self.document.beginMetadataEdit()
                self.document.record.backgroundARGB = 0xff000000 | UInt32(r * 255) << 16 | UInt32(g * 255) << 8 | UInt32(b * 255)
                self.document.record.backgroundOpacity = Double(a); self.document.endMetadataEdit(); self.changed()
            })
        }
        menu.addAction(UIAlertAction(title: "Cancel", style: .cancel)); anchor(menu); present(menu, animated: true)
    }
    func exportAnimationVideo() {
        guard !exporting else { return }; exporting = true
        viewport.canvas.cancelStroke()
        ProjectStore.shared.exportMP4(document.snapshot()) { result in
            DispatchQueue.main.async {
                self.exporting = false
                do {
                    let sheet = UIActivityViewController(activityItems: [try result.get()], applicationActivities: nil)
                    sheet.popoverPresentationController?.sourceView = self.view
                    sheet.popoverPresentationController?.sourceRect = CGRect(x: self.view.bounds.midX, y: 60, width: 1, height: 1)
                    self.present(sheet, animated: true)
                } catch { self.showError(error) }
            }
        }
    }
    func exportAnimation() {
        guard !exporting else { return }; exporting = true
        viewport.canvas.cancelStroke(); let snapshot = document.snapshot()
        ProjectStore.shared.queue.async {
            let result = Result { try ProjectStore.shared.exportGIF(snapshot) }
            DispatchQueue.main.async {
                self.exporting = false
                do {
                    let sheet = UIActivityViewController(activityItems: [try result.get()], applicationActivities: nil)
                    sheet.popoverPresentationController?.sourceView = self.view
                    sheet.popoverPresentationController?.sourceRect = CGRect(x: self.view.bounds.midX, y: 60, width: 1, height: 1)
                    self.present(sheet, animated: true)
                } catch { self.showError(error) }
            }
        }
    }
    func anchor(_ menu: UIAlertController) {
        menu.popoverPresentationController?.sourceView = view
        menu.popoverPresentationController?.sourceRect = CGRect(x: view.bounds.midX, y: view.bounds.maxY - 80, width: 1, height: 1)
    }
    func selectionActions() {
        let menu = UIAlertController(title: "Selection", message: nil, preferredStyle: .actionSheet)
        func action(_ name: String, _ body: @escaping () throws -> Void) {
            menu.addAction(UIAlertAction(title: name, style: .default) { [weak self] _ in
                do { try body(); self?.viewport.canvas.refreshSelection(); self?.changed() }
                catch { self?.showError(error) }
            })
        }
        action("Select all") { self.document.selection = CanvasSelection(path: CGPath(rect: self.document.bounds, transform: nil)) }
        action("Deselect") { self.document.selection = nil }
        action("Invert") { self.document.selection?.inverted.toggle() }
        action("Clear selected pixels") {
            if self.document.beginStroke() {
                do { try self.document.editTiles(in: self.document.bounds, erasing: true) { $0.fill(self.document.bounds) }; self.document.endStroke(cancelled: false) }
                catch { self.document.endStroke(cancelled: true); throw error }
            }
        }
        let bounds = document.selection?.path.boundingBoxOfPath ?? document.bounds
        func centered(_ transform: CGAffineTransform) -> CGAffineTransform {
            CGAffineTransform(translationX: bounds.midX, y: bounds.midY).concatenating(transform).translatedBy(x: -bounds.midX, y: -bounds.midY)
        }
        action("Shadow pull · drag selected paint") {
            guard self.document.selection != nil else { throw DrawingError.message("Select an area first.") }
            self.viewport.canvas.mode = .paint; self.viewport.canvas.shadowPull = true
        }
        action("Move") { self.viewport.canvas.mode = .transform }
        action("Copy and offset") { try self.document.transformSelection(CGAffineTransform(translationX: 24, y: 24), copy: true) }
        action("Flip horizontal") { try self.document.transformSelection(centered(CGAffineTransform(scaleX: -1, y: 1))) }
        action("Flip vertical") { try self.document.transformSelection(centered(CGAffineTransform(scaleX: 1, y: -1))) }
        action("Rotate 90°") { try self.document.transformSelection(centered(CGAffineTransform(rotationAngle: .pi / 2))) }
        action("Scale / rotate…") { self.transformDialog() }
        menu.addAction(UIAlertAction(title: "Cancel", style: .cancel)); anchor(menu); present(menu, animated: true)
    }
    private func transformDialog() {
        let alert = UIAlertController(title: "Transform selection", message: "Scale percent and rotation in degrees", preferredStyle: .alert)
        for value in ["100", "0"] { alert.addTextField { $0.text = value; $0.keyboardType = .numbersAndPunctuation } }
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        alert.addAction(UIAlertAction(title: "Apply", style: .default) { [weak self, weak alert] _ in
            guard let self, let fields = alert?.textFields, let scale = Double(fields[0].text ?? ""), let angle = Double(fields[1].text ?? ""),
                  scale.isFinite, angle.isFinite, scale >= 1, scale <= 1000 else { return }
            let bounds = self.document.selection?.path.boundingBoxOfPath ?? self.document.bounds
            let t = CGAffineTransform(translationX: bounds.midX, y: bounds.midY).rotated(by: angle * .pi / 180)
                .scaledBy(x: scale / 100, y: scale / 100).translatedBy(x: -bounds.midX, y: -bounds.midY)
            do { try self.document.transformSelection(t); self.viewport.canvas.refreshSelection(); self.changed() }
            catch { self.showError(error) }
        })
        present(alert, animated: true)
    }
    private func resizeCanvas() {
        let alert = UIAlertController(title: "Resize canvas", message: "Crop or extend from the top-left. Existing pixels stay at their original resolution.", preferredStyle: .alert)
        for value in [document.record.width, document.record.height] { alert.addTextField { $0.text = String(value); $0.keyboardType = .numberPad } }
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        alert.addAction(UIAlertAction(title: "Apply", style: .default) { [weak self, weak alert] _ in
            guard let self, let fields = alert?.textFields, let width = Int(fields[0].text ?? ""), let height = Int(fields[1].text ?? ""),
                  (16...4096).contains(width), (16...4096).contains(height) else { return }
            do {
                try self.document.resize(width: width, height: height)
                self.viewport.canvas.bounds = self.document.bounds; self.viewport.contentSize = self.document.bounds.size
                self.viewport.fit(); self.changed()
            } catch { self.showError(error) }
        })
        present(alert, animated: true)
    }
    func fill(at point: CGPoint) {
        let snapshot = document.snapshot(), layer = document.activeLayer
        guard let record = snapshot.record.layers.first(where: { $0.id == layer }), record.visible, !record.locked else { return }
        let selection = document.selection, color = viewport.canvas.brushColor.withAlphaComponent(viewport.canvas.brushOpacity)
        view.isUserInteractionEnabled = false
        ProjectStore.shared.queue.async {
            let result = Result { try RasterDocument.floodFill(snapshot, layerID: layer, point: point, color: color, tolerance: 24, selection: selection) }
            DispatchQueue.main.async {
                self.view.isUserInteractionEnabled = true
                do { try self.document.replaceTiles(result.get()); self.changed() }
                catch { self.showError(error) }
            }
        }
    }
}


extension EditorController {
    func brushAids() {
        viewport.canvas.cancelStroke()
        let menu = UIAlertController(title: "Brush aids", message: "Temporary aids stay active until switched off. Paint inside preserves the active layer’s existing transparency.", preferredStyle: .actionSheet)
        menu.addAction(UIAlertAction(title: "Perspective rulers", style: .default) { [weak self] _ in self?.perspectiveSettings() })
        menu.addAction(UIAlertAction(title: "Mirror wheel", style: .default) { [weak self] _ in self?.mirrorSettings() })
        menu.addAction(UIAlertAction(title: "Elastic line" + (viewport.canvas.elasticLine ? " ✓" : ""), style: .default) { [weak self] _ in self?.viewport.canvas.elasticLine.toggle() })
        menu.addAction(UIAlertAction(title: "Paint inside" + (document.paintInside ? " ✓" : ""), style: .default) { [weak self] _ in self?.document.paintInside.toggle() })
        menu.addAction(UIAlertAction(title: "Cancel", style: .cancel)); anchor(menu); present(menu, animated: true)
    }
}

extension EditorController {
    func mirrorSettings() {
        let menu = UIAlertController(title: "Mirror wheel", message: "Two copies reflect; higher counts repeat around the center. All copies undo together.", preferredStyle: .actionSheet)
        for count in [0, 2, 4, 6, 8, 12] {
            menu.addAction(UIAlertAction(title: (count == 0 ? "Off" : "\(count) copies") + (document.mirrorCopies == count ? " ✓" : ""), style: .default) { [weak self] _ in
                self?.document.mirrorCopies = count; self?.viewport.canvas.refreshSelection()
            })
        }
        menu.addAction(UIAlertAction(title: "Place center with next touch", style: .default) { [weak self] _ in self?.viewport.canvas.placingMirrorCenter = true })
        menu.addAction(UIAlertAction(title: "Cancel", style: .cancel)); anchor(menu); present(menu, animated: true)
    }
}

extension EditorController {
    func perspectiveSettings() {
        let menu = UIAlertController(title: "Magnetic perspective", message: "Brush strokes lock to the closest ruler direction. Rulers take priority over Elastic line. Place each vanishing point with the next touch.", preferredStyle: .actionSheet)
        for (index, title) in ["Off", "One point", "Two points"].enumerated() {
            menu.addAction(UIAlertAction(title: title + (viewport.canvas.perspectiveMode == index ? " ✓" : ""), style: .default) { [weak self] _ in
                self?.viewport.canvas.perspectiveMode = index; self?.viewport.canvas.refreshSelection()
            })
        }
        menu.addAction(UIAlertAction(title: "Enter coordinates · including off canvas", style: .default) { [weak self] _ in self?.vanishingPointCoordinates() })
        for index in 1...2 {
            menu.addAction(UIAlertAction(title: "Place vanishing point \(index)", style: .default) { [weak self] _ in self?.viewport.canvas.placingVanishingPoint = index })
        }
        menu.addAction(UIAlertAction(title: "Cancel", style: .cancel)); anchor(menu); present(menu, animated: true)
    }
}

extension EditorController {
    private func chooseCanvasMode(_ modes: [CanvasMode], title: String) {
        let menu = UIAlertController(title: title, message: modes.contains(.colorPush) ? "Color push uses size as diameter (up to 192 px) and opacity as strength." : nil, preferredStyle: .actionSheet)
        for mode in modes {
            menu.addAction(UIAlertAction(title: mode.rawValue + (viewport.canvas.mode == mode ? " ✓" : ""), style: .default) { [weak self] _ in
                self?.activateMode(mode)
            })
        }
        menu.addAction(UIAlertAction(title: "Cancel", style: .cancel)); anchor(menu); present(menu, animated: true)
    }
    private func vanishingPointCoordinates() {
        let canvas = viewport.canvas
        let p = canvas.vanishingPoint1 ?? CGPoint(x: canvas.bounds.width * 0.25, y: canvas.bounds.height * 0.35)
        let q = canvas.vanishingPoint2 ?? CGPoint(x: canvas.bounds.width * 0.75, y: canvas.bounds.height * 0.35)
        let alert = UIAlertController(title: "Vanishing point coordinates", message: "Document pixels; negative or off-canvas coordinates are allowed (±100,000).", preferredStyle: .alert)
        let labels = ["First X", "First Y", "Second X", "Second Y"]
        for (i, value) in [p.x, p.y, q.x, q.y].enumerated() { alert.addTextField { $0.text = String(Double(value)); $0.placeholder = labels[i]; $0.accessibilityLabel = labels[i]; $0.keyboardType = .numbersAndPunctuation } }
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        alert.addAction(UIAlertAction(title: "Apply", style: .default) { [weak self, weak alert] _ in
            guard let self, let fields = alert?.textFields else { return }
            let values = fields.map { Double($0.text ?? "") ?? .nan }
            guard values.allSatisfy({ $0.isFinite && abs($0) <= 100_000 }) else { self.showError(DrawingError.message("Enter finite coordinates between −100,000 and 100,000.")); return }
            canvas.vanishingPoint1 = CGPoint(x: values[0], y: values[1]); canvas.vanishingPoint2 = CGPoint(x: values[2], y: values[3]); canvas.refreshSelection()
        })
        present(alert, animated: true)
    }
}
