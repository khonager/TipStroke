import XCTest

final class EditorFlowTests: XCTestCase {
    func testCreateDrawUndoRedoSaveReopenAndRotate() throws {
        continueAfterFailure = false
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication()
        app.launch()
        app.navigationBars.buttons["Add"].tap()
        let create = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", "Create")).firstMatch
        XCTAssertTrue(create.waitForExistence(timeout: 10))
        create.tap()
        let gallery = app.buttons["Gallery"]
        XCTAssertTrue(gallery.waitForExistence(timeout: 10))
        let canvas = app.descendants(matching: .any)["Drawing canvas"]
        let drawingID = try XCTUnwrap(UUID(uuidString: canvas.identifier))
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
        XCTAssertFalse(app.segmentedControls["Drawing tool"].exists)
        XCTAssertFalse(app.staticTexts["Saved on this device"].exists)
        XCTAssertLessThan(app.buttons["Brush color"].frame.midY, app.buttons["Drawing tools"].frame.midY)
        XCTAssertFalse(app.sliders["Size"].isHittable)
        app.buttons["Show size and opacity"].tap()
        for label in ["Size", "Opacity"] {
            let slider = app.sliders[label]
            XCTAssertTrue(slider.isHittable)
            XCTAssertGreaterThan(slider.frame.width, 100)
            let readout = app.buttons[label + " readout"]
            slider.adjust(toNormalizedSliderPosition: 0.25)
            let low = try XCTUnwrap(readout.value as? String)
            slider.adjust(toNormalizedSliderPosition: 0.75)
            let high = try XCTUnwrap(readout.value as? String)
            XCTAssertNotEqual(low, high, "\(label) readout should follow the slider")
        }
        app.buttons["Hide size and opacity"].tap()
        XCTAssertFalse(app.sliders["Opacity"].isHittable)
        XCTAssertFalse(app.buttons["Pixel tips"].exists)
        func element(_ label: String) -> XCUIElement {
            app.descendants(matching: .any).matching(NSPredicate(format: "identifier == %@ OR label == %@", label, label)).firstMatch
        }
        let firstSlot = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "dock-tip-")).firstMatch
        XCTAssertTrue(firstSlot.waitForExistence(timeout: 10))
        firstSlot.press(forDuration: 0.8)
        XCTAssertTrue(element("Choose drawing tip").waitForExistence(timeout: 10))
        element("All").tap()
        let search = element("tip-picker-search")
        XCTAssertTrue(search.waitForExistence(timeout: 10))
        search.tap()
        app.typeText("Pixel line")
        let pixelLine = element("Choose Pixel line")
        XCTAssertTrue(pixelLine.waitForExistence(timeout: 10))
        pixelLine.tap()
        let pinnedLine = element("dock-tip-pixel-line-v1")
        XCTAssertTrue(pinnedLine.waitForExistence(timeout: 10))
        pinnedLine.tap()
        element("Groups").tap()
        element("Open group Manga").tap()
        element("Choose Manga hatching").tap()
        XCTAssertTrue(element("dock-tip-manga-hatch-v1").waitForExistence(timeout: 10))
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
        firstSlot.tap()
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
        app.buttons["More canvas actions"].tap()
        app.buttons["Brush settings"].tap()
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
        for label in ["Gallery", "Undo", "Redo", "Fit canvas", "Brush color", "Layers", "More canvas actions"] {
            let frame = app.buttons[label].frame
            XCTAssertEqual(frame.midY, gallery.frame.midY, accuracy: 2, label)
            XCTAssertLessThan(frame.midY, app.frame.minY + app.frame.height * 0.25, label)
        }
        XCTAssertLessThan(app.buttons["Fit canvas"].frame.maxX, app.buttons["Brush color"].frame.minX)
        XCTAssertTrue(app.buttons["Layers"].isHittable)
        app.buttons["Layers"].tap()
        XCTAssertTrue(app.buttons["Import image"].exists)
        app.navigationBars.buttons["Add"].tap()
        XCTAssertTrue(app.staticTexts["Layer 2"].exists)
        app.buttons["Choose blend mode"].tap()
        app.buttons["Multiply"].tap()
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "Multiply")).firstMatch.exists)
        app.navigationBars.buttons["Done"].tap()
        gallery.tap()
        let savedDrawing = app.cells["gallery-\(drawingID.uuidString)"]
        XCTAssertTrue(savedDrawing.waitForExistence(timeout: 15))
        app.terminate(); app.launch()
        let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == true AND hittable == true"), object: savedDrawing)
        XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 15), .completed, "Saved drawing should be available in the gallery")
        savedDrawing.tap()
        let reopenedCanvas = app.descendants(matching: .any)[drawingID.uuidString]
        XCTAssertTrue(reopenedCanvas.waitForExistence(timeout: 10), "One tap should reopen the same drawing.\n\(app.debugDescription)")
        app.buttons["Layers"].tap()
        XCTAssertTrue(app.staticTexts["Layer 2"].exists)
        XCUIDevice.shared.orientation = .portrait
    }
}
