import XCTest
import UIKit
@testable import TipStroke

final class PhotoLayerTests: XCTestCase {
    func testSourceSpaceMaskFollowsPhotoAndRoundTrips() throws {
        let context = try RasterDocument.context(width: 512, height: 256)
        context.setFillColor(UIColor.red.cgColor)
        context.fill(CGRect(x: 0, y: 0, width: 512, height: 256))
        let source = try PhotoSource(image: XCTUnwrap(context.makeImage()))
        let document = RasterDocument(name: "Photo", width: 128, height: 128)
        try document.importPhoto(source, name: "Imported")
        let key = TileKey(x: 0, y: 0)
        func alpha(_ doc: RasterDocument, _ x: Int, _ y: Int) throws -> UInt32 {
            let image = try XCTUnwrap(RasterDocument.layerTile(doc.snapshot(), id: doc.activeLayer, key: key))
            return try TilePixels([key: image]).rgba(x, y) & 255
        }
        XCTAssertEqual(try alpha(document, 96, 64), 255)
        XCTAssertTrue(document.beginStroke(photoErase: true))
        _ = try document.paint(from: CGPoint(x: 96, y: 64), to: CGPoint(x: 96, y: 64), radius: 3, color: .black, erasing: true)
        document.endStroke(cancelled: false)
        XCTAssertEqual(try alpha(document, 96, 64), 0)
        XCTAssertNotNil(document.pixels[document.activeLayer]?[TileKey(x: 1, y: 0)])
        XCTAssertFalse(document.beginStroke())
        document.undo()
        XCTAssertEqual(try alpha(document, 96, 64), 255)
        document.redo()
        let pose = try XCTUnwrap(document.activePhoto).transform.concatenating(CGAffineTransform(translationX: -24, y: 0))
        try document.transformPhoto(pose)
        XCTAssertEqual(try alpha(document, 72, 64), 0)
        XCTAssertEqual(try alpha(document, 96, 64), 255)
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = ProjectStore(root: root)
        try store.save(document.snapshot())
        let restored = RasterDocument(snapshot: try store.load(document.record.id))
        XCTAssertEqual(restored.activePhoto?.width, 512)
        XCTAssertEqual(try alpha(restored, 72, 64), 0)
        try restored.transformPhoto(.identity)
        XCTAssertEqual(try alpha(restored, 96, 64), 255)
    }
}
