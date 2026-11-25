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
        let canvas = app.descendants(matching: .any)["Drawing canvas"]
        let fingerMode = app.buttons["Finger mode"]
        if fingerMode.value as? String != "Navigate canvas" { fingerMode.tap() }
        let before = canvas.frame.midX
        let finger = canvas.coordinate(withNormalizedOffset: CGVector(dx: 0.45, dy: 0.5))
        finger.press(forDuration: 0.1, thenDragTo: finger.withOffset(CGVector(dx: 60, dy: 0)))
        XCTAssertGreaterThan(canvas.frame.midX, before + 10)
        XCTAssertFalse(app.buttons["Undo"].isEnabled)
        app.buttons["Fit canvas"].tap()
        fingerMode.tap()
        XCTAssertEqual(fingerMode.value as? String, "Draw")
        for label in ["Size", "Opacity"] {
            let slider = app.sliders[label]
            XCTAssertTrue(slider.isHittable)
            let before = slider.value as? String
            slider.adjust(toNormalizedSliderPosition: 0.65)
            XCTAssertNotEqual(slider.value as? String, before)
        }
        let special = app.buttons["Special tools"]
        XCTAssertTrue(special.isHittable)
        special.tap()
        app.buttons["Pixel line"].tap()
        XCTAssertEqual(special.value as? String, "Pixel line")
        app.buttons["Tips"].tap()
        func element(_ label: String) -> XCUIElement {
            app.descendants(matching: .any).matching(NSPredicate(format: "identifier == %@ OR label == %@", label, label)).firstMatch
        }
        XCTAssertTrue(element("Choose drawing tip").waitForExistence(timeout: 10))
        element("Groups").tap()
        element("Open group Manga").tap()
        element("Choose Manga hatching").tap()
        let tools = app.buttons["Drawing tools"]
        tools.tap()
        XCTAssertEqual(tools.value as? String, "Eraser")
        tools.tap()
        XCTAssertTrue(element("Choose eraser tip").waitForExistence(timeout: 10))
        element("All").tap()
        element("Choose Pencil").tap()
        XCTAssertEqual(tools.value as? String, "Eraser")
        let anchor = tools.frame
        let origin = app.coordinate(withNormalizedOffset: .zero)
        // Smudge is the second cell in the first row, below the palette caption.
        let cellSize: CGFloat = 48
        let captionHeight: CGFloat = 24
        let gridHeight = 3 * cellSize + captionHeight
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
        let drawingArea = app.descendants(matching: .any)["Drawing canvas"]
        let point = drawingArea.coordinate(withNormalizedOffset: CGVector(dx: 0.4, dy: 0.45))
        movingTools.tap()
        app.buttons["Rectangle selection"].tap()
        point.press(forDuration: 0.15, thenDragTo: drawingArea.coordinate(withNormalizedOffset: CGVector(dx: 0.6, dy: 0.55)))
        let deselect = app.buttons["Deselect"]
        XCTAssertTrue(deselect.waitForExistence(timeout: 5))
        deselect.tap()
        XCTAssertFalse(deselect.exists)
        app.segmentedControls["Drawing tool"].buttons["Ink"].tap()
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
