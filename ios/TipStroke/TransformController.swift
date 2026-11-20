import UIKit

final class TransformController: UIViewController {
    let document: RasterDocument
    let session: SelectionTransformSession
    var onApply: (() -> Void)?
    private let editor: TransformPreviewView
    private var copyPaint = false

    init(document: RasterDocument, session: SelectionTransformSession) {
        self.document = document; self.session = session
        editor = TransformPreviewView(session: session, canvas: document.bounds)
        super.init(nibName: nil, bundle: nil)
    }
    required init?(coder: NSCoder) { fatalError("Use init(document:session:)") }
    override func viewDidLoad() {
        super.viewDidLoad()
        title = session.photo == nil ? "Transform selection" : "Transform photo"
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
            var items: [UIMenuElement] = [grid,radius,filter,reset]
            if self.session.photo == nil {
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
}

private final class TransformPreviewView: UIView {
    let session: SelectionTransformSession
    let canvas: CGRect
    var tool: TransformGeometry.Tool = .move
    var divisions = 4
    var radius: CGFloat = 64
    private var origin = CGPoint.zero
    private var beforeDrag: TransformGeometry?
    init(session: SelectionTransformSession, canvas: CGRect) {
        self.session = session; self.canvas = canvas
        super.init(frame: .zero)
        backgroundColor = .secondarySystemBackground; isOpaque = true; contentMode = .redraw
        accessibilityLabel = "Transform preview"
        addGestureRecognizer(UIPanGestureRecognizer(target: self, action: #selector(drag(_:))))
    }
    required init?(coder: NSCoder) { fatalError("Use init(session:canvas:)") }
    private var documentToView: CGAffineTransform {
        let s = max(0.0001,min((bounds.width-24)/canvas.width,(bounds.height-24)/canvas.height))
        return CGAffineTransform(translationX: bounds.midX - canvas.midX*s, y: bounds.midY - canvas.midY*s).scaledBy(x: s,y: s)
    }
    @objc private func drag(_ pan: UIPanGestureRecognizer) {
        let p = pan.location(in: self).applying(documentToView.inverted())
        switch pan.state {
        case .began:
            let delta = pan.translation(in: self)
            origin = CGPoint(x: p.x-delta.x/documentToView.a, y: p.y-delta.y/documentToView.d)
            beforeDrag = session.geometry
            fallthrough
        case .changed, .ended:
            if let beforeDrag {
                session.geometry = beforeDrag.dragged(tool: tool, from: origin, to: p, divisions: divisions, radius: radius)
            }
            if pan.state == .ended { beforeDrag = nil }
        case .cancelled, .failed:
            if let beforeDrag { session.geometry = beforeDrag }; beforeDrag = nil
        default: return
        }
        setNeedsDisplay()
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
