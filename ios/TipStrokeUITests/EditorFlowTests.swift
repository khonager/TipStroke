import XCTest

final class EditorFlowTests: XCTestCase {
    func testCreateDrawUndoRedoSaveReopenAndRotate() {
        let app = XCUIApplication()
        app.launch()
        app.navigationBars.buttons["Add"].tap()
        let alert = app.alerts["New drawing"]
        XCTAssertTrue(alert.waitForExistence(timeout: 5))
        let name = "UI drawing \(UUID().uuidString.prefix(8))"
        let nameField = alert.textFields.element(boundBy: 0)
        nameField.tap()
        // Replace default text without relying on a hardware keyboard shortcut.
        nameField.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: 8) + name)
        alert.buttons["Create"].tap()
        let gallery = app.buttons["Gallery"]
        XCTAssertTrue(gallery.waitForExistence(timeout: 10))
        let start = app.coordinate(withNormalizedOffset: CGVector(dx: 0.35, dy: 0.45))
        let end = app.coordinate(withNormalizedOffset: CGVector(dx: 0.65, dy: 0.55))
        start.press(forDuration: 0.05, thenDragTo: end)
        XCTAssertTrue(app.buttons["Undo"].isEnabled)
        app.buttons["Undo"].tap()
        XCTAssertTrue(app.buttons["Redo"].isEnabled)
        app.buttons["Redo"].tap()
        XCUIDevice.shared.orientation = .landscapeLeft
        XCTAssertTrue(app.buttons["Layers"].isHittable)
        app.buttons["Layers"].tap()
        app.navigationBars.buttons["Add"].tap()
        XCTAssertTrue(app.staticTexts["Layer 2"].exists)
        app.navigationBars.buttons["Done"].tap()
        gallery.tap()
        XCTAssertTrue(app.staticTexts[name].waitForExistence(timeout: 15))
        app.terminate(); app.launch()
        app.staticTexts[name].tap()
        XCTAssertTrue(app.buttons["Gallery"].waitForExistence(timeout: 10))
        app.buttons["Layers"].tap()
        XCTAssertTrue(app.staticTexts["Layer 2"].exists)
        XCUIDevice.shared.orientation = .portrait
    }
}
