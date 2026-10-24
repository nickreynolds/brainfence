import BrainfenceCore
import SwiftUI

struct RulesView: View {
    @Environment(AppModel.self) private var model
    @State private var editing: EditorTarget?

    enum EditorTarget: Identifiable {
        case new
        case existing(BlockingRule)
        var id: String {
            switch self {
            case .new: "new"
            case .existing(let r): r.id
            }
        }
    }

    var body: some View {
        TimelineView(.periodic(from: .now, by: 60)) { context in
            List {
                if model.engine.rules.isEmpty {
                    Text("No blocking rules yet.")
                        .foregroundStyle(.secondary)
                }
                ForEach(model.engine.rules) { rule in
                    RuleRow(rule: rule, now: context.date) { editing = .existing(rule) }
                }
            }
        }
        .navigationTitle("Blocking Rules")
        .toolbar {
            Button {
                editing = .new
            } label: {
                Label("New Rule", systemImage: "plus")
            }
        }
        .sheet(item: $editing) { target in
            switch target {
            case .new: RuleEditorView(rule: nil)
            case .existing(let rule): RuleEditorView(rule: rule)
            }
        }
    }
}

private struct RuleRow: View {
    let rule: BlockingRule
    let now: Date
    let edit: () -> Void
    @Environment(AppModel.self) private var model

    private var isBlocking: Bool { model.engine.state.activeBlocks.contains { $0.rule.id == rule.id } }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Image(systemName: !rule.isActive ? "pause.circle" : isBlocking ? "lock.fill" : "lock.open")
                    .foregroundStyle(!rule.isActive ? Color.secondary : isBlocking ? .orange : .green)
                Text(rule.name).font(.headline)
                if !rule.isActive {
                    Text("Inactive").font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                Button("Edit", action: edit)
            }
            Text(BlockedTargetsSummary(rule: rule).text)
                .font(.caption)
                .foregroundStyle(.secondary)
            Text(conditionSummary)
                .font(.caption)
                .foregroundStyle(.secondary)
            if rule.hasPendingChanges {
                PendingChangeBanner(rule: rule, now: now)
            }
        }
        .padding(.vertical, 4)
    }

    private var conditionSummary: String {
        if rule.conditionTaskIds.isEmpty { return "Always blocks while active" }
        let titles = rule.conditionTaskIds.map { model.engine.task(id: $0)?.title ?? "Unknown task" }
        let joiner = rule.conditionLogic == "any" ? " or " : " and "
        return "Unblocks after " + titles.joined(separator: joiner)
    }
}

/// "Changes take effect in 23h 47m" with a cancel button.
struct PendingChangeBanner: View {
    let rule: BlockingRule
    let now: Date
    @Environment(AppModel.self) private var model
    @State private var error: String?

    var body: some View {
        HStack {
            Image(systemName: "clock.badge.exclamationmark")
            VStack(alignment: .leading) {
                Text(message).font(.caption.weight(.medium))
                if let error { Text(error).font(.caption).foregroundStyle(.red) }
            }
            Spacer()
            Button("Cancel Change") {
                Task {
                    do { try await model.cancelPendingChange(ruleId: rule.id) } catch { self.error = error.localizedDescription }
                }
            }
            .controlSize(.small)
        }
        .padding(8)
        .background(Color.yellow.opacity(0.15), in: RoundedRectangle(cornerRadius: 6))
    }

    private var message: String {
        guard let at = Timestamp.parse(rule.changesApplyAt) else { return "A change is pending" }
        let remaining = at.timeIntervalSince(now)
        if remaining <= 0 { return "Pending change is being applied…" }
        let hours = Int(remaining) / 3600
        let minutes = (Int(remaining) % 3600) / 60
        return "Pending change takes effect in \(hours)h \(minutes)m"
    }
}
