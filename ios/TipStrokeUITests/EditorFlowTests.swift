import XCTest

final class EditorFlowTests: XCTestCase {
    func testCreateDrawUndoRedoSaveReopenAndRotate() {
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication()
        app.launch()
        let galleryCells = app.tables.cells.matching(NSPredicate(format: "identifier BEGINSWITH %@", "gallery-"))
        let existingIDs = galleryCells.allElementsBoundByIndex.map(\.identifier)
        app.navigationBars.buttons["Add"].tap()
        let create = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", "Create")).firstMatch
        XCTAssertTrue(create.waitForExistence(timeout: 10))
        create.tap()
        let gallery = app.buttons["Gallery"]
        XCTAssertTrue(gallery.waitForExistence(timeout: 10))
        let tools = app.buttons["Drawing tools"]
        tools.tap()
        XCTAssertEqual(tools.value as? String, "Eraser")
        let anchor = tools.frame
        let origin = app.coordinate(withNormalizedOffset: .zero)
        // Smudge is the second cell in the first row, below the palette caption.
        let cellSize: CGFloat = 48
        let captionHeight: CGFloat = 24
        let gridHeight = 4 * cellSize + captionHeight
        let smudge = origin.withOffset(CGVector(dx: anchor.midX - cellSize / 2 - app.frame.minX,
            dy: anchor.minY - gridHeight + captionHeight + cellSize / 2 - app.frame.minY))
        let toolCenter = tools.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        toolCenter.press(forDuration: 0.8, thenDragTo: smudge)
        XCTAssertEqual(tools.value as? String, "Smudge")
        let movingTools = app.buttons["Moving tools"]
        movingTools.tap()
        XCTAssertFalse(app.buttons["Smudge"].exists)
        app.buttons["Freehand selection"].tap()
        XCTAssertEqual(movingTools.value as? String, "Freehand selection")
        app.segmentedControls["Drawing tool"].buttons["Ink"].tap()
        let canvas = app.descendants(matching: .any)["Drawing canvas"]
        let start = canvas.coordinate(withNormalizedOffset: CGVector(dx: 0.40, dy: 0.45))
        let end = canvas.coordinate(withNormalizedOffset: CGVector(dx: 0.60, dy: 0.55))
        start.press(forDuration: 0.2, thenDragTo: end)
        for label in ["Undo", "Redo"] {
            let action = app.buttons[label]
            let enabled = XCTNSPredicateExpectation(predicate: NSPredicate(format: "enabled == true"), object: action)
            XCTAssertEqual(XCTWaiter.wait(for: [enabled], timeout: 5), .completed)
            action.tap()
        }
        // These elements come from Kotlin Compose panels, exercising the framework in the real app.
        app.buttons["Brush size, opacity and finger drawing"].tap()
        XCTAssertTrue(app.navigationBars["Brush settings"].waitForExistence(timeout: 10))
        let pressure = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", "Pressure")).firstMatch
        XCTAssertTrue(pressure.waitForExistence(timeout: 10))
        app.navigationBars.buttons["Done"].tap()
        app.buttons["Brush color"].tap()
        XCTAssertTrue(app.navigationBars["Colors"].waitForExistence(timeout: 10))
        let wheel = app.descendants(matching: .any).matching(NSPredicate(
            format: "identifier == %@ OR label CONTAINS[c] %@", "color-mode-HSV_WHEEL", "wheel"
        )).firstMatch
        XCTAssertTrue(wheel.waitForExistence(timeout: 10), app.debugDescription)
        app.navigationBars.buttons["Done"].tap()
        XCUIDevice.shared.orientation = .landscapeLeft
        XCTAssertTrue(app.buttons["Layers"].isHittable)
        app.buttons["Layers"].tap()
        app.navigationBars.buttons["Add"].tap()
        XCTAssertTrue(app.staticTexts["Layer 2"].exists)
        app.navigationBars.buttons["Done"].tap()
        gallery.tap()
        let newDrawing = galleryCells.matching(NSPredicate(format: "NOT (identifier IN %@)", existingIDs)).firstMatch
        XCTAssertTrue(newDrawing.waitForExistence(timeout: 15))
        let drawingID = newDrawing.identifier
        app.terminate(); app.launch()
        app.cells[drawingID].tap()
        XCTAssertTrue(app.buttons["Gallery"].waitForExistence(timeout: 10))
        app.buttons["Layers"].tap()
        XCTAssertTrue(app.staticTexts["Layer 2"].exists)
        XCUIDevice.shared.orientation = .portrait
    }
}
