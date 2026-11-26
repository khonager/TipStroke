import XCTest
import UIKit
@testable import TipStroke

final class ProjectExchangeTests: XCTestCase {
    func testGalleryMovieCreatesAndReopensDrawingWithMedia() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = ProjectStore(root: folder)
        let source = RasterDocument(name: "Movie", width: 96, height: 64)
        source.enableAnimation()
        let movie: URL = try await withCheckedThrowingContinuation { continuation in
            store.exportMP4(source.snapshot()) { continuation.resume(with: $0) }
        }
        defer { try? FileManager.default.removeItem(at: movie) }
        guard case .canvas(let imported) = try store.importGalleryFile(movie) else {
            return XCTFail("Gallery movie should open a canvas")
        }
        let saved = try store.load(imported.record.id)
        XCTAssertEqual(saved.record.width, 96)
        XCTAssertEqual(saved.record.height, 64)
        XCTAssertEqual(saved.record.animation?.frames.first?.sourceTimeUs, 0)
        XCTAssertEqual(saved.record.media?.assets.first?.kind, "video")
        XCTAssertEqual(saved.record.media?.clips.count, 1)
        XCTAssertTrue(RasterDocument(snapshot: saved).beginStroke())
    }

    func testPackageRetainsOriginalPhotoBytesAndSourceMask() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = ProjectStore(root: folder)
        let doc = RasterDocument(name: "Photo exchange", width: 64, height: 64)
        let ctx = try RasterDocument.context(width: 256, height: 128)
        ctx.setFillColor(UIColor.orange.cgColor); ctx.fill(CGRect(x: 0, y: 0, width: 256, height: 128))
        let source = try PhotoSource(image: XCTUnwrap(ctx.makeImage()))
        try doc.importPhoto(source, name: "Photo")
        let id = doc.activeLayer
        XCTAssertTrue(doc.beginStroke(photoErase: true))
        _ = try doc.paint(from: CGPoint(x: 35, y: 32), to: CGPoint(x: 35, y: 32), radius: 6, color: .black, erasing: true)
        doc.endStroke(cancelled: false)
        try store.save(doc.snapshot())
        let package = try store.exportPackage([doc.record.id])
        defer { try? FileManager.default.removeItem(at: package) }
        let importedIDs = try store.importPackage(package)
        let restored = try store.load(XCTUnwrap(importedIDs.first))
        let photo = try XCTUnwrap(restored.record.layers.last?.photo)
        XCTAssertEqual(photo.source?.encoded, source.encoded)
        XCTAssertEqual(photo.transform.tx, doc.activePhoto?.transform.tx ?? -1, accuracy: 0.001)
        XCTAssertEqual(Set(restored.pixels[id]!.keys), Set(doc.pixels[id]!.keys))
    }

    func testLongTimelineRoundTripPreservesDiskBackedCels() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = ProjectStore(root: folder)
        let doc = RasterDocument(name: "Long timeline", width: 256, height: 256)
        let layer = doc.activeLayer
        let key = TileKey(x: 0, y: 0)
        let context = try RasterDocument.context(width: 256, height: 256)
        context.setFillColor(UIColor.green.cgColor); context.fill(CGRect(x: 0, y: 0, width: 256, height: 256))
        let image = try XCTUnwrap(context.makeImage())
        let encoded = try CelStorage.encode([layer: [key: image]])
        var snapshot = doc.snapshot(), timeline = AnimationRecord()
        timeline.frames = (0..<241).map { _ in AnimationFrameRecord() }
        for index in 1...65 {
            timeline.frames[index].tiles = [layer.uuidString: [key]]
            snapshot.coldFrames[timeline.frames[index].id] = encoded
        }
        snapshot.record.animation = timeline
        try store.save(snapshot)
        let package = try store.exportPackage([doc.record.id])
        defer { try? FileManager.default.removeItem(at: package) }
        let imported = try store.load(XCTUnwrap(store.importPackage(package).first))
        XCTAssertEqual(imported.record.animation?.frames.count, 241)
        let tile = try XCTUnwrap(imported.coldFrames[timeline.frames[65].id]?[layer]?[key])
        XCTAssertEqual(try Data(contentsOf: tile.url), try Data(contentsOf: XCTUnwrap(encoded[layer]?[key]).url))
    }

    func testLayeredAnimationRoundTripRetainsExposurePaintAndIdentity() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = ProjectStore(root: folder)
        let document = RasterDocument(name: "Exchange", width: 128, height: 128)
        let base = document.activeLayer
        XCTAssertTrue(document.addLayer())
        let layer = document.activeLayer
        document.record.layers[1].clipSource = base
        document.enableAnimation()
        try document.addFrame(copy: false)
        document.record.animation?.frames[1].exposure = 7
        XCTAssertTrue(document.beginStroke())
        let point = CGPoint(x: 40, y: 30)
        _ = try document.paint(from: point, to: point, radius: 5, color: .red, erasing: false)
        document.endStroke(cancelled: false)
        try store.save(document.snapshot())
        let url = try store.exportPackage([document.record.id])
        defer { try? FileManager.default.removeItem(at: url) }
        let ids = try store.importPackage(url)
        let imported = try store.load(XCTUnwrap(ids.first))
        XCTAssertNotEqual(document.record.id, imported.record.id)
        XCTAssertEqual(imported.record.layers.map(\.id), [base, layer])
        XCTAssertEqual(imported.record.layers.last?.clipSource, base)
        XCTAssertEqual(imported.record.animation?.frames[1].exposure, 7)
        XCTAssertEqual(try TilePixels(imported.pixels[layer] ?? [:]).rgba(40, 30),
                       try TilePixels(document.pixels[layer] ?? [:]).rgba(40, 30))
    }
}
