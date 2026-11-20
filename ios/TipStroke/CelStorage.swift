import UIKit
import ImageIO

typealias CelPixels = [UUID: [TileKey: CGImage]]
typealias CelFiles = [UUID: [TileKey: LocalMediaFile]]

enum CelStorage {
    static let queue = DispatchQueue(label: "dev.tipstroke.animation.cels", qos: .userInitiated)

    static func read(_ url: URL) throws -> CGImage {
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              properties[kCGImagePropertyPixelWidth] as? Int == 256, properties[kCGImagePropertyPixelHeight] as? Int == 256,
              let image = CGImageSourceCreateImageAtIndex(source, 0, [kCGImageSourceShouldCacheImmediately: true] as CFDictionary) else {
            throw DrawingError.message("An animation tile is missing or damaged.")
        }
        return image
    }
    static func decode(_ files: CelFiles) throws -> CelPixels {
        try files.mapValues { try $0.mapValues { file in try autoreleasepool { try read(file.url) } } }
    }
    static func encode(_ layers: CelPixels) throws -> CelFiles {
        try layers.mapValues { tiles in try tiles.mapValues { image in
            try autoreleasepool {
                let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathExtension("png")
                defer { try? FileManager.default.removeItem(at: url) }
                try ProjectStore.writePNG(image, to: url)
                return try LocalMediaFile(url)
            }
        } }
    }
    static func identical(_ lhs: CelPixels?, _ rhs: CelPixels) -> Bool {
        guard let lhs, lhs.count == rhs.count else { return false }
        return rhs.allSatisfy { id, tiles in
            guard let other = lhs[id], other.count == tiles.count else { return false }
            return tiles.allSatisfy { key, image in other[key] === image }
        }
    }
}

extension RasterDocument {
    /// A single worker evicts immutable inactive cels. Compare identity before publishing so
    /// revisiting or editing a cel during compression cannot replace newer paint.
    func archiveInactiveCels() {
        guard !archivingCel, let animation = record.animation, framePixels.count > 2 else { return }
        let keep = Set([animation.selectedIndex - 1, animation.selectedIndex, animation.selectedIndex + 1]
            .filter { animation.frames.indices.contains($0) }.map { animation.frames[$0].id })
        guard let (id, pixels) = framePixels.first(where: { !keep.contains($0.key) && !$0.value.values.allSatisfy(\.isEmpty) }) else { return }
        archivingCel = true
        CelStorage.queue.async {
            let result = Result { try CelStorage.encode(pixels) }
            DispatchQueue.main.async {
                self.archivingCel = false
                do {
                    let files = try result.get()
                    if let current = self.record.animation, let i = current.frames.firstIndex(where: { $0.id == id }),
                       abs(i - current.selectedIndex) > 1, CelStorage.identical(self.framePixels[id], pixels) {
                        self.coldFrames[id] = files; self.framePixels[id] = nil
                        self.archiveHistoryFrame(id, matching: pixels, files: files)
                    }
                    self.archiveInactiveCels()
                } catch { self.onCelError?(error) }
            }
        }
    }
    /// Decode only the requested cel and visible onion neighbors, with one outstanding request.
    func prepareFrame(_ index: Int, then action: @escaping () -> Void) {
        guard let a = record.animation, a.frames.indices.contains(index) else { return }
        let currentID = a.frames[index].id
        let indices = a.onionSkin ? [index-1, index, index+1] : [index]
        let requests = indices.filter { a.frames.indices.contains($0) }.compactMap { i -> (UUID, CelFiles)? in
            let id = a.frames[i].id
            return coldFrames[id].map { (id, $0) }
        }
        if requests.isEmpty { pendingCelSelection = nil; action(); return }
        pendingCelSelection = (currentID, action)
        guard !loadingCel else { return }
        loadingCel = true
        CelStorage.queue.async {
            let result = Result { try requests.map { ($0.0, try CelStorage.decode($0.1)) } }
            DispatchQueue.main.async {
                self.loadingCel = false
                guard let next = self.pendingCelSelection else { return }
                self.pendingCelSelection = nil
                do {
                    let decoded = try result.get()
                    if next.0 == currentID {
                        for ((id, pixels), (_, inputs)) in zip(decoded, requests) {
                            guard let current = self.coldFrames[id], current.count == inputs.count,
                                  inputs.allSatisfy({ layer, tiles in
                                      current[layer]?.count == tiles.count && tiles.allSatisfy { current[layer]?[$0.key] === $0.value }
                                  }) else { continue }
                            self.framePixels[id] = pixels; self.coldFrames[id] = nil
                        }
                    }
                    if let target = self.record.animation?.frames.firstIndex(where: { $0.id == next.0 }) {
                        self.prepareFrame(target, then: next.1)
                    }
                } catch { self.onCelError?(error) }
            }
        }
    }
}
