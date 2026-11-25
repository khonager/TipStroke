import UIKit
import ImageIO
import XCTest
@testable import TipStroke

final class ProcessReplayTests: XCTestCase {
    func testLegacyAndroidBrushCommandReplaysAcrossScrub() throws {
        // DataOutputStream format: a white 32 px page, then one red v1 Ink command.
        let bytes = try XCTUnwrap(Data(base64Encoded: "ABNUaXBTdHJva2UtcHJvY2Vzcy0zAAAAIAAAACAAAAAAAAIAAABpH4sIAAAAAAAC/2NgSK9WSkpMzk4vyi/NS1Gy0jXUUcovSEzOLKlUsgKycxIrU4uKlayiq5UygdIQvpKOUllmcWZSTqqSVUlRaSqqFqBwXopvfgpQUsnPP8jX0UepNrYWAA8NH25qAAAAAAAApR+LCAAAAAAAAv9jZGDNSaxMLWJgYGCEYhBgYWDLzMvWLTNkYPbMywYSft6OExgY7Bsg2Lb0UL+dz5mztjsC34DFRKoe2n491A9mu68TgbMZoHrkdgSBaAa+YA9HF08/9/jgcM8QZw+w2utcto4MCLNhAMxmDXD09AuBizGBCAeghCOaYjCw5YTQbMEhkT6hwTB5xwfY1TOutsdUDwA1MzAoEAEAAA=="))
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let source = root.appendingPathComponent("process.tsr"), folder = root.appendingPathComponent("progress")
        try bytes.write(to: source)
        try AndroidProcess.importRecording(source, to: folder)
        let replay = try ProcessReplay(folder: folder)
        XCTAssertEqual(replay.count, 2)
        let frames: [(Int, UInt32)] = [(1, 0xff0000ff), (0, 0xffffffff), (1, 0xff0000ff)]
        for (index, color) in frames {
            let image = try replay.render(index, maxEdge: 32)
            let ctx = try RasterDocument.context(width: 256, height: 256)
            RasterDocument.draw(image, in: ctx, rect: CGRect(x: 0, y: 0, width: 32, height: 32))
            let tile = try XCTUnwrap(ctx.makeImage())
            XCTAssertEqual(try TilePixels([TileKey(x: 0, y: 0): tile]).rgba(16, 16), color)
        }
    }
    func testCommittedPaintAndUndoProduceScrubbableDurableReplay() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = ProjectStore(root: root)
        let document = RasterDocument(name: "Replay", width: 32, height: 32)
        let recorder = ProcessRecorder(store: store, id: document.record.id)
        recorder.capture(document)
        XCTAssertTrue(document.beginStroke())
        let point = CGPoint(x: 16, y: 16)
        _ = try document.paint(from: point, to: point, radius: 4, color: .red, erasing: false)
        document.endStroke(cancelled: false); recorder.capture(document)
        document.undo(); recorder.capture(document)
        let replay = try store.queue.sync { try ProcessReplay(folder: store.replayFolder(document.record.id)) }
        XCTAssertEqual(replay.count, 3)
        func pixel(_ player: ProcessReplay, _ step: Int) throws -> UInt32 {
            let image = try player.render(step, maxEdge: 32)
            let context = try RasterDocument.context(width: 256, height: 256)
            RasterDocument.draw(image, in: context, rect: CGRect(x: 0, y: 0, width: 32, height: 32))
            let tile = try XCTUnwrap(context.makeImage())
            return try TilePixels([TileKey(x: 0, y: 0): tile]).rgba(16, 16)
        }
        XCTAssertEqual(try pixel(replay, 1), 0xff0000ff)
        XCTAssertEqual(try pixel(replay, 2), 0xffffffff)
        XCTAssertEqual(try pixel(replay, 0), 0xffffffff)
        let portable = root.appendingPathComponent("test.tsr")
        try AndroidProcess.exportRecording(store.replayFolder(document.record.id), to: portable)
        let playback = root.appendingPathComponent("imported-progress")
        try AndroidProcess.importRecording(portable, to: playback)
        let imported = try ProcessReplay(folder: playback)
        XCTAssertEqual(imported.count, replay.count)
        for step in 0..<replay.count {
            XCTAssertEqual(try pixel(imported, step), try pixel(replay, step), "Portable replay step \(step)")
        }
        let gif = try replay.exportGIF()
        defer { try? FileManager.default.removeItem(at: gif) }
        let source = try XCTUnwrap(CGImageSourceCreateWithURL(gif as CFURL, nil))
        XCTAssertEqual(CGImageSourceGetCount(source), 3)
    }
}
