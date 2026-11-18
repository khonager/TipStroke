import XCTest
import UIKit
import TipStrokeShared
@testable import TipStroke

final class CreativeToolsTests: XCTestCase {
    private func rgba(_ document: RasterDocument, _ x: Int, _ y: Int, layer: UUID? = nil) throws -> UInt32 {
        try TilePixels(document.pixels[layer ?? document.activeLayer] ?? [:]).rgba(x, y)
    }
    private func dot(_ document: RasterDocument, _ x: CGFloat, _ y: CGFloat, color: UIColor = .black) throws {
        _ = try document.paint(from: CGPoint(x: x, y: y), to: CGPoint(x: x, y: y), radius: 5, color: color, erasing: false)
    }
    func testLassoPreviewShrinksAndCancellationRestoresOriginal() throws {
        let doc = RasterDocument(name: "Live", width: 512, height: 512)
        XCTAssertTrue(doc.beginStroke())
        try doc.paintPath(CGPath(rect: CGRect(x: 20, y: 20, width: 300, height: 100), transform: nil), color: .red, width: 0, filled: true)
        XCTAssertNotEqual(try rgba(doc, 280, 50), 0)
        doc.resetStrokePreview()
        try doc.paintPath(CGPath(rect: CGRect(x: 20, y: 20, width: 40, height: 40), transform: nil), color: .red, width: 0, filled: true)
        XCTAssertEqual(try rgba(doc, 280, 50), 0)
        doc.endStroke(cancelled: false)
        XCTAssertNotEqual(try rgba(doc, 30, 30), 0)
        doc.undo(); XCTAssertEqual(try rgba(doc, 30, 30), 0)
        doc.redo(); XCTAssertNotEqual(try rgba(doc, 30, 30), 0)
        XCTAssertTrue(doc.beginStroke()); try dot(doc, 400, 400)
        doc.endStroke(cancelled: true); XCTAssertEqual(try rgba(doc, 400, 400), 0)
    }
    func testPaintInsidePreservesSoftAlphaAndDoesNotGrowTiles() throws {
        let doc = RasterDocument(name: "Inside", width: 512, height: 512)
        XCTAssertTrue(doc.beginStroke()); try dot(doc, 20, 20, color: UIColor.red.withAlphaComponent(0.4)); doc.endStroke(cancelled: false)
        let alpha = try rgba(doc, 20, 20) & 255
        doc.paintInside = true
        XCTAssertTrue(doc.beginStroke())
        _ = try doc.paint(from: CGPoint(x: 20, y: 20), to: CGPoint(x: 300, y: 20), radius: 10, color: .blue, erasing: false)
        doc.endStroke(cancelled: false)
        XCTAssertEqual(try rgba(doc, 20, 20) & 255, alpha)
        XCTAssertEqual(try rgba(doc, 40, 20), 0)
        XCTAssertEqual(doc.pixels[doc.activeLayer]?.count, 1)
    }
    func testMirrorCopiesUndoTogetherAndClipInDocumentCoordinates() throws {
        let doc = RasterDocument(name: "Mirror", width: 512, height: 512)
        doc.mirrorCopies = 2; doc.mirrorCenter = CGPoint(x: 256, y: 256)
        XCTAssertTrue(doc.beginStroke()); try dot(doc, 20, 40); doc.endStroke(cancelled: false)
        XCTAssertNotEqual(try rgba(doc, 20, 40), 0); XCTAssertNotEqual(try rgba(doc, 492, 40), 0)
        doc.undo(); XCTAssertEqual(try rgba(doc, 492, 40), 0); XCTAssertEqual(try rgba(doc, 20, 40), 0)
        doc.selection = CanvasSelection(path: CGPath(rect: CGRect(x: 0, y: 0, width: 100, height: 100), transform: nil))
        XCTAssertTrue(doc.beginStroke()); try dot(doc, 20, 40); doc.endStroke(cancelled: false)
        XCTAssertNotEqual(try rgba(doc, 20, 40), 0); XCTAssertEqual(try rgba(doc, 492, 40), 0)
    }
    func testShadowPullUsesSelectionAsSourceAndCancelRestores() throws {
        let doc = RasterDocument(name: "Shadow", width: 512, height: 256)
        XCTAssertTrue(doc.beginStroke()); try dot(doc, 250, 30); doc.endStroke(cancelled: false)
        doc.selection = CanvasSelection(path: CGPath(rect: CGRect(x: 240, y: 20, width: 20, height: 20), transform: nil))
        let source = try doc.shadowSource(color: .blue)
        XCTAssertTrue(doc.beginStroke(mirrored: false)); try doc.paintShadow(source, offset: CGPoint(x: 20, y: 0))
        XCTAssertNotEqual(try rgba(doc, 270, 30), 0)
        doc.resetStrokePreview(); try doc.paintShadow(source, offset: CGPoint(x: 40, y: 0))
        XCTAssertEqual(try rgba(doc, 270, 30), 0); XCTAssertNotEqual(try rgba(doc, 290, 30), 0)
        doc.endStroke(cancelled: true)
        XCTAssertEqual(try rgba(doc, 290, 30), 0); XCTAssertNotEqual(try rgba(doc, 250, 30), 0)
    }
    func testColorPushCrossesTileBoundaryAndIsUndoable() throws {
        let doc = RasterDocument(name: "Push", width: 512, height: 256)
        XCTAssertTrue(doc.beginStroke()); try dot(doc, 251, 30, color: .red); doc.endStroke(cancelled: false)
        let old = try rgba(doc, 260, 30)
        XCTAssertTrue(doc.beginStroke(mirrored: false))
        try doc.pushColor(from: CGPoint(x: 251, y: 30), to: CGPoint(x: 260, y: 30), radius: 24, strength: 1)
        doc.endStroke(cancelled: false)
        XCTAssertGreaterThan(try rgba(doc, 260, 30) & 255, old & 255)
        doc.undo(); XCTAssertEqual(try rgba(doc, 260, 30), old)
    }
    func testBlurHaloAndPreviewDoNotMutateSource() throws {
        let doc = RasterDocument(name: "Blur", width: 512, height: 256)
        XCTAssertTrue(doc.beginStroke()); try dot(doc, 255, 30, color: .red); doc.endStroke(cancelled: false)
        let before = try rgba(doc, 264, 30)
        var settings = EffectSettings(); settings.radius = 8
        let tiles = try RasterDocument.effectTiles(doc.snapshot(), layer: doc.activeLayer, selection: nil, settings: settings)
        XCTAssertEqual(try rgba(doc, 264, 30), before)
        XCTAssertGreaterThan(try TilePixels(tiles).rgba(261, 30) & 255, 0)
        // Prepared pixels must ignore session mirror settings, preserving the preview exactly.
        doc.mirrorCopies = 4
        try doc.replacePreparedTiles(tiles)
        XCTAssertEqual(try rgba(doc, 261, 30), try TilePixels(tiles).rgba(261, 30))
        doc.undo(); XCTAssertEqual(try rgba(doc, 264, 30), before)
    }
    func testColorizeHintsRecipeSaveReopenAndCompoundUndo() throws {
        let doc = RasterDocument(name: "Colorize", width: 64, height: 64)
        let source = doc.activeLayer
        doc.record.layers[0].reference = true // Hints must not be clipped to the line-art reference.
        let output = try doc.createColorizeMask()
        let hints = doc.activeLayer, recipe = try doc.colorizeRecipe(output)
        XCTAssertTrue(doc.beginStroke()); try dot(doc, 20, 20, color: .red); doc.endStroke(cancelled: false)
        XCTAssertNotEqual(try rgba(doc, 20, 20), 0)
        let tiles = try RasterDocument.colorizeTiles(doc.snapshot(), recipe: recipe)
        try doc.applyColorize(tiles, output: output, recipe: recipe)
        XCTAssertFalse(doc.record.layers.first { $0.id == hints }!.visible)
        XCTAssertEqual(doc.activeLayer, output)
        XCTAssertGreaterThan(try rgba(doc, 40, 40) & 255, 0)
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = ProjectStore(root: root); try store.save(doc.snapshot())
        let reopened = RasterDocument(snapshot: try store.load(doc.record.id))
        let restored = try reopened.colorizeRecipe(output)
        XCTAssertEqual(restored.lineArtId, source.uuidString); XCTAssertEqual(restored.hintsId, hints.uuidString)
        XCTAssertEqual(reopened.record.schemaVersion, 4)
        doc.undo(); XCTAssertTrue(doc.record.layers.first { $0.id == hints }!.visible)
        XCTAssertTrue(doc.pixels[output]?.isEmpty ?? true)
        doc.redo(); XCTAssertGreaterThan(try rgba(doc, 40, 40, layer: output) & 255, 0)
        try doc.deleteLayer(hints)
        XCTAssertThrowsError(try doc.colorizeRecipe(output))
        XCTAssertGreaterThan(try rgba(doc, 40, 40, layer: output) & 255, 0)
    }
    func testMangaPatternsStayRegisteredAcrossStrokes() throws {
        let brush = NativeBrush.companion.builtIn(id: "manga-dots-30-v1")
        XCTAssertTrue(brush.usesStamps)
        let renderer = try NativeStampRenderer(brush: brush)
        let doc = RasterDocument(name: "Tone", width: 512, height: 256)
        for x in [240, 272] {
            let path = brush.path(size: 48, opacity: 1)
            XCTAssertTrue(doc.beginStroke())
            let stamps = path.append(x: Float(x), y: 32, pressure: 1, tilt: 0, time: 0)
            _ = try renderer.paint(stamps, color: .black, document: doc, erasing: false)
            doc.endStroke(cancelled: false)
        }
        for x in 235..<245 { for y in 27..<37 {
            XCTAssertEqual(try rgba(doc, x, y), try rgba(doc, x + 32, y))
        } }
    }
}
