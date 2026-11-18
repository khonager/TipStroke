import UIKit
import TipStrokeShared
import UniformTypeIdentifiers
import ImageIO

extension EditorController {
    func brushSources() {
        let menu = UIAlertController(title: "Brush sources", message: "Transparent images use their alpha. Opaque images use brightness: white paints, black is empty. Draw a source on a transparent layer, then capture it here.", preferredStyle: .actionSheet)
        for grain in [false, true] {
            let name = grain ? "grain" : "shape"
            menu.addAction(UIAlertAction(title: "Import \(name) image", style: .default) { [weak self] _ in
                guard let self else { return }; self.importingBrushTexture = grain
                let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.image], asCopy: true)
                picker.delegate = self; self.present(picker, animated: true)
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
    func importBrushTexture(_ url: URL, grain: Bool) {
        ProjectStore.shared.queue.async {
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            let result = Result { () -> String in
                guard (try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? Int.max) <= 16 * 1024 * 1024,
                      let source = CGImageSourceCreateWithURL(url as CFURL, nil),
                      let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [kCGImageSourceCreateThumbnailFromImageAlways: true,
                        kCGImageSourceCreateThumbnailWithTransform: true, kCGImageSourceThumbnailMaxPixelSize: 512] as CFDictionary) else {
                    throw DrawingError.message("Choose a readable source image smaller than 16 MiB.")
                }
                return try Self.textureData(image)
            }
            DispatchQueue.main.async { self.applyTexture(result, grain: grain) }
        }
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
    func showBrushLibrary() {
        viewport.canvas.cancelStroke()
        let controller = sharedControls.libraryController(onSave: { [weak self] data in
            self?.saveBrushLibrary(data); return KotlinUnit()
        }, onImport: { [weak self] in
            self?.dismiss(animated: true) {
                guard let self else { return }
                self.importingBrush = true
                let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.json, .data], asCopy: true)
                picker.delegate = self; self.present(picker, animated: true)
            }
            return KotlinUnit()
        }, onExport: { [weak self] data in
            self?.shareBrush(data); return KotlinUnit()
        })
        showShared(controller, title: "Tip library")
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
                    self.showBrushLibrary()
                } catch { self.showError(error) }
            }
        }
    }
    private func shareBrush(_ data: String) {
        ProjectStore.shared.queue.async {
            let url = FileManager.default.temporaryDirectory.appendingPathComponent("TipStroke-tip-\(UUID().uuidString).json")
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
