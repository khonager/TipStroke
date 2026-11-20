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
    static func identical(_ lhs: CelFiles?, _ rhs: CelFiles) -> Bool {
        guard let lhs, lhs.count == rhs.count else { return false }
        return rhs.allSatisfy { layer, files in
            lhs[layer]?.count == files.count && files.allSatisfy { key, file in lhs[layer]?[key] === file }
        }
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
        guard !archivingCel, !loadingCel, let animation = record.animation, framePixels.count > 2 else { return }
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
    /// Recording needs upcoming pixels without changing the selected cel or the active stroke.
    func prefetchRecordingFrame(_ id: UUID, completion: @escaping (Result<Void, Error>) -> Void) {
        guard let files = coldFrames[id] else { completion(.success(())); return }
        let cost = files.values.reduce(0) { $0 + $1.count }
        guard residentTileCount + cost <= Self.maximumTiles else {
            completion(.failure(DrawingError.message("The next recording cel exceeds the painting memory budget."))); return
        }
        CelStorage.queue.async {
            let result = Result { try CelStorage.decode(files) }
            DispatchQueue.main.async {
                do {
                    let decoded = try result.get()
                    if CelStorage.identical(self.coldFrames[id], files) {
                        guard self.residentTileCount + cost <= Self.maximumTiles else {
                            throw DrawingError.message("The drawing changed and the next cel no longer fits in memory.")
                        }
                        self.framePixels[id] = decoded; self.coldFrames[id] = nil
                    }
                    completion(.success(()))
                } catch { completion(.failure(error)) }
            }
        }
    }
    /// Decode only the requested cel and visible onion neighbors, with one outstanding request.
    func prepareFrame(_ index: Int, then action: @escaping () -> Void) {
        guard let a = record.animation, a.frames.indices.contains(index) else { return }
        let currentID = a.frames[index].id
        let activeID = a.frames[a.selectedIndex].id
        var available = Self.maximumTiles - residentTileCount
        let targetCost = coldFrames[currentID]?.values.reduce(0) { $0 + $1.count } ?? 0
        var evictions: [UUID: CelPixels] = [:]
        if targetCost > available {
            // Prepare inactive cels for eviction before reading a large destination.
            // Archive the outgoing cel too if these two frames cannot coexist.
            let candidates = framePixels.keys.filter { $0 != activeID && $0 != currentID }
            for id in candidates where available < targetCost {
                guard let layers = framePixels[id] else { continue }
                evictions[id] = layers
                available += layers.values.reduce(0) { $0 + $1.count }
            }
            if available < targetCost, activeID != currentID {
                let hidden = (framePixels[activeID] ?? [:]).filter { a.sharedLayerIDs.contains($0.key) }
                let outgoing = hidden.merging(pixels.filter { !a.sharedLayerIDs.contains($0.key) }) { _, current in current }
                evictions[activeID] = outgoing
                available += outgoing.values.reduce(0) { $0 + $1.count }
            }
        }
        guard targetCost <= available else {
            pendingCelSelection = nil
            onCelError?(DrawingError.message("The destination cel and shared artwork exceed the painting memory budget."))
            return
        }

        var requests: [(UUID, CelFiles)] = []
        if let files = coldFrames[currentID] { requests.append((currentID, files)); available -= targetCost }
        if a.onionSkin {
            for i in [index - 1, index + 1] where a.frames.indices.contains(i) {
                let id = a.frames[i].id
                guard let files = coldFrames[id] else { continue }
                let cost = files.values.reduce(0) { $0 + $1.count }
                if cost <= available { requests.append((id, files)); available -= cost }
            }
        }
        if requests.isEmpty { pendingCelSelection = nil; action(); return }
        pendingCelSelection = (currentID, action)
        guard !loadingCel else { return }
        loadingCel = true
        let startingRevision = revision
        let loads = requests, archives = evictions
        CelStorage.queue.async {
            let result = Result { () -> ([UUID: CelFiles], [CelPixels]) in
                let backups = try archives.mapValues(CelStorage.encode)
                return (backups, try loads.map { try CelStorage.decode($0.1) })
            }
            DispatchQueue.main.async {
                self.loadingCel = false
                guard let next = self.pendingCelSelection else { return }
                self.pendingCelSelection = nil
                guard let timeline = self.record.animation, let target = timeline.frames.firstIndex(where: { $0.id == next.0 }) else { return }
                guard next.0 == currentID, self.revision == startingRevision, timeline.frames[timeline.selectedIndex].id == activeID,
                      loads.allSatisfy({ CelStorage.identical(self.coldFrames[$0.0], $0.1) }) else {
                    self.prepareFrame(target, then: next.1); return
                }
                do {
                    let (backups, decoded) = try result.get()
                    func evict(_ id: UUID) {
                        guard let layers = archives[id], let files = backups[id],
                              CelStorage.identical(self.framePixels[id], layers) else { return }
                        self.coldFrames[id] = files; self.framePixels[id] = nil
                        self.archiveHistoryFrame(id, matching: layers, files: files)
                    }
                    for id in archives.keys where id != activeID { evict(id) }
                    for ((id, _), layers) in zip(loads, decoded) {
                        self.framePixels[id] = layers; self.coldFrames[id] = nil
                    }
                    next.1()
                    if let current = self.record.animation {
                        let selected = current.frames[current.selectedIndex].id
                        if selected != activeID { evict(activeID) }
                        // A canceled action may leave the original heavy frame active.
                        for ((id, files), layers) in zip(loads, decoded) where id != selected && self.residentTileCount > Self.maximumTiles {
                            if CelStorage.identical(self.framePixels[id], layers) {
                                self.framePixels[id] = nil; self.coldFrames[id] = files
                            }
                        }
                    }
                    self.archiveInactiveCels()
                } catch { self.onCelError?(error) }
            }
        }
    }
}
