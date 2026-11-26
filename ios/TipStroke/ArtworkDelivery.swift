import Photos
import UIKit

extension UIViewController {
    func deliverArtwork(_ files: [URL], photos: Bool) {
        func cleanup() { files.forEach { try? FileManager.default.removeItem(at: $0) } }
        if photos {
            PHPhotoLibrary.shared().performChanges {
                for file in files {
                    let resource: PHAssetResourceType = file.pathExtension.lowercased() == "mp4" ? .video : .photo
                    PHAssetCreationRequest.forAsset().addResource(with: resource, fileURL: file, options: nil)
                }
            } completionHandler: { success, error in
                cleanup()
                DispatchQueue.main.async {
                    if !success { self.showError(error ?? DrawingError.message("Could not save to Photos.")) }
                    else { UIAccessibility.post(notification: .announcement, argument: "Saved to Photos") }
                }
            }
        } else {
            let sheet = UIActivityViewController(activityItems: files, applicationActivities: nil)
            sheet.completionWithItemsHandler = { _, _, _, _ in cleanup() }
            sheet.popoverPresentationController?.sourceView = view
            sheet.popoverPresentationController?.sourceRect = CGRect(x: view.bounds.midX, y: view.safeAreaInsets.top + 30, width: 1, height: 1)
            present(sheet, animated: true)
        }
    }
}
