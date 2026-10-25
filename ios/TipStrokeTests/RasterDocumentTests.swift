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
        document.deleteLayer(document.activeLayer)
        XCTAssertEqual(document.activeLayer, original)
        XCTAssertFalse(document.canUndo)
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
