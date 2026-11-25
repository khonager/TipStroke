import UIKit
import TipStrokeShared

final class GraphiteRenderer {
    private let coverage = StrokeCoverage()
    private var previous: CGPoint?
    private static let grain: (side: Int, bytes: [UInt8]) = {
        let source = Graphite.shared.grain()
        let bytes = (0..<Int(source.width * source.height)).map { UInt8(source.coverage(index: Int32($0))) }
        return (Int(source.width), bytes)
    }()

    func begin() {
        previous = nil
        coverage.reset()
    }

    func paint(document: RasterDocument, brush: NativeBrush, point: CGPoint, size: CGFloat, pressure: CGFloat,
               tilt: Float, angle: CGFloat, speed: CGFloat, color: UIColor, opacity: CGFloat) throws -> CGRect {
        let tip = Graphite.shared.contact(brush: brush.preset, size: Float(size), opacity: Float(opacity),
            x: Float(point.x), y: Float(point.y), pressure: Float(pressure), opacityPressure: Float(pressure),
            tilt: tilt, speedScale: Float(speed))
        let level = min(3, max(0, Int((3 * brush.tiltResponse(radians: tilt) * brush.pencilGrain).rounded())))
        let valley = [230, 190, 145, 100][level]
        let start = previous ?? point
        previous = point
        let nib = StrokeCoverage.Nib(aspect: CGFloat(tip.height / tip.width), angle: angle)
        return try coverage.paint(document: document, from: start, to: point, radius: CGFloat(tip.width) / 2,
                                  alpha: CGFloat(tip.alpha) / 255, hardness: 1, color: color, nib: nib) { x, y in
            let gx = Int(CGFloat(x) / 0.38) % Self.grain.side
            let gy = Int(CGFloat(y) / 0.38) % Self.grain.side
            let tooth = Int(Self.grain.bytes[gy * Self.grain.side + gx])
            return CGFloat(valley + (255 - valley) * tooth / 255) / 255
        }
    }
}
