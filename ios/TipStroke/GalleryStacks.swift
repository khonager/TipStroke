import UIKit

struct GalleryStacks: Codable {
    struct Stack: Codable {
        var name: String
        var members: [UUID]
    }
    var order: [UUID] = []
    var stacks: [UUID: Stack] = [:]

    mutating func reconcile(_ drawings: [UUID]) {
        let available = Set(drawings)
        for key in Array(stacks.keys) {
            stacks[key]?.members.removeAll { !available.contains($0) }
            if stacks[key]?.members.isEmpty == true { stacks[key] = nil }
        }
        let members = Set(stacks.values.flatMap(\.members))
        order.removeAll { (stacks[$0] == nil && !available.contains($0)) || members.contains($0) }
        let existing = Set(order)
        order = drawings.filter { !existing.contains($0) && !members.contains($0) } + order
    }
    mutating func move(_ id: UUID, onto target: UUID, group: Bool, after: Bool, inside parent: UUID?) {
        guard id != target else { return }
        if let parent {
            guard var rows = stacks[parent]?.members, rows.contains(id), rows.contains(target) else { return }
            rows.removeAll { $0 == id }
            guard let index = rows.firstIndex(of: target) else { return }
            rows.insert(id, at: index + (after ? 1 : 0)); stacks[parent]?.members = rows
            return
        }
        guard order.contains(id), order.contains(target) else { return }
        order.removeAll { $0 == id }
        guard let index = order.firstIndex(of: target) else { return }
        if group && stacks[id] == nil {
            if stacks[target] != nil { stacks[target]?.members.append(id) }
            else {
                let stack = UUID()
                stacks[stack] = Stack(name: "Stack", members: [target, id])
                order[index] = stack
            }
        } else { order.insert(id, at: index + (after ? 1 : 0)) }
    }
    mutating func unstack(_ id: UUID) {
        guard let index = order.firstIndex(of: id), let stack = stacks.removeValue(forKey: id) else { return }
        order.replaceSubrange(index...index, with: stack.members)
    }
    mutating func moveOut(_ id: UUID, from parent: UUID) {
        guard stacks[parent]?.members.contains(id) == true else { return }
        stacks[parent]?.members.removeAll { $0 == id }
        let index = order.firstIndex(of: parent) ?? 0
        order.insert(id, at: index)
        if stacks[parent]?.members.isEmpty == true { unstack(parent) }
    }
}

extension GalleryController: UITableViewDragDelegate, UITableViewDropDelegate {
    func tableView(_ tableView: UITableView, itemsForBeginning session: UIDragSession, at path: IndexPath) -> [UIDragItem] {
        guard !pickingExports, displayed.indices.contains(path.row) else { return [] }
        let id = displayed[path.row]
        let item = UIDragItem(itemProvider: NSItemProvider(object: id.uuidString as NSString))
        item.localObject = id
        return [item]
    }
    func tableView(_ tableView: UITableView, dropSessionDidUpdate session: UIDropSession,
                   withDestinationIndexPath path: IndexPath?) -> UITableViewDropProposal {
        guard session.localDragSession != nil, let path, displayed.indices.contains(path.row),
              let id = session.items.first?.localObject as? UUID, displayed.contains(id) else {
            return UITableViewDropProposal(operation: .forbidden)
        }
        let group = groupsDrop(session, at: path, from: id)
        return UITableViewDropProposal(operation: .move, intent: group ? .insertIntoDestinationIndexPath : .insertAtDestinationIndexPath)
    }
    private func groupsDrop(_ session: UIDropSession, at path: IndexPath, from id: UUID) -> Bool {
        let rect = tableView.rectForRow(at: path)
        return openStack == nil && layout.stacks[id] == nil &&
            abs(session.location(in: tableView).y - rect.midY) < rect.height / 4
    }
    func tableView(_ tableView: UITableView, performDropWith coordinator: UITableViewDropCoordinator) {
        guard let item = coordinator.items.first, let id = item.dragItem.localObject as? UUID,
              let path = coordinator.destinationIndexPath, displayed.indices.contains(path.row) else { return }
        let target = displayed[path.row]
        let after = coordinator.session.location(in: tableView).y > tableView.rectForRow(at: path).midY
        layout.move(id, onto: target, group: groupsDrop(coordinator.session, at: path, from: id), after: after, inside: openStack)
        saveLayout()
    }
}
