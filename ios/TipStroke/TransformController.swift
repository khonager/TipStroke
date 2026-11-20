import UIKit

final class TransformController: UIViewController {
    let document: RasterDocument
    let session: SelectionTransformSession
    var onApply: (() -> Void)?
    private let editor: TransformPreviewView
    private var copyPaint = false

    init(document: RasterDocument, session: SelectionTransformSession) {
        self.document = document; self.session = session
        editor = TransformPreviewView(session: session, document: document)
        super.init(nibName: nil, bundle: nil)
    }
    required init?(coder: NSCoder) { fatalError("Use init(document:session:)") }
    override func viewDidLoad() {
        super.viewDidLoad()
        title = session.singlePhoto ? "Transform photo" : "Transform selection"
        view.backgroundColor = .systemBackground
        navigationItem.leftBarButtonItem = UIBarButtonItem(systemItem: .cancel, primaryAction: UIAction { [weak self] _ in self?.dismiss(animated: true) })
        navigationItem.rightBarButtonItem = UIBarButtonItem(title: "Apply", primaryAction: UIAction { [weak self] _ in
            guard let self else { return }
            do { try self.session.apply(to: self.document, copy: self.copyPaint); self.onApply?(); self.dismiss(animated: true) }
            catch { self.showError(error) }
        })
        let tools = UIButton(type: .system)
        tools.showsMenuAsPrimaryAction = true
        func refreshTools() {
            tools.setTitle(editor.tool.rawValue + " ▾", for: .normal)
            tools.menu = UIMenu(children: TransformGeometry.Tool.allCases.map { tool in
                UIAction(title: tool.rawValue, state: editor.tool == tool ? .on : .off) { [weak self, weak tools] _ in
                    self?.editor.tool = tool; self?.editor.setNeedsDisplay()
                    tools?.setTitle(tool.rawValue + " ▾", for: .normal)
                }
            })
            tools.changesSelectionAsPrimaryAction = true
        }
        refreshTools()
        let options = UIButton(type: .system)
        options.setTitle("Options ▾", for: .normal); options.showsMenuAsPrimaryAction = true
        options.menu = UIMenu(children: [UIDeferredMenuElement.uncached { [weak self] complete in
            guard let self else { complete([]); return }
            let grid = UIMenu(title: "Mesh handles", children: [2,4,8].map { n in
                UIAction(title: "\(n) × \(n)", state: self.editor.divisions == n ? .on : .off) { _ in self.editor.divisions = n; self.editor.setNeedsDisplay() }
            })
            let radius = UIMenu(title: "Deformation radius", children: [24,64,128,256].map { px in
                UIAction(title: "\(px) px", state: self.editor.radius == CGFloat(px) ? .on : .off) { _ in self.editor.radius = CGFloat(px) }
            })
            let filter = UIAction(title: "Bilinear sampling", state: self.session.geometry.filtered ? .on : .off) { _ in
                self.session.geometry.filtered.toggle(); self.editor.setNeedsDisplay()
            }
            let reset = UIAction(title: "Reset transform") { _ in self.session.geometry = self.session.initial; self.editor.setNeedsDisplay() }
            let flipX = UIAction(title: "Flip horizontal") { _ in self.editor.adjust(CGAffineTransform(scaleX: -1,y: 1)) }
            let flipY = UIAction(title: "Flip vertical") { _ in self.editor.adjust(CGAffineTransform(scaleX: 1,y: -1)) }
            let rotate = UIAction(title: "Rotate 45°") { _ in self.editor.adjust(CGAffineTransform(rotationAngle: .pi/4)) }
            let fit = UIAction(title: "Fit to canvas") { _ in
                self.session.geometry = self.session.geometry.fitted(in: self.document.bounds); self.editor.setNeedsDisplay()
            }
            let exact = UIAction(title: "Exact size and rotation…") { _ in self.exactSize() }
            let snap = UIAction(title: "Snap", state: self.editor.snapping ? .on : .off) { _ in self.editor.snapping.toggle() }
            let snapAngles = UIMenu(title: "Snap angle", children: [5,15,30,45,90].map { angle in
                UIAction(title: "\(angle)°", state: self.editor.snapAngle == CGFloat(angle) ? .on : .off) { _ in self.editor.snapAngle = CGFloat(angle) }
            })
            let strength = UIMenu(title: "Snap strength", children: [0.25,0.5,0.75,1.0].map { value in
                UIAction(title: "\(Int(100*value))%", state: self.editor.snapStrength == value ? .on : .off) { _ in self.editor.snapStrength = value }
            })
            var items: [UIMenuElement] = [grid,radius,filter,flipX,flipY,rotate,fit,exact,snap,snapAngles,strength,reset]
            if self.session.photos.isEmpty {
                items.append(UIAction(title: "Keep original pixels", state: self.copyPaint ? .on : .off) { _ in self.copyPaint.toggle() })
            }
            complete(items)
        }])
        let hint = UILabel(); hint.text = "Drag to transform. Apply keeps your changes."; hint.font = .preferredFont(forTextStyle: .caption1)
        hint.textAlignment = .center; hint.adjustsFontForContentSizeCategory = true
        let bar = UIStackView(arrangedSubviews: [tools,options]); bar.distribution = .fillEqually
        let stack = UIStackView(arrangedSubviews: [editor,hint,bar]); stack.axis = .vertical; stack.spacing = 8
        stack.translatesAutoresizingMaskIntoConstraints = false; view.addSubview(stack)
        NSLayoutConstraint.activate([stack.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            stack.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor),
            stack.leadingAnchor.constraint(equalTo: view.leadingAnchor), stack.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            bar.heightAnchor.constraint(greaterThanOrEqualToConstant: 44)])
    }
    private func exactSize() {
        let g = session.geometry, initial = session.initial
        let xScale = hypot(g.pose.a,g.pose.b)/max(0.00001,hypot(initial.pose.a,initial.pose.b))
        let yScale = hypot(g.pose.c,g.pose.d)/max(0.00001,hypot(initial.pose.c,initial.pose.d))
        let alert = UIAlertController(title: "Exact size and rotation", message: "Width and height in percent (1–800), rotation in degrees.", preferredStyle: .alert)
        for (name, value) in [("Width %",xScale*100),("Height %",yScale*100),("Rotation °",g.angle*180 / .pi)] {
            alert.addTextField { field in
                field.accessibilityLabel = name; field.placeholder = name; field.keyboardType = .numbersAndPunctuation
                field.text = String(format: "%.2f", Double(value))
            }
        }
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        alert.addAction(UIAlertAction(title: "Update preview", style: .default) { [weak self, weak alert] _ in
            guard let self, let fields = alert?.textFields else { return }
            let values = fields.compactMap { Double($0.text ?? "") }
            guard values.count == 3, values.allSatisfy(\.isFinite), (1...800).contains(values[0]), (1...800).contains(values[1]) else {
                self.showError(DrawingError.message("Enter sizes from 1 to 800 percent and a finite rotation.")); return
            }
            var updated = g
            updated.pose = g.pose.scaledBy(x: CGFloat(values[0])/100/xScale,y: CGFloat(values[1])/100/yScale)
            let center = CGPoint(x:g.bounds.midX,y:g.bounds.midY), moved = updated.bounds
            updated.pose = updated.pose.concatenating(CGAffineTransform(translationX: center.x-moved.midX,y: center.y-moved.midY))
            self.session.geometry = updated.centered(CGAffineTransform(rotationAngle: CGFloat(values[2]) * .pi / 180 - g.angle))
            self.editor.setNeedsDisplay()
        })
        present(alert, animated: true)
    }
}

private final class TransformPreviewView: UIView {
    let session: SelectionTransformSession
    let canvas: CGRect
    var tool: TransformGeometry.Tool = .move
    var divisions = 4
    var radius: CGFloat = 64
    private let targets: [CGRect]
    private let angles: [CGFloat]
    private var pointers: [UITouch] = []
    private var origins: [CGPoint] = []
    private var beforeDrag: TransformGeometry?
    private var originalGesture: TransformGeometry?
    var snapping = UserDefaults.standard.bool(forKey: "photoSnap") { didSet { UserDefaults.standard.set(snapping,forKey: "photoSnap") } }
    var snapAngle = CGFloat(UserDefaults.standard.object(forKey: "photoSnapAngle") as? Double ?? 15) {
        didSet { UserDefaults.standard.set(Double(snapAngle),forKey: "photoSnapAngle") }
    }
    var snapStrength = UserDefaults.standard.object(forKey: "photoSnapStrength") as? Double ?? 0.5 {
        didSet { UserDefaults.standard.set(snapStrength,forKey: "photoSnapStrength") }
    }
    init(session: SelectionTransformSession, document: RasterDocument) {
        self.session = session; self.canvas = document.bounds
        let photos = document.record.layers.filter { $0.visible && $0.id != session.layerID }.compactMap(\.photo)
        targets = [document.bounds] + photos.map(\.bounds); angles = photos.map { $0.geometry.angle }
        super.init(frame: .zero)
        backgroundColor = .secondarySystemBackground; isOpaque = true; contentMode = .redraw; isMultipleTouchEnabled = true
        accessibilityLabel = "Transform preview"
    }
    func adjust(_ t: CGAffineTransform) { session.geometry = session.geometry.centered(t); setNeedsDisplay() }
    required init?(coder: NSCoder) { fatalError("Use init(session:canvas:)") }
    private var documentToView: CGAffineTransform {
        let s = max(0.0001,min((bounds.width-24)/canvas.width,(bounds.height-24)/canvas.height))
        return CGAffineTransform(translationX: bounds.midX - canvas.midX*s, y: bounds.midY - canvas.midY*s).scaledBy(x: s,y: s)
    }
    private func position(_ touch: UITouch) -> CGPoint { touch.location(in: self).applying(documentToView.inverted()) }
    private func rebase() { beforeDrag = session.geometry; origins = pointers.map(position) }
    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        if pointers.isEmpty { originalGesture = session.geometry }
        for touch in touches where pointers.count < 2 { pointers.append(touch) }
        rebase()
    }
    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let start = beforeDrag, let origin = origins.first, let first = pointers.first else { return }
        let point = position(first)
        var changed: TransformGeometry
        if pointers.count == 2 {
            let other = position(pointers[1]), o = origins[1]
            let pivot = CGPoint(x:(origin.x+o.x)/2,y:(origin.y+o.y)/2)
            let center = CGPoint(x:(point.x+other.x)/2,y:(point.y+other.y)/2)
            let angle = atan2(other.y-point.y,other.x-point.x)-atan2(o.y-origin.y,o.x-origin.x)
            let scale = max(0.01,min(100,hypot(other.x-point.x,other.y-point.y)/max(0.1,hypot(o.x-origin.x,o.y-origin.y))))
            let delta = CGAffineTransform(translationX:-pivot.x,y:-pivot.y)
                .concatenating(CGAffineTransform(scaleX:scale,y:scale))
                .concatenating(CGAffineTransform(rotationAngle:angle))
                .concatenating(CGAffineTransform(translationX:center.x,y:center.y))
            changed = start; changed.pose = start.pose.concatenating(delta)
        } else { changed = start.dragged(tool: tool, from: origin, to: point, divisions: divisions, radius: radius) }
        if snapping && (pointers.count == 2 || tool == .move || tool == .scale || tool == .rotate) {
            changed = changed.snapped(to: targets, angles: angles, step: snapAngle,
                distance: CGFloat(20*snapStrength)/documentToView.a, tolerance: CGFloat(6*snapStrength))
        }
        session.geometry = changed
        setNeedsDisplay()
    }
    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        touchesMoved(touches, with: event)
        pointers.removeAll { touches.contains($0) }
        rebase()
        if pointers.isEmpty { beforeDrag = nil; originalGesture = nil }
    }
    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        if let originalGesture { session.geometry = originalGesture }
        pointers = []; origins = []; originalGesture = nil; beforeDrag = nil; setNeedsDisplay()
    }
    override func draw(_ rect: CGRect) {
        guard let ctx = UIGraphicsGetCurrentContext() else { return }
        ctx.concatenate(documentToView)
        ctx.setFillColor(UIColor.white.cgColor); ctx.fill(canvas); ctx.clip(to: canvas)
        let g = session.geometry
        g.render(in: ctx, visible: canvas) { RasterDocument.draw(session.preview, in: $0, rect: g.rect) }
        let n = tool == .warp ? divisions : 1
        ctx.setStrokeColor(UIColor.systemOrange.cgColor); ctx.setLineWidth(1 / documentToView.a)
        for row in 0...n {
            for col in 0...16 {
                let p = g.vertex(row*16/n*17+col)
                if col == 0 { ctx.move(to: p) } else { ctx.addLine(to: p) }
            }
        }
        for col in 0...n {
            for row in 0...16 {
                let p = g.vertex(row*17+col*16/n)
                if row == 0 { ctx.move(to: p) } else { ctx.addLine(to: p) }
            }
        }
        ctx.strokePath()
        if tool == .warp || tool == .distort {
            let r = 3 / documentToView.a; ctx.setFillColor(UIColor.systemOrange.cgColor)
            for row in 0...n { for col in 0...n {
                let p = g.vertex(row*16/n*17+col*16/n)
                ctx.fillEllipse(in: CGRect(x:p.x-r,y:p.y-r,width:2*r,height:2*r))
            } }
        }
    }
}

extension EditorController {
    func showTransformEditor() {
        viewport.canvas.cancelStroke()
        do {
            let editor = try TransformController(document: document, session: SelectionTransformSession(document: document))
            editor.onApply = { [weak self] in self?.viewport.canvas.refreshSelection(); self?.changed() }
            let navigation = UINavigationController(rootViewController: editor); navigation.modalPresentationStyle = .fullScreen
            present(navigation, animated: true)
        } catch { showError(error) }
    }
}
