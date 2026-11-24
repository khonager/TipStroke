import UIKit
import TipStrokeShared

/// Render one pressure sweep into a private, single-tile document.
enum BrushSpecimen {
    static func image(_ source: NativeBrush, eraser: Bool) throws -> Data? {
        let brush = eraser ? NativeBrush(preset: source.eraserPreset()) : source
        let document = RasterDocument(name: "", width: 112, height: 48)
        document.record.backgroundARGB = 0xff303238
        guard document.beginStroke() else { return nil }
        if eraser {
            try document.paintPath(CGPath(rect: document.bounds.insetBy(dx: 2, dy: 3), transform: nil),
                                   color: .lightGray, width: 1, filled: true)
        }
        let graphite = GraphiteRenderer()
        let air = AirbrushCoverage()
        let size = min(40, max(2, 4.5 * log2(CGFloat(brush.size))))
        let path = brush.path(size: Float(size), opacity: brush.opacity)
        var stamps: [BrushStamp] = []
        var previous: CGPoint?
        for index in 0...28 {
            let t = CGFloat(index) / 28
            var point = CGPoint(x: 10 + 92 * t, y: 24 - 9 * sin(2 * .pi * t))
            let speed = 1 - CGFloat(brush.speedTaper) * (0.15 + 0.65 * t)
            if let previous {
                let response = 1 - CGFloat(brush.stabilization) * 0.85
                point = CGPoint(x: previous.x + response * (point.x - previous.x),
                                y: previous.y + response * (point.y - previous.y))
            }
            let pressure = 0.12 + 0.88 * t
            let radius = speed * size * CGFloat(brush.sizeResponse(pressure: Float(pressure))) / 2
            let alpha = CGFloat(brush.opacity * brush.opacityResponse(pressure: Float(pressure)))
            if brush.usesStamps {
                stamps += path.append(x: Float(point.x), y: Float(point.y), pressure: Float(pressure),
                                      tilt: Float(t), time: Double(t) * 0.5)
            } else if brush.engine == "PENCIL" {
                _ = try graphite.paint(document: document, brush: brush, point: point, size: size,
                    pressure: pressure, tilt: Float(t), angle: 0.3, speed: speed, color: .white, opacity: CGFloat(brush.opacity))
            } else if brush.engine == "AIRBRUSH" {
                _ = try air.paint(document: document, from: previous ?? point, to: point, radius: radius,
                                  alpha: alpha, hardness: CGFloat(brush.hardness), color: .white)
            } else {
                _ = try document.paint(from: previous ?? point, to: point, radius: radius,
                                       color: UIColor.white.withAlphaComponent(alpha), erasing: false)
            }
            previous = point
        }
        if brush.usesStamps {
            _ = try NativeStampRenderer(brush: brush).paint(stamps, color: .white, document: document, erasing: eraser)
        }
        document.endStroke(cancelled: false)
        let context = try RasterDocument.context(width: 112, height: 48)
        context.setFillColor(document.record.paperColor.cgColor)
        context.fill(document.bounds)
        try RasterDocument.composite(document.snapshot(), in: context, visible: document.bounds)
        return context.makeImage().flatMap { UIImage(cgImage: $0).pngData() }
    }
}
