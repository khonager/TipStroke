import UIKit
import ImageIO
import XCTest
@testable import TipStroke

final class ProcessReplayTests: XCTestCase {
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
