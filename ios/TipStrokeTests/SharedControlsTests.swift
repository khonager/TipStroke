import XCTest
import UIKit
import TipStrokeShared
@testable import TipStroke

final class SharedControlsTests: XCTestCase {
    private final class Actions: NSObject, AppleEditorActions {
        var brush: NativeBrush?
        var finger = false
        func strokePreview(brush: NativeBrush) -> Data? { try? BrushSpecimen.image(brush) }
        func eraserChanged() {}
        func brushChanged(brush: NativeBrush) { self.brush = brush }
        func colorChanged(red: Float, green: Float, blue: Float) {}
        func swatchDrag(x: Float, y: Float, red: Float, green: Float, blue: Float, ended: Bool, cancelled: Bool) {}
        func fingerDrawingChanged(enabled: Bool) { finger = enabled }
    }

    func testLinkedKotlinPresetsAndRestoreDriveNativeSettings() {
        let actions = Actions()
        let bridge = AppleEditorBridge(actions: actions)
        bridge.restore(id: "pencil-v1", size: 32, opacity: 0.75, finger: true)
        XCTAssertEqual(actions.brush?.engine, "PENCIL")
        XCTAssertEqual(actions.brush?.size, 32)
        XCTAssertEqual(actions.brush?.opacity, 0.75)
        XCTAssertTrue(actions.finger)
        let saved = bridge.brushData()
        bridge.selectBrush(id: "airbrush-v1")
        XCTAssertEqual(actions.brush?.engine, "AIRBRUSH")
        XCTAssertTrue(bridge.restoreBrush(data: saved))
        XCTAssertEqual(actions.brush?.engine, "PENCIL")
        XCTAssertEqual(actions.brush?.size, 32)
        XCTAssertFalse(bridge.restoreBrush(data: "broken"))
    }

    func testImportedStampSourceRendersAcrossTilesAndLibraryRoundTrips() throws {
        let actions = Actions()
        let bridge = AppleEditorBridge(actions: actions)
        XCTAssertTrue(bridge.setTexture(encoded: Data([255, 255, 255, 255]).base64EncodedString(), width: 2, height: 2, grain: false))
        let brush = try XCTUnwrap(actions.brush)
        XCTAssertTrue(brush.usesStamps)
        let document = RasterDocument(name: "Stamp", width: 512, height: 256)
        let renderer = try NativeStampRenderer(brush: brush)
        let path = brush.path(size: 20, opacity: 1)
        XCTAssertTrue(document.beginStroke())
        _ = try renderer.paint(path.append(x: 250, y: 30, pressure: 1, tilt: 0, time: 0), color: .red, document: document, erasing: false)
        _ = try renderer.paint(path.append(x: 270, y: 30, pressure: 1, tilt: 0, time: 0.1), color: .red, document: document, erasing: false)
        document.endStroke(cancelled: false)
        let pixels = TilePixels(document.pixels[document.activeLayer] ?? [:])
        XCTAssertGreaterThan(try pixels.rgba(255, 30) & 255, 100)
        XCTAssertGreaterThan(try pixels.rgba(256, 30) & 255, 100)
        let saved = bridge.brushData()
        XCTAssertTrue(bridge.importBrushes(data: saved))
        let restored = AppleEditorBridge(actions: Actions())
        XCTAssertTrue(restored.restoreLibrary(data: bridge.libraryData()))
        XCTAssertFalse(restored.importBrushes(data: "not a brush"))
    }

    func testStampEraserPreservesTransparentPartOfImportedTip() throws {
        let actions = Actions()
        let bridge = AppleEditorBridge(actions: actions)
        var mask = Data(repeating: 255, count: 16 * 16)
        for y in 5...10 { for x in 5...10 { mask[y * 16 + x] = 0 } }
        XCTAssertTrue(bridge.setTexture(encoded: mask.base64EncodedString(), width: 16, height: 16, grain: false))
        let brush = try XCTUnwrap(actions.brush)
        let document = RasterDocument(name: "Erase stamp", width: 128, height: 128)
        XCTAssertTrue(document.beginStroke())
        _ = try document.paint(from: CGPoint(x: 32, y: 64), to: CGPoint(x: 96, y: 64),
                               radius: 30, color: .red, erasing: false)
        document.endStroke(cancelled: false)
        let renderer = try NativeStampRenderer(brush: brush)
        XCTAssertTrue(document.beginStroke())
        _ = try renderer.paint(brush.path(size: 40, opacity: 1).append(x: 64, y: 64, pressure: 1, tilt: 0, time: 0),
                               color: .white, document: document, erasing: true)
        document.endStroke(cancelled: false)
        let pixels = TilePixels(document.pixels[document.activeLayer] ?? [:])
        XCTAssertEqual(try pixels.rgba(64, 64) & 255, 255)
        XCTAssertLessThan(try pixels.rgba(49, 64) & 255, 255)
    }

    @MainActor
    func testSharedPanelsCreateRealUIKitControllers() {
        let bridge = AppleEditorBridge(actions: Actions())
        let brush = bridge.brushController()
        let color = bridge.colorController()
        brush.loadViewIfNeeded(); color.loadViewIfNeeded()
        XCTAssertNotNil(brush.view)
        XCTAssertNotNil(color.view)
    }
}
