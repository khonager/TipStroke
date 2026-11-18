import XCTest
import UIKit
@testable import TipStroke

final class RasterDocumentTests: XCTestCase {
    func testTileBoundsClipNegativeCoordinatesAndExactEdges() {
        XCTAssertEqual(TileKey.intersecting(CGRect(x: -20, y: -20, width: 276, height: 276), width: 512, height: 512), [TileKey(x: 0, y: 0)])
        XCTAssertEqual(Set(TileKey.intersecting(CGRect(x: 255, y: 255, width: 2, height: 2), width: 512, height: 512)),
                       Set([TileKey(x: 0, y: 0), TileKey(x: 1, y: 0), TileKey(x: 0, y: 1), TileKey(x: 1, y: 1)]))
        XCTAssertTrue(TileKey.intersecting(CGRect(x: 512, y: 0, width: 10, height: 10), width: 512, height: 512).isEmpty)
    }

    func testCrossTileStrokeUndoRedoAndCancellation() throws {
        let document = RasterDocument(name: "Test", width: 1024, height: 1024)
        XCTAssertTrue(document.beginStroke())
        try paint(document, from: CGPoint(x: 240, y: 30), to: CGPoint(x: 275, y: 30))
        document.endStroke(cancelled: false)
        XCTAssertEqual(document.pixels[document.activeLayer]?.count, 2)
        XCTAssertGreaterThan(try alpha(document, x: 255, y: 30), 200)
        XCTAssertGreaterThan(try alpha(document, x: 256, y: 30), 200)
        document.undo()
        XCTAssertEqual(document.pixels[document.activeLayer]?.count, 0)
        document.redo()
        XCTAssertGreaterThan(try alpha(document, x: 260, y: 30), 200)
        XCTAssertTrue(document.beginStroke())
        try paint(document, from: CGPoint(x: 700, y: 700), to: CGPoint(x: 720, y: 720))
        document.endStroke(cancelled: true)
        XCTAssertEqual(document.pixels[document.activeLayer]?.count, 2)
    }

    func testEraseIsTransparentAndUndoRestoresPixels() throws {
        let document = RasterDocument(name: "Erase", width: 512, height: 512)
        XCTAssertTrue(document.beginStroke())
        try paint(document, from: CGPoint(x: 30, y: 20), to: CGPoint(x: 80, y: 20))
        document.endStroke(cancelled: false)
        let original = try alpha(document, x: 50, y: 20)
        XCTAssertTrue(document.beginStroke())
        _ = try document.paint(from: CGPoint(x: 50, y: 20), to: CGPoint(x: 50, y: 20), radius: 10, color: .black, erasing: true)
        document.endStroke(cancelled: false)
        XCTAssertEqual(try alpha(document, x: 50, y: 20), 0)
        document.undo()
        XCTAssertEqual(try alpha(document, x: 50, y: 20), original)
        document.redo()
        XCTAssertEqual(try alpha(document, x: 50, y: 20), 0)
    }

    func testSaveSnapshotsAreImmutableAndRoundTripCoordinates() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = ProjectStore(root: root)
        let document = RasterDocument(name: "Round trip", width: 512, height: 512)
        XCTAssertTrue(document.beginStroke())
        try paint(document, from: CGPoint(x: 20, y: 15), to: CGPoint(x: 80, y: 15))
        document.endStroke(cancelled: false)
        let snapshot = document.snapshot()
        XCTAssertTrue(document.beginStroke())
        try paint(document, from: CGPoint(x: 20, y: 200), to: CGPoint(x: 80, y: 200))
        document.endStroke(cancelled: false)
        try store.save(snapshot)
        let reopened = RasterDocument(snapshot: try store.load(snapshot.record.id))
        XCTAssertGreaterThan(try alpha(reopened, x: 30, y: 15), 200)
        XCTAssertEqual(try alpha(reopened, x: 30, y: 200), 0)
        XCTAssertEqual(try store.list().map(\.id), [document.record.id])
        try store.save(document.snapshot())
        let latest = RasterDocument(snapshot: try store.load(document.record.id))
        XCTAssertGreaterThan(try alpha(latest, x: 30, y: 200), 200)
        XCTAssertEqual(try store.list().count, 1)
    }

    func testFailedSaveKeepsPreviousRevision() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = ProjectStore(root: root)
        let document = RasterDocument(name: "Keep me", width: 512, height: 512)
        try store.save(document.snapshot())
        var broken = document.snapshot()
        broken.record.name = "Should not be published"
        broken.record.layers[0].tiles = [TileKey(x: 0, y: 0)]
        XCTAssertThrowsError(try store.save(broken))
        XCTAssertEqual(try store.load(document.record.id).record.name, "Keep me")
    }

    func testCorruptTileFailsLoadInsteadOfReturningBlankCanvas() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = ProjectStore(root: root)
        let document = RasterDocument(name: "Corrupt", width: 512, height: 512)
        XCTAssertTrue(document.beginStroke())
        try paint(document, from: CGPoint(x: 20, y: 15), to: CGPoint(x: 80, y: 15))
        document.endStroke(cancelled: false)
        try store.save(document.snapshot())
        let folder = root.appendingPathComponent(document.record.id.uuidString)
        let revision = try String(contentsOf: folder.appendingPathComponent("current"), encoding: .utf8)
        let tile = folder.appendingPathComponent(revision).appendingPathComponent(document.activeLayer.uuidString).appendingPathComponent("0_0.png")
        try Data("broken".utf8).write(to: tile)
        XCTAssertThrowsError(try store.load(document.record.id))
    }

    func testLayersDoNotFlattenAndHiddenLayerRejectsInput() throws {
        let document = RasterDocument(name: "Layers", width: 512, height: 512)
        let original = document.activeLayer
        XCTAssertTrue(document.beginStroke()); try paint(document, from: .init(x: 40, y: 40), to: .init(x: 60, y: 40)); document.endStroke(cancelled: false)
        XCTAssertTrue(document.addLayer())
        XCTAssertNotEqual(document.activeLayer, original)
        XCTAssertNil(document.pixels[document.activeLayer])
        document.record.layers[1].visible = false
        XCTAssertFalse(document.beginStroke())
        try document.deleteLayer(document.activeLayer)
        XCTAssertEqual(document.activeLayer, original)
        XCTAssertTrue(document.canUndo)
        document.undo()
        XCTAssertEqual(document.record.layers.count, 2)
        document.redo()
        XCTAssertEqual(document.record.layers.count, 1)
        XCTAssertGreaterThan(try alpha(document, x: 50, y: 40), 200)
    }

    func testFutureVersionsAndUnsafeTileIndicesAreRejected() {
        var record = DocumentRecord()
        record.schemaVersion = 100
        XCTAssertThrowsError(try record.validate())
        record.schemaVersion = 1
        record.layers[0].tiles = [TileKey(x: Int.max, y: 0)]
        XCTAssertThrowsError(try record.validate())
        record.layers[0].tiles = [TileKey(x: -1, y: 0)]
        XCTAssertThrowsError(try record.validate())
    }

    func testOversizedStrokeCanBeCancelledWithoutLosingPreviousArtwork() throws {
        let document = RasterDocument(name: "Budget", width: 4096, height: 4096)
        XCTAssertTrue(document.beginStroke())
        try paint(document, from: .init(x: 10, y: 10), to: .init(x: 30, y: 10))
        document.endStroke(cancelled: false)
        XCTAssertTrue(document.beginStroke())
        XCTAssertThrowsError(try document.paint(from: .zero, to: CGPoint(x: 4095, y: 4095), radius: 8, color: .black, erasing: false))
        document.endStroke(cancelled: true)
        XCTAssertEqual(document.pixels[document.activeLayer]?.count, 1)
        XCTAssertGreaterThan(try alpha(document, x: 20, y: 10), 200)
    }

    func testOpeningLayerSettingsWithoutChangingAnythingKeepsRedo() throws {
        let document = RasterDocument(name: "No-op settings", width: 256, height: 256)
        XCTAssertTrue(document.beginStroke())
        try paint(document, from: .init(x: 20, y: 20), to: .init(x: 40, y: 20))
        document.endStroke(cancelled: false); document.undo()
        document.beginMetadataEdit(); document.endMetadataEdit()
        XCTAssertTrue(document.canRedo)
        XCTAssertFalse(document.canUndo)
        document.redo(); XCTAssertGreaterThan(try alpha(document, x: 30, y: 20), 200)
    }

    func testLayerLocksAndAlphaLockPreserveTransparency() throws {
        let document = RasterDocument(name: "Locks", width: 512, height: 512)
        document.record.layers[0].locked = true
        XCTAssertFalse(document.beginStroke())
        document.record.layers[0].locked = false
        XCTAssertTrue(document.beginStroke())
        try paint(document, from: CGPoint(x: 40, y: 40), to: CGPoint(x: 60, y: 40))
        document.endStroke(cancelled: false)
        document.record.layers[0].alphaLocked = true
        XCTAssertTrue(document.beginStroke())
        _ = try document.paint(from: .init(x: 50, y: 40), to: .init(x: 400, y: 40), radius: 10, color: .red, erasing: false)
        document.endStroke(cancelled: false)
        XCTAssertGreaterThan(try alpha(document, x: 50, y: 40), 200)
        XCTAssertEqual(try alpha(document, x: 80, y: 40), 0)
        XCTAssertEqual(document.pixels[document.activeLayer]?.count, 1)
        let sampled = try XCTUnwrap(document.color(at: .init(x: 50, y: 40)))
        var red: CGFloat = 0, green: CGFloat = 0, blue: CGFloat = 0, a: CGFloat = 0
        sampled.getRed(&red, green: &green, blue: &blue, alpha: &a)
        XCTAssertGreaterThan(red, 0.9); XCTAssertLessThan(green, 0.1)
    }

    func testDuplicateLayerKeepsIdentityAndIndependentPaintThroughUndo() throws {
        let document = RasterDocument(name: "Duplicate", width: 512, height: 512)
        let original = document.activeLayer
        XCTAssertTrue(document.beginStroke()); try paint(document, from: .init(x: 20, y: 20), to: .init(x: 40, y: 20)); document.endStroke(cancelled: false)
        try document.duplicateLayer(original)
        let copy = document.activeLayer
        XCTAssertNotEqual(copy, original)
        XCTAssertTrue(document.pixels[copy]?[TileKey(x: 0, y: 0)] === document.pixels[original]?[TileKey(x: 0, y: 0)])
        document.undo(); XCTAssertEqual(document.record.layers.count, 1)
        document.redo(); XCTAssertEqual(document.activeLayer, copy)
        XCTAssertTrue(document.beginStroke())
        _ = try document.paint(from: .init(x: 30, y: 20), to: .init(x: 30, y: 20), radius: 10, color: .black, erasing: true)
        document.endStroke(cancelled: false)
        XCTAssertEqual(try alpha(document, x: 30, y: 20), 0)
        document.record.selectedLayer = original
        XCTAssertGreaterThan(try alpha(document, x: 30, y: 20), 200)
    }

    func testImageImportAndUndoUseSparseTiles() throws {
        let source = try RasterDocument.context(width: 100, height: 50)
        source.setFillColor(UIColor.blue.cgColor); source.fill(CGRect(x: 0, y: 0, width: 100, height: 50))
        let image = try XCTUnwrap(source.makeImage())
        let tiles = try RasterDocument.imageTiles(image, width: 512, height: 512)
        XCTAssertEqual(tiles.count, 4)
        let document = RasterDocument(name: "Import", width: 512, height: 512)
        try document.importTiles(tiles, name: "Photo")
        XCTAssertEqual(document.record.layers.count, 2)
        XCTAssertEqual(try alpha(document, x: 256, y: 50), 0)
        XCTAssertGreaterThan(try alpha(document, x: 256, y: 256), 200)
        document.undo(); XCTAssertEqual(document.record.layers.count, 1)
        document.redo(); XCTAssertEqual(document.record.layers.last?.name, "Photo")
    }

    func testBlendModeCompositesAndPersistsWithoutFlattening() throws {
        let document = RasterDocument(name: "Blend", width: 256, height: 256)
        XCTAssertTrue(document.beginStroke())
        _ = try document.paint(from: .init(x: 40, y: 40), to: .init(x: 40, y: 40), radius: 15, color: .red, erasing: false)
        document.endStroke(cancelled: false)
        XCTAssertTrue(document.addLayer()); XCTAssertTrue(document.beginStroke())
        _ = try document.paint(from: .init(x: 40, y: 40), to: .init(x: 40, y: 40), radius: 15, color: .blue, erasing: false)
        document.endStroke(cancelled: false)
        document.beginMetadataEdit(); document.record.layers[1].blendMode = "MULTIPLY"; document.endMetadataEdit()
        let color = try XCTUnwrap(document.color(at: .init(x: 40, y: 40)))
        var r: CGFloat = 1, g: CGFloat = 1, b: CGFloat = 1, a: CGFloat = 0
        color.getRed(&r, green: &g, blue: &b, alpha: &a)
        XCTAssertLessThan(max(r, g, b), 0.1)
        document.undo(); XCTAssertEqual(document.record.layers[1].blendMode, "NORMAL")
        document.redo(); XCTAssertEqual(document.record.layers[1].blendMode, "MULTIPLY")
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = ProjectStore(root: root)
        try store.save(document.snapshot())
        let restored = try store.load(document.record.id)
        XCTAssertEqual(restored.record.schemaVersion, 2)
        XCTAssertEqual(restored.record.layers[1].blendMode, "MULTIPLY")
        XCTAssertEqual(restored.pixels.count, 2)
    }

    func testVersionOneDocumentsDefaultNewLayerProperties() throws {
        let record = DocumentRecord()
        var json = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(record)) as? [String: Any])
        json["schemaVersion"] = 1
        var layers = try XCTUnwrap(json["layers"] as? [[String: Any]])
        layers[0].removeValue(forKey: "blendMode"); layers[0].removeValue(forKey: "locked"); layers[0].removeValue(forKey: "alphaLocked")
        json["layers"] = layers
        let legacy = try JSONDecoder().decode(DocumentRecord.self, from: JSONSerialization.data(withJSONObject: json))
        try legacy.validate()
        XCTAssertEqual(legacy.layers[0].blendMode, "NORMAL")
        XCTAssertFalse(legacy.layers[0].locked)
        XCTAssertFalse(legacy.layers[0].alphaLocked)
    }

    func testTransparentExportDoesNotAddWhitePaper() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let document = RasterDocument(name: "Transparent", width: 32, height: 32)
        let store = ProjectStore(root: root)
        let url = try store.exportPNG(document.snapshot(), transparent: true)
        defer { try? FileManager.default.removeItem(at: url) }
        let image = try XCTUnwrap(UIImage(contentsOfFile: url.path)?.cgImage)
        let context = try RasterDocument.context(width: 32, height: 32)
        RasterDocument.draw(image, in: context, rect: CGRect(x: 0, y: 0, width: 32, height: 32))
        XCTAssertEqual(context.data!.assumingMemoryBound(to: UInt8.self)[3], 0)
    }

    private func paint(_ document: RasterDocument, from: CGPoint, to: CGPoint) throws {
        _ = try document.paint(from: from, to: to, radius: 5, color: .black, erasing: false)
    }
    private func alpha(_ document: RasterDocument, x: Int, y: Int) throws -> UInt8 {
        guard let image = document.pixels[document.activeLayer]?[TileKey(x: x / 256, y: y / 256)] else { return 0 }
        // Normalize decoder/native image formats before inspecting RGBA bytes.
        let context = try RasterDocument.context(width: 256, height: 256)
        RasterDocument.draw(image, in: context, rect: CGRect(x: 0, y: 0, width: 256, height: 256))
        let bytes = context.data!.assumingMemoryBound(to: UInt8.self)
        return bytes[((y % 256) * 256 + x % 256) * 4 + 3]
    }
}
