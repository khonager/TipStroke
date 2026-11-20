import XCTest
import UIKit
import ImageIO
import UniformTypeIdentifiers
@testable import TipStroke

final class PhotoLayerTests: XCTestCase {
    func testUndoOnWarmCelWithColdOnionNeighbor() throws {
        let doc = RasterDocument(name: "Undo", width: 128, height: 128)
        doc.enableAnimation()
        try doc.addFrame(copy: false)
        try doc.addFrame(copy: false)
        doc.selectFrame(1)
        XCTAssertTrue(doc.beginStroke())
        try doc.paintPath(CGPath(rect: doc.bounds, transform: nil), color: .red, width: 0, filled: true)
        doc.endStroke(cancelled: false)
        doc.selectFrame(2)
        let neighbor = try XCTUnwrap(doc.record.animation?.frames.first?.id)
        doc.framePixels[neighbor] = nil
        doc.coldFrames[neighbor] = [:]
        doc.record.animation?.onionSkin = true
        doc.undo()
        XCTAssertEqual(doc.record.animation?.selectedIndex, 1)
        XCTAssertTrue(doc.pixels[doc.activeLayer]?.isEmpty ?? true)
        doc.redo()
        XCTAssertEqual(doc.pixels[doc.activeLayer]?.count, 1)
        doc.selectFrame(2)
        XCTAssertTrue(doc.pixels[doc.activeLayer]?.isEmpty ?? true)
    }

    func testTransformDraftIsolatedUntilApplyAndUndoRestoresPixels() throws {
        let doc = RasterDocument(name: "Selection", width: 256, height: 256)
        XCTAssertTrue(doc.beginStroke())
        _ = try doc.paint(from: CGPoint(x: 20, y: 20), to: CGPoint(x: 20, y: 20), radius: 5, color: .red, erasing: false)
        doc.endStroke(cancelled: false)
        doc.selection = CanvasSelection(path: CGPath(rect: CGRect(x: 10, y: 10, width: 30, height: 30), transform: nil))
        let revision = doc.revision
        let session = try SelectionTransformSession(document: doc)
        session.geometry = session.initial.dragged(tool: .move, from: .zero, to: CGPoint(x: 50, y: 0), divisions: 4, radius: 50)
        func alpha(_ x: Int) throws -> UInt32 { try TilePixels(doc.pixels[doc.activeLayer] ?? [:]).rgba(x, 20) & 255 }
        XCTAssertEqual(doc.revision, revision)
        XCTAssertEqual(try alpha(20), 255)
        XCTAssertEqual(try alpha(70), 0)
        try session.apply(to: doc, copy: false)
        XCTAssertEqual(try alpha(20), 0)
        XCTAssertEqual(try alpha(70), 255)
        XCTAssertTrue(doc.selection?.contains(CGPoint(x: 70,y: 20), in: doc.bounds) == true)
        doc.undo()
        XCTAssertTrue(doc.selection?.contains(CGPoint(x:20,y:20), in:doc.bounds) == true)
        XCTAssertEqual(try alpha(20), 255)
        XCTAssertEqual(try alpha(70), 0)
    }

    func testBooleanSelectionRecallAndCutUseTheSameHole() throws {
        let doc = RasterDocument(name: "Hole", width: 256, height: 256)
        XCTAssertTrue(doc.beginStroke())
        try doc.paintPath(CGPath(rect: doc.bounds, transform: nil), color: .red, width: 0, filled: true)
        doc.endStroke(cancelled: false)
        doc.combineSelection(CGPath(rect: CGRect(x: 10,y: 10,width: 100,height: 100), transform: nil))
        doc.selectionOperation = .subtract
        doc.combineSelection(CGPath(rect: CGRect(x: 30,y: 30,width: 30,height: 30), transform: nil))
        doc.selection = nil
        XCTAssertTrue(doc.restoreSelection())
        XCTAssertFalse(try XCTUnwrap(doc.selection).contains(CGPoint(x:40,y:40), in:doc.bounds))
        let original = doc.activeLayer
        try doc.transferSelection(cut: true)
        let kept = TilePixels(doc.pixels[original] ?? [:]), cut = TilePixels(doc.pixels[doc.activeLayer] ?? [:])
        XCTAssertEqual(try kept.rgba(40,40) & 255,255)
        XCTAssertEqual(try cut.rgba(40,40) & 255,0)
        XCTAssertEqual(try kept.rgba(20,20) & 255,0)
        XCTAssertEqual(try cut.rgba(20,20) & 255,255)
        doc.undo(); XCTAssertEqual(doc.record.layers.count,1)
    }
    func testErasePaintAndPhotoTogetherCanCancelAndUndo() throws {
        let doc = RasterDocument(name:"Mixed",width:128,height:128)
        let paint = doc.activeLayer
        XCTAssertTrue(doc.beginStroke())
        try doc.paintPath(CGPath(rect: doc.bounds, transform:nil),color:.red,width:0,filled:true)
        doc.endStroke(cancelled:false)
        let ctx = try RasterDocument.context(width:256,height:128)
        ctx.setFillColor(UIColor.green.cgColor); ctx.fill(CGRect(x:0,y:0,width:256,height:128))
        try doc.importPhoto(PhotoSource(image:XCTUnwrap(ctx.makeImage())),name:"Photo")
        let photo = doc.activeLayer, key = TileKey(x:0,y:0)
        doc.chooseLayers([paint,photo])
        func alpha(_ id: UUID) throws -> UInt32 {
            let image = try XCTUnwrap(RasterDocument.layerTile(doc.snapshot(),id:id,key:key))
            return try TilePixels([key:image]).rgba(64,64) & 255
        }
        for cancel in [true,false] {
            XCTAssertTrue(doc.beginStroke(photoErase:true))
            _ = try doc.paint(from:CGPoint(x:64,y:64),to:CGPoint(x:64,y:64),radius:10,color:.black,erasing:true)
            XCTAssertEqual(try alpha(photo),0); XCTAssertEqual(try alpha(paint),0)
            doc.endStroke(cancelled:cancel)
            if !cancel { doc.undo() }
            XCTAssertEqual(try alpha(photo),255); XCTAssertEqual(try alpha(paint),255)
        }
        doc.redo(); XCTAssertEqual(try alpha(photo),0); XCTAssertEqual(try alpha(paint),0)
    }
    func testPhotoCelPoseAndPresenceRoundTripAndExportByFrame() throws {
        let doc = RasterDocument(name: "Photo cels", width: 128, height: 128)
        let ctx = try RasterDocument.context(width: 32, height: 32)
        ctx.setFillColor(UIColor.red.cgColor); ctx.fill(CGRect(x:0,y:0,width:32,height:32))
        try doc.importPhoto(PhotoSource(image:XCTUnwrap(ctx.makeImage())),name:"Photo")
        let layer = doc.activeLayer, initial = try XCTUnwrap(doc.activePhoto).transform
        doc.enableAnimation(); try doc.addFrame(copy:true)
        let moved = initial.concatenating(CGAffineTransform(translationX:5,y:8))
        try doc.transformPhoto(moved)
        try doc.addFrame(copy:false)
        XCTAssertFalse(try XCTUnwrap(doc.record.layers.last).photoCelActive)
        let snapshot = doc.snapshot()
        let frames = try XCTUnwrap(snapshot.record.animation).frames
        XCTAssertEqual(try snapshot.atFrame(frames[0].id).record.layers.last?.photo?.transform,initial)
        XCTAssertEqual(try snapshot.atFrame(frames[1].id).record.layers.last?.photo?.transform,moved)
        XCTAssertNil(try RasterDocument.layerTile(snapshot.atFrame(frames[2].id), id:layer, key:TileKey(x:0,y:0)))
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at:root) }
        let store = ProjectStore(root:root)
        try store.save(snapshot)
        let restored = RasterDocument(snapshot:try store.load(doc.record.id))
        restored.selectFrame(1); XCTAssertEqual(restored.activePhoto?.transform,moved)
        restored.selectFrame(0); XCTAssertEqual(restored.activePhoto?.transform,initial)
    }
    func testOneTransformMovesBothPaintLayersAndUndoRestoresBoth() throws {
        let doc = RasterDocument(name:"Group transform",width:256,height:256)
        let first = doc.activeLayer
        for x in [30, 60] {
            if x == 60 { XCTAssertTrue(doc.addLayer()) }
            XCTAssertTrue(doc.beginStroke())
            let point = CGPoint(x:x,y:40)
            _ = try doc.paint(from:point,to:point,radius:4,color:.red,erasing:false)
            doc.endStroke(cancelled:false)
        }
        let second = doc.activeLayer
        doc.chooseLayers([first,second])
        let session = try SelectionTransformSession(document:doc)
        session.geometry = session.initial.dragged(tool:.move,from:.zero,to:CGPoint(x:10,y:20),divisions:4,radius:24)
        try session.apply(to:doc,copy:false)
        for (id,x) in [(first,30),(second,60)] {
            let pixels = TilePixels(doc.pixels[id] ?? [:])
            XCTAssertEqual(try pixels.rgba(x,40) & 255,0)
            XCTAssertEqual(try pixels.rgba(x+10,60) & 255,255)
        }
        doc.undo()
        XCTAssertEqual(try TilePixels(doc.pixels[first] ?? [:]).rgba(30,40) & 255,255)
        XCTAssertEqual(try TilePixels(doc.pixels[second] ?? [:]).rgba(60,40) & 255,255)
    }
    func testFitAndSnapRespectCanvasCoordinates() {
        let canvas = CGRect(x:0,y:0,width:200,height:100)
        let shape = TransformGeometry(rect:CGRect(x:0,y:0,width:50,height:50))
        let moved = shape.dragged(tool:.move,from:.zero,to:CGPoint(x:148,y:49),divisions:4,radius:24)
        let snapped = moved.snapped(to:[canvas],angles:[],step:15,distance:3,tolerance:2)
        XCTAssertEqual(snapped.bounds.maxX,200,accuracy:0.01)
        XCTAssertEqual(snapped.bounds.maxY,100,accuracy:0.01)
        let fitted = snapped.fitted(in:canvas)
        XCTAssertEqual(fitted.bounds.midX,100,accuracy:0.01)
        XCTAssertEqual(fitted.bounds.height,100,accuracy:0.01)
    }
    func testWarpAndPerspectiveUseSharedNonFoldingGeometry() {
        let initial = TransformGeometry(rect: CGRect(x: 0, y: 0, width: 100, height: 100))
        let warp = initial.dragged(tool: .warp, from: CGPoint(x: 50, y: 50), to: CGPoint(x: 54, y: 51), divisions: 4, radius: 20)
        XCTAssertEqual(warp.mesh?.count, 289)
        XCTAssertEqual(warp.vertex(8 * 17 + 8).x, 54, accuracy: 0.001)
        XCTAssertTrue(warp.validated())
        let distorted = warp.dragged(tool: .distort, from: .zero, to: CGPoint(x: 5, y: 4), divisions: 4, radius: 20)
        XCTAssertEqual(distorted.vertex(0).x, 5, accuracy: 0.001)
        XCTAssertTrue(distorted.validated())
    }

    func testOversizedOrientedPhotoKeepsOriginalBytesAndExportsVisiblePixels() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let url = root.appendingPathComponent("Portrait.jpg")
        let context = try RasterDocument.context(width: 5000, height: 100)
        context.setFillColor(UIColor.red.cgColor); context.fill(CGRect(x: 0, y: 0, width: 5000, height: 100))
        let writer = try XCTUnwrap(CGImageDestinationCreateWithURL(url as CFURL, UTType.jpeg.identifier as CFString, 1, nil))
        CGImageDestinationAddImage(writer, try XCTUnwrap(context.makeImage()), [kCGImagePropertyOrientation: 6] as CFDictionary)
        XCTAssertTrue(CGImageDestinationFinalize(writer))
        let source = try PhotoSource(file: url)
        XCTAssertEqual(source.width, 5000)
        XCTAssertEqual(source.height, 100)
        XCTAssertEqual(source.image.height, 4096)
        XCTAssertLessThan(source.image.width, source.image.height)
        let detailed = try source.exportImage(for: CGRect(x: 0, y: 0, width: 100, height: 5000))
        XCTAssertEqual(detailed.height, 5000)
        let doc = RasterDocument(name: "Original", width: 256, height: 256)
        try doc.importPhoto(source, name: "Portrait")
        XCTAssertLessThan(try XCTUnwrap(doc.activePhoto).bounds.width, 8)
        let store = ProjectStore(root: root)
        try store.save(doc.snapshot())
        let restored = try store.load(doc.record.id)
        XCTAssertEqual(restored.record.layers.last?.photo?.source?.encoded, try Data(contentsOf: url))
        let png = try store.exportPNG(restored, transparent: true)
        defer { try? FileManager.default.removeItem(at: png) }
        let output = try XCTUnwrap(UIImage(contentsOfFile: png.path)?.cgImage)
        let pixels = TilePixels([TileKey(x: 0, y: 0): output])
        XCTAssertEqual(try pixels.rgba(128, 128) & 255, 255)
        XCTAssertEqual(try pixels.rgba(20, 128) & 255, 0)
    }

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
        XCTAssertNil(document.pixels[document.activeLayer]?[TileKey(x: 0, y: 0)])
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
