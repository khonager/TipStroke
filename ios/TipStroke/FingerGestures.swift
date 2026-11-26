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
                        slot.set(command)
                        if slot == .drag { self.sharedControls.setFingerDrawing(enabled: command != .navigate) }
                        else { self.refreshFingerMode() }
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

final class GallerySettingsController: UITableViewController {
    override func viewDidLoad() {
        super.viewDidLoad()
        title = "Settings"
        overrideUserInterfaceStyle = .dark
    }
    override func numberOfSections(in tableView: UITableView) -> Int { 2 }
    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        section == 0 ? 1 : FingerBinding.allCases.count + 1
    }
    override func tableView(_ tableView: UITableView, titleForHeaderInSection section: Int) -> String? {
        section == 0 ? "Image import picker" : "Finger actions"
    }
    override func tableView(_ tableView: UITableView, titleForFooterInSection section: Int) -> String? {
        section == 0 ? "Used when starting a drawing from an image and adding an image layer." : nil
    }
    override func tableView(_ tableView: UITableView, cellForRowAt path: IndexPath) -> UITableViewCell {
        let cell = UITableViewCell(style: .value1, reuseIdentifier: nil)
        if path.section == 0 {
            cell.textLabel?.text = "Open images from"
            let picker = UISegmentedControl(items: ["Photos", "Files"])
            picker.selectedSegmentIndex = UserDefaults.standard.useFilesForImages ? 1 : 0
            picker.addAction(UIAction { [weak picker] _ in
                UserDefaults.standard.useFilesForImages = picker?.selectedSegmentIndex == 1
            }, for: .valueChanged)
            cell.accessoryView = picker
        } else if path.row == FingerBinding.allCases.count {
            cell.textLabel?.text = "Hold for drawing tools"
            let toggle = UISwitch()
            toggle.isOn = UserDefaults.standard.object(forKey: "holdDrawingTools") as? Bool ?? true
            toggle.addAction(UIAction { [weak toggle] _ in
                UserDefaults.standard.set(toggle?.isOn == true, forKey: "holdDrawingTools")
            }, for: .valueChanged)
            cell.accessoryView = toggle
        } else {
            let slot = FingerBinding.allCases[path.row]
            cell.textLabel?.text = slot.rawValue
            cell.detailTextLabel?.text = slot.command.rawValue
            cell.accessoryType = .disclosureIndicator
        }
        return cell
    }
    override func tableView(_ tableView: UITableView, didSelectRowAt path: IndexPath) {
        tableView.deselectRow(at: path, animated: true)
        guard path.section == 1, path.row < FingerBinding.allCases.count else { return }
        let slot = FingerBinding.allCases[path.row]
        let choices = UIAlertController(title: slot.rawValue, message: nil, preferredStyle: .actionSheet)
        for command in slot.choices {
            choices.addAction(UIAlertAction(title: command.rawValue, style: .default) { _ in
                slot.set(command)
                if slot == .drag { UserDefaults.standard.set(command != .navigate, forKey: "fingerDrawing") }
                tableView.reloadData()
            })
        }
        choices.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        choices.popoverPresentationController?.sourceView = tableView
        choices.popoverPresentationController?.sourceRect = tableView.rectForRow(at: path)
        present(choices, animated: true)
    }
}
