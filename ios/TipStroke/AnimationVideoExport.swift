import UIKit
import AVFoundation
import TipStrokeShared

extension ProjectStore {
    /// AVFoundation requests frames on a worker queue. Retains one export buffer at a time.
    func exportMP4(_ snapshot: DocumentSnapshot, smooth: Bool = false, completion: @escaping (Result<URL, Error>) -> Void) {
        queue.async {
            do {
                let plan = try AnimationRenderPlan(snapshot, smooth: smooth)
                let url = FileManager.default.temporaryDirectory.appendingPathComponent("TipStroke-\(UUID().uuidString).mp4")
                let writer = try AVAssetWriter(outputURL: url, fileType: .mp4)
                let width = (snapshot.record.width + 1) / 2 * 2, height = (snapshot.record.height + 1) / 2 * 2
                let input = AVAssetWriterInput(mediaType: .video, outputSettings: [
                    AVVideoCodecKey: AVVideoCodecType.h264, AVVideoWidthKey: width, AVVideoHeightKey: height
                ])
                input.expectsMediaDataInRealTime = false
                let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input, sourcePixelBufferAttributes: [
                    kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
                    kCVPixelBufferWidthKey as String: width, kCVPixelBufferHeightKey as String: height,
                    kCVPixelBufferCGImageCompatibilityKey as String: true,
                    kCVPixelBufferCGBitmapContextCompatibilityKey as String: true
                ])
                guard writer.canAdd(input) else { throw DrawingError.message("This device cannot encode this canvas size.") }
                writer.add(input)
                guard writer.startWriting() else { throw writer.error ?? DrawingError.message("Could not start video export.") }
                writer.startSession(atSourceTime: .zero)
                let segments = plan.samples
                var index = 0
                var finished = false
                let work = DispatchQueue(label: "dev.tipstroke.ios.video-export", qos: .userInitiated)
                input.requestMediaDataWhenReady(on: work) {
                    guard !finished else { return }
                    do {
                        while input.isReadyForMoreMediaData && index < segments.count {
                            try autoreleasepool {
                                guard let pool = adaptor.pixelBufferPool else { throw DrawingError.message("Video buffer allocation failed.") }
                                var output: CVPixelBuffer?
                                guard CVPixelBufferPoolCreatePixelBuffer(nil, pool, &output) == kCVReturnSuccess, let buffer = output else {
                                    throw DrawingError.message("Not enough memory for video export.")
                                }
                                CVPixelBufferLockBaseAddress(buffer, [])
                                defer { CVPixelBufferUnlockBaseAddress(buffer, []) }
                                guard let context = CGContext(data: CVPixelBufferGetBaseAddress(buffer), width: width, height: height,
                                    bitsPerComponent: 8, bytesPerRow: CVPixelBufferGetBytesPerRow(buffer),
                                    space: CGColorSpace(name: CGColorSpace.sRGB)!,
                                    bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue) else {
                                    throw DrawingError.message("Could not create the video frame.")
                                }
                                context.translateBy(x: 0, y: CGFloat(height)); context.scaleBy(x: 1, y: -1)
                                let bounds = CGRect(x: 0, y: 0, width: width, height: height)
                                context.setFillColor(UIColor.white.cgColor); context.fill(bounds)
                                context.setFillColor(snapshot.record.paperColor.cgColor); context.fill(bounds)
                                let segment = segments[index]
                                try plan.draw(segment, in: context)
                                guard adaptor.append(buffer, withPresentationTime: CMTime(value: segment.startUs, timescale: 1_000_000)) else {
                                    throw writer.error ?? DrawingError.message("Video encoding failed.")
                                }
                                index += 1
                            }
                        }
                        if index == segments.count {
                            finished = true
                            writer.endSession(atSourceTime: CMTime(value: plan.durationUs, timescale: 1_000_000))
                            input.markAsFinished()
                            writer.finishWriting {
                                if writer.status == .completed { completion(.success(url)) }
                                else { try? FileManager.default.removeItem(at: url); completion(.failure(writer.error ?? DrawingError.message("Video export failed."))) }
                            }
                        }
                    } catch {
                        finished = true; writer.cancelWriting(); try? FileManager.default.removeItem(at: url); completion(.failure(error))
                    }
                }
            } catch { completion(.failure(error)) }
        }
    }
}
