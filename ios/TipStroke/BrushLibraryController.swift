import UIKit
import TipStrokeShared
import UniformTypeIdentifiers
import ImageIO

extension EditorController: AppleBrushLibraryActions {
    func openTipSlot(slot: Int32) { showTipPicker(eraser: false, slot: slot) }

    func tipFolder(tips: [NativeBrush], label: String, title: String) -> UIView {
        let button = UIButton(type: .custom)
        button.accessibilityLabel = label
        button.showsMenuAsPrimaryAction = true
        button.backgroundColor = UIColor(white: 0.2, alpha: 1)
        button.layer.cornerRadius = 7
        func thumbnail(_ tip: NativeBrush) -> UIImage? {
            (try? BrushSpecimen.image(tip, eraser: false)).flatMap { UIImage(data: $0) }?
                .preparingThumbnail(of: CGSize(width: 48, height: 32))?.withRenderingMode(.alwaysOriginal)
        }
        for (index, tip) in tips.prefix(4).enumerated() {
            let cell = UIImageView(frame: CGRect(x: 2 + (index % 2) * 20, y: 2 + (index / 2) * 20,
                                                 width: 19, height: 19))
            cell.contentMode = .scaleAspectFit
            button.addSubview(cell)
            DispatchQueue.global(qos: .userInitiated).async {
                let image = thumbnail(tip)
                DispatchQueue.main.async { cell.image = image }
            }
        }
        let contents = UIDeferredMenuElement { [weak self] complete in
            DispatchQueue.global(qos: .userInitiated).async {
                let images = tips.map(thumbnail)
                DispatchQueue.main.async {
                    complete(zip(tips, images).map { tip, image in
                        UIAction(title: tip.name, image: image) { [weak self] _ in self?.pickedTip(id: tip.id, eraser: false) }
                    })
                }
            }
        }
        button.menu = UIMenu(title: title, children: [contents])
        return button
    }

    func brushSources() {
        let menu = UIAlertController(title: "Brush sources", message: "Transparent images use their alpha. Opaque images use brightness: white paints, black is empty. Draw a source on a transparent layer, then capture it here.", preferredStyle: .actionSheet)
        for grain in [false, true] {
            let name = grain ? "grain" : "shape"
            menu.addAction(UIAlertAction(title: "Import \(name) image", style: .default) { [weak self] _ in
                self?.choosePhoto { [weak self] url, _ in
                    let texture = try Self.readBrushTexture(url)
                    return { self?.applyTexture(.success(texture), grain: grain) }
                }
            })
            menu.addAction(UIAlertAction(title: "Capture selected layer as \(name)", style: .default) { [weak self] _ in self?.captureBrushTexture(grain: grain) })
            menu.addAction(UIAlertAction(title: "Invert \(name)", style: .default) { [weak self] _ in self?.sharedControls.editTexture(grain: grain, rotate: false) })
            menu.addAction(UIAlertAction(title: "Rotate \(name)", style: .default) { [weak self] _ in self?.sharedControls.editTexture(grain: grain, rotate: true) })
        }
        menu.addAction(UIAlertAction(title: "Cancel", style: .cancel)); anchor(menu); present(menu, animated: true)
    }
    private static func textureData(_ image: CGImage) throws -> String {
        let context = try RasterDocument.context(width: 256, height: 256)
        let scale = 256 / CGFloat(max(image.width, image.height))
        let size = CGSize(width: CGFloat(image.width) * scale, height: CGFloat(image.height) * scale)
        let rect = CGRect(x: (256 - size.width) / 2, y: (256 - size.height) / 2, width: size.width, height: size.height)
        RasterDocument.draw(image, in: context, rect: rect)
        let pixels = context.data!.assumingMemoryBound(to: UInt8.self)
        // Ignore letterboxing when deciding whether the source itself has transparency.
        var alphaSource = false
        for y in Int(ceil(rect.minY))..<Int(floor(rect.maxY)) { for x in Int(ceil(rect.minX))..<Int(floor(rect.maxX)) {
            if pixels[(y * 256 + x) * 4 + 3] < 255 { alphaSource = true }
        } }
        var coverage = Data(count: 256 * 256)
        for index in 0..<(256 * 256) {
            let offset = index * 4
            coverage[index] = alphaSource ? pixels[offset + 3] : UInt8((Int(pixels[offset]) * 54 + Int(pixels[offset + 1]) * 183 + Int(pixels[offset + 2]) * 19) / 256)
        }
        return coverage.base64EncodedString()
    }
    private static func readBrushTexture(_ url: URL) throws -> String {
        guard (try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? Int.max) <= 16 * 1024 * 1024,
              let source = CGImageSourceCreateWithURL(url as CFURL, nil),
              let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true, kCGImageSourceThumbnailMaxPixelSize: 512] as CFDictionary) else {
            throw DrawingError.message("Choose a readable source image smaller than 16 MiB.")
        }
        return try Self.textureData(image)
    }
    private func captureBrushTexture(grain: Bool) {
        viewport.canvas.cancelStroke()
        var snapshot = document.snapshot()
        snapshot.record.layers = snapshot.record.layers.filter { $0.id == document.activeLayer }
        snapshot.record.layers[0].visible = true; snapshot.record.layers[0].clipSource = nil
        snapshot.record.backgroundOpacity = 0
        ProjectStore.shared.queue.async {
            let result = Result { try Self.textureData(ProjectStore.thumbnail(snapshot)) }
            DispatchQueue.main.async { self.applyTexture(result, grain: grain) }
        }
    }
    private func applyTexture(_ result: Result<String, Error>, grain: Bool) {
        do {
            guard sharedControls.setTexture(encoded: try result.get(), width: 256, height: 256, grain: grain) else {
                throw DrawingError.message("Could not apply this brush source.")
            }
            showShared(sharedControls.brushController(), title: "Brush settings")
        } catch { showError(error) }
    }
    func showTipPicker(eraser: Bool, slot: Int32 = -1) {
        finishCanvasInteraction()
        let controller = sharedControls.pickerController(callbacks: self, eraser: eraser, slot: slot)
        controller.modalPresentationStyle = .pageSheet
        controller.sheetPresentationController?.detents = [.large()]
        present(controller, animated: true)
    }
    func pickedTip(id: String, eraser: Bool) {
        finishCanvasInteraction()
        if eraser { viewport.canvas.tool = .eraser; activateEraserBrush() }
        else if let mode = CanvasMode.allCases.first(where: { $0.pixelTip?.tipId == id }) { activateMode(mode) }
        else {
            viewport.canvas.tool = .ink
            sharedControls.selectBrush(id: id)
        }
    }
    func closePicker() { presentedViewController?.dismiss(animated: true) }
    func openLibrary(slot: Int32) {
        dismiss(animated: true) { [weak self] in self?.showBrushLibrary(slot: slot) }
    }

    func showBrushLibrary(openGroups: Bool = false, slot: Int32 = -1) {
        brushLibrarySlot = slot
        viewport.canvas.cancelStroke()
        showShared(sharedControls.libraryController(callbacks: self, openGroups: openGroups, slot: slot), title: "Tip library")
    }
    func librarySaved(data: String) { saveBrushLibrary(data) }
    func exportToFile(data: String) { shareBrush(data) }
    func importFromFile() {
        dismiss(animated: true) { [weak self] in
            guard let self else { return }
            let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.json, .data], asCopy: true)
            picker.delegate = self
            self.present(picker, animated: true)
        }
    }
    func saveBrushLibrary(_ data: String) {
        ProjectStore.shared.queue.async {
            do {
                try FileManager.default.createDirectory(at: ProjectStore.shared.root, withIntermediateDirectories: true)
                try Data(data.utf8).write(to: ProjectStore.shared.root.appendingPathComponent("brush-library.json"), options: .atomic)
            } catch { DispatchQueue.main.async { self.showError(error) } }
        }
    }
    func importBrushFile(_ url: URL) {
        ProjectStore.shared.queue.async {
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            let result = Result { () -> String in
                let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
                guard size <= 32 * 1024 * 1024 else { throw DrawingError.message("This brush file exceeds 32 MiB.") }
                return try String(contentsOf: url, encoding: .utf8)
            }
            DispatchQueue.main.async {
                do {
                    guard self.sharedControls.importBrushes(data: try result.get()) else { throw DrawingError.message("Unsupported, damaged or oversized tip library.") }
                    self.saveBrushLibrary(self.sharedControls.libraryData())
                    self.showBrushLibrary(slot: self.brushLibrarySlot)
                } catch { self.showError(error) }
            }
        }
    }
    private func shareBrush(_ data: String) {
        ProjectStore.shared.queue.async {
            let url = FileManager.default.temporaryDirectory.appendingPathComponent("TipStroke-tip-\(UUID().uuidString).tipbrush")
            do {
                try Data(data.utf8).write(to: url, options: .atomic)
                DispatchQueue.main.async {
                    let sheet = UIActivityViewController(activityItems: [url], applicationActivities: nil)
                    let host = self.presentedViewController ?? self
                    sheet.popoverPresentationController?.sourceView = host.view
                    sheet.popoverPresentationController?.sourceRect = CGRect(x: host.view.bounds.midX, y: host.view.bounds.midY, width: 1, height: 1)
                    host.present(sheet, animated: true)
                }
            } catch { DispatchQueue.main.async { self.showError(error) } }
        }
    }
}

extension EditorController {
    func mangaTips() {
        let menu = UIAlertController(title: "Manga tips", message: "Size controls painted coverage. Grain scale controls pattern spacing. Use full opacity for crisp tones; keep spacing consistent across a page to avoid moiré. Paint inside or a selection keeps shading in its shape.", preferredStyle: .actionSheet)
        for (id, name) in [("dots-10", "Dots 10%"), ("dots-30", "Dots 30%"), ("dots-50", "Dots 50%"), ("hatch", "Hatching"), ("crosshatch", "Crosshatching"), ("lines", "Parallel shade")] {
            menu.addAction(UIAlertAction(title: name, style: .default) { [weak self] _ in
                guard let self else { return }
                self.viewport.canvas.mode = .paint; self.viewport.canvas.tool = .ink
                self.sharedControls.selectBrush(id: "manga-\(id)-v1")
            })
        }
        menu.addAction(UIAlertAction(title: "Cancel", style: .cancel)); anchor(menu); present(menu, animated: true)
    }
}
