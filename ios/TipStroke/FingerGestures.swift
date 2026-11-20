import UIKit

enum FingerCommand: String, CaseIterable {
    case draw = "Draw", navigate = "Navigate canvas", smudge = "Smudge", pick = "Pick color"
    case undo = "Undo", redo = "Redo", disabled = "Disabled"
}
enum FingerBinding: String, CaseIterable {
    case drag = "One-finger drag", hold = "One-finger hold", two = "Two-finger tap", three = "Three-finger tap"
    var choices: [FingerCommand] {
        switch self {
        case .drag: return FingerCommand.allCases
        case .hold: return [.pick, .undo, .redo, .disabled]
        case .two, .three: return [.undo, .redo, .disabled]
        }
    }
    var command: FingerCommand {
        let saved = UserDefaults.standard.string(forKey: "finger." + rawValue).flatMap(FingerCommand.init(rawValue:))
        if let saved, choices.contains(saved) { return saved }
        switch self { case .drag: return .draw; case .hold: return .pick; case .two: return .undo; case .three: return .redo }
    }
    func set(_ value: FingerCommand) { UserDefaults.standard.set(value.rawValue, forKey: "finger." + rawValue) }
}

extension EditorController {
    func configureFingerGestures() {
        let canvas = viewport.canvas
        canvas.onFingerHistory = { [weak self] command in
            guard let self else { return }
            if command == .undo { self.document.undo() } else { self.document.redo() }
            self.changed()
        }
        canvas.configureInput()
    }
    func fingerSettings() {
        viewport.canvas.cancelStroke()
        let menu = UIAlertController(title: "Finger gestures", message: nil, preferredStyle: .actionSheet)
        for slot in FingerBinding.allCases {
            menu.addAction(UIAlertAction(title: slot.rawValue + " · " + slot.command.rawValue, style: .default) { [weak self] _ in
                guard let self else { return }
                let options = UIAlertController(title: slot.rawValue, message: nil, preferredStyle: .actionSheet)
                for command in slot.choices {
                    options.addAction(UIAlertAction(title: command.rawValue + (command == slot.command ? " ✓" : ""), style: .default) { _ in
                        slot.set(command); self.viewport.canvas.configureInput()
                    })
                }
                options.addAction(UIAlertAction(title: "Cancel", style: .cancel)); self.anchor(options); self.present(options, animated: true)
            })
        }
        menu.addAction(UIAlertAction(title: "Hold delay", style: .default) { [weak self] _ in
            guard let self else { return }
            let options = UIAlertController(title: "Hold delay", message: nil, preferredStyle: .actionSheet)
            for milliseconds in [150, 250, 420, 550, 750, 1000] {
                options.addAction(UIAlertAction(title: "\(milliseconds) ms", style: .default) { _ in
                    UserDefaults.standard.set(milliseconds, forKey: "finger.holdDelay")
                    self.viewport.canvas.configureInput()
                })
            }
            options.addAction(UIAlertAction(title: "Cancel", style: .cancel)); self.anchor(options); self.present(options, animated: true)
        })
        menu.addAction(UIAlertAction(title: "Done", style: .cancel)); anchor(menu); present(menu, animated: true)
    }
}
