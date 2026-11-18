import XCTest
import UIKit
import TipStrokeShared
@testable import TipStroke

final class SharedControlsTests: XCTestCase {
    private final class Actions: NSObject, AppleEditorActions {
        var brush: NativeBrush?
        var finger = false
        func brushChanged(brush: NativeBrush) { self.brush = brush }
        func colorChanged(red: Float, green: Float, blue: Float) {}
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

    func testSharedPanelsCreateRealUIKitControllers() {
        let bridge = AppleEditorBridge(actions: Actions())
        let brush = bridge.brushController()
        let color = bridge.colorController()
        brush.loadViewIfNeeded(); color.loadViewIfNeeded()
        XCTAssertNotNil(brush.view)
        XCTAssertNotNil(color.view)
    }
}
