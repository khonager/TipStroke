import UIKit
import XCTest
@testable import TipStroke

final class SceneLaunchTests: XCTestCase {
    @MainActor
    func testGalleryWaitsForDocumentRecordsBeforeShowingSavedRows() {
        let gallery = GalleryController()
        let id = UUID()
        gallery.layout = GalleryStacks(order: [id])
        XCTAssertEqual(gallery.tableView(gallery.tableView, numberOfRowsInSection: 0), 0)
    }

    @MainActor
    func testApplicationSceneOwnsGalleryWindow() throws {
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first { $0.session.role == .windowApplication })
        let delegate = try XCTUnwrap(scene.delegate as? SceneDelegate)
        let window = try XCTUnwrap(delegate.window)
        XCTAssertTrue(window.windowScene === scene)
        let navigation = try XCTUnwrap(window.rootViewController as? UINavigationController)
        XCTAssertTrue(navigation.viewControllers.first is GalleryController)
    }
}
