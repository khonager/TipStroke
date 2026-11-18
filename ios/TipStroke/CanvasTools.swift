import UIKit

enum CanvasMode: String, CaseIterable {
    case lassoFill = "Lasso fill"
    case paint = "Brush", fill = "Fill", smudge = "Smudge"
    case pixel = "Pixel pencil", pixelLine = "Pixel line", dither = "Dither"
    case line = "Line", rectangle = "Rectangle", ellipse = "Ellipse", triangle = "Triangle", arrow = "Arrow"
    case lasso = "Freehand selection", rectangleSelection = "Rectangle selection", ellipseSelection = "Ellipse selection"
    case transform = "Move selection"
    var isSelection: Bool { self == .lasso || self == .rectangleSelection || self == .ellipseSelection }
    var isShape: Bool { [.line, .rectangle, .ellipse, .triangle, .arrow, .pixelLine].contains(self) }
    var streamsPaint: Bool { [.paint, .smudge, .pixel, .dither].contains(self) }
    func outline(_ points: [CGPoint]) -> CGPath {
        let path = CGMutablePath()
        guard let first = points.first, let last = points.last else { return path }
        let rect = CGRect(x: min(first.x, last.x), y: min(first.y, last.y), width: abs(last.x - first.x), height: abs(last.y - first.y))
        switch self {
        case .rectangle, .rectangleSelection: path.addRect(rect)
        case .ellipse, .ellipseSelection: path.addEllipse(in: rect)
        case .triangle:
            path.move(to: CGPoint(x: rect.midX, y: rect.minY)); path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY))
            path.addLine(to: CGPoint(x: rect.minX, y: rect.maxY)); path.closeSubpath()
        case .arrow:
            let dx = last.x - first.x, dy = last.y - first.y
            path.move(to: first); path.addLine(to: last)
            path.addLine(to: CGPoint(x: last.x - dx * 0.22 - dy * 0.13, y: last.y - dy * 0.22 + dx * 0.13))
            path.move(to: last); path.addLine(to: CGPoint(x: last.x - dx * 0.22 + dy * 0.13, y: last.y - dy * 0.22 - dx * 0.13))
        case .lasso, .lassoFill:
            path.addLines(between: points); path.closeSubpath()
        default: path.move(to: first); path.addLine(to: last)
        }
        return path
    }
}
