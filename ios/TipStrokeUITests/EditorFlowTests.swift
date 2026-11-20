import XCTest

final class EditorFlowTests: XCTestCase {
    func testCreateDrawUndoRedoSaveReopenAndRotate() {
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication()
        app.launch()
        app.navigationBars.buttons["Add"].tap()
        let create = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", "Create")).firstMatch
        XCTAssertTrue(create.waitForExistence(timeout: 10))
        let name = "UI drawing \(UUID().uuidString.prefix(8))"
        let nameField = app.textFields.firstMatch
        nameField.tap()
        // Replace default text without relying on a hardware keyboard shortcut.
        nameField.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: 8) + name)
        create.tap()
        let gallery = app.buttons["Gallery"]
        XCTAssertTrue(gallery.waitForExistence(timeout: 10))
        let tools = app.buttons["Drawing tools"]
        tools.tap()
        XCTAssertEqual(tools.value as? String, "Eraser")
        let anchor = tools.frame
        let origin = app.coordinate(withNormalizedOffset: .zero)
        // The four-column palette sits above the portrait dock. Smudge is the third cell.
        let gridHeight: CGFloat = 5 * 48 + 24
        let smudge = origin.withOffset(CGVector(dx: anchor.midX + 24 - app.frame.minX,
            dy: anchor.minY - gridHeight + 24 - app.frame.minY))
        tools.press(forDuration: 0.8, thenDragTo: smudge)
        XCTAssertEqual(tools.value as? String, "Smudge")
        app.segmentedControls["Drawing tool"].buttons["Ink"].tap()
        let start = app.coordinate(withNormalizedOffset: CGVector(dx: 0.35, dy: 0.45))
        let end = app.coordinate(withNormalizedOffset: CGVector(dx: 0.65, dy: 0.55))
        start.press(forDuration: 0.05, thenDragTo: end)
        XCTAssertTrue(app.buttons["Undo"].isEnabled)
        app.buttons["Undo"].tap()
        XCTAssertTrue(app.buttons["Redo"].isEnabled)
        app.buttons["Redo"].tap()
        // These elements come from Kotlin Compose panels, exercising the framework in the real app.
        app.buttons["Brush size, opacity and finger drawing"].tap()
        XCTAssertTrue(app.navigationBars["Brush settings"].waitForExistence(timeout: 10))
        let pressure = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", "Pressure")).firstMatch
        XCTAssertTrue(pressure.waitForExistence(timeout: 10))
        app.navigationBars.buttons["Done"].tap()
        app.buttons["Brush color"].tap()
        let wheel = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", "Wheel color picker")).firstMatch
        XCTAssertTrue(wheel.waitForExistence(timeout: 10))
        app.navigationBars.buttons["Done"].tap()
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
