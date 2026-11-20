import XCTest
import UIKit
@testable import TipStroke

final class ProjectExchangeTests: XCTestCase {
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
