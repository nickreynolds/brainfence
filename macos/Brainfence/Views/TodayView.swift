import BrainfenceCore
import SwiftUI

struct TodayView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        // Re-render every 30 s so time-based labels ("Overdue", "Available
        // from …") stay correct without any data change.
        TimelineView(.periodic(from: .now, by: 30)) { context in
            content(now: context.date)
        }
        .navigationTitle("Today")
    }

    @ViewBuilder
    private func content(now: Date) -> some View {
        let rows = model.engine.tasks.map { TaskRowModel(task: $0, now: now, engine: model.engine) }
        let today = rows.filter(\.isToday)
        let later = rows.filter { !$0.isToday }

        List {
            if model.engine.state.isBlocking {
                Section("Blocking now") {
                    ForEach(model.engine.state.activeBlocks) { block in
                        ActiveBlockRow(block: block)
                    }
                }
            }

            Section("Today") {
                if today.isEmpty {
                    Text(model.engine.tasks.isEmpty ? "No tasks yet — create them on your phone." : "Nothing left for today.")
                        .foregroundStyle(.secondary)
                }
                ForEach(today) { row in TaskRow(row: row) }
            }

            if !later.isEmpty {
                Section("Later") {
                    ForEach(later) { row in TaskRow(row: row) }
                }
            }
        }
    }
}

/// Precomputed display state for a task row.
struct TaskRowModel: Identifiable {
    let task: BrainTask
    let status: OccurrenceStatus
    let phase: TimeGatePhase?
    let enforcingRules: [BlockingRule]
    let isBlockingNow: Bool

    var id: String { task.id }

    @MainActor
    init(task: BrainTask, now: Date, engine: BlockingEngine) {
        self.task = task
        self.status = computeOccurrenceStatus(
            recurrenceType: task.recurrenceType,
            recurrenceConfig: task.recurrenceConfig,
            availableFrom: task.availableFrom,
            dueAt: task.dueAt,
            lastCompletionAt: Timestamp.parse(task.lastCompletionAt),
            now: now
        )
        self.phase = computeTaskPhase(availableFrom: task.availableFrom, dueAt: task.dueAt, now: now)
        self.enforcingRules = engine.enforcingRules(for: task.id)
        self.isBlockingNow = engine.state.unmetTaskIds.contains(task.id)
    }

    var isDone: Bool { task.completedToday || status == .completed }

    /// Due or done today (or blocking), as opposed to a future occurrence.
    var isToday: Bool {
        if isBlockingNow { return true }
        switch status {
        case .due, .completed: return true
        case .notDue: return phase == .beforeStart
        case .upcoming: return false
        }
    }

    var detail: String {
        if isDone { return "Done" }
        switch status {
        case .upcoming(let date):
            return "Next: \(date.formatted(.relative(presentation: .named)))"
        case .notDue:
            if phase == .beforeStart, let from = task.availableFrom { return "Available from \(from)" }
            return "Not due now"
        case .due, .completed:
            if phase == .pastDue { return "Overdue" + (task.dueAt.map { " since \($0)" } ?? "") }
            if let due = task.dueAt { return "Due by \(due)" }
            return "Due today"
        }
    }

    /// Due now — or blocking, which must always be resolvable from here.
    var canComplete: Bool {
        guard !isDone, task.isQuickCompletable, phase != .beforeStart else { return false }
        return status == .due || isBlockingNow
    }
}

private struct TaskRow: View {
    let row: TaskRowModel
    @Environment(AppModel.self) private var model
    @State private var isCompleting = false
    @State private var error: String?

    var body: some View {
        HStack(alignment: .center, spacing: 10) {
            Image(systemName: row.isDone ? "checkmark.circle.fill" : row.isBlockingNow ? "exclamationmark.circle.fill" : "circle")
                .foregroundStyle(row.isDone ? .green : row.isBlockingNow ? .orange : .secondary)
                .font(.title3)

            VStack(alignment: .leading, spacing: 3) {
                Text(row.task.title)
                    .strikethrough(row.isDone, color: .secondary)
                HStack(spacing: 8) {
                    Text(row.detail)
                    if !row.isDone, let hint = row.task.verificationHint {
                        Text("· \(hint)")
                    }
                }
                .font(.caption)
                .foregroundStyle(.secondary)
                if let error {
                    Text(error).font(.caption).foregroundStyle(Palette.error)
                }
            }

            Spacer()

            if !row.enforcingRules.isEmpty {
                Label(row.enforcingRules.map(\.name).joined(separator: ", "), systemImage: "lock.fill")
                    .labelStyle(.titleAndIcon)
                    .font(.caption)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .background((row.isBlockingNow ? Color.orange : Color.secondary).opacity(0.15), in: Capsule())
                    .help("Required by these blocking rules")
            }

            if row.canComplete {
                Button(isCompleting ? "Completing…" : "Complete") { complete() }
                    .disabled(isCompleting)
            }
        }
        .padding(.vertical, 2)
    }

    private func complete() {
        isCompleting = true
        error = nil
        Task {
            do {
                try await model.complete(row.task)
            } catch {
                self.error = error.localizedDescription
            }
            isCompleting = false
        }
    }
}

/// A rule that is blocking now: what it blocks on this Mac and what lifts it.
struct ActiveBlockRow: View {
    let block: RuleBlock
    @Environment(AppModel.self) private var model

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Image(systemName: "lock.fill").foregroundStyle(.orange)
                Text(block.rule.name).font(.headline)
            }
            Text(BlockedTargetsSummary(rule: block.rule).text)
                .font(.caption)
                .foregroundStyle(.secondary)
            let unmet = block.rule.conditionTaskIds.filter(block.unmetTaskIds.contains)
            ForEach(unmet, id: \.self) { id in
                BlockingTaskRow(taskId: id, task: model.engine.task(id: id))
            }
            if block.rule.conditionTaskIds.isEmpty {
                Text("No condition tasks — blocks whenever active.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 4)
    }
}

/// "Blocks Discord, Slack · reddit.com, x.com · 2 phone apps"
@MainActor
struct BlockedTargetsSummary {
    let rule: BlockingRule

    var text: String {
        var parts: [String] = []
        let apps = rule.macOSBundleIDs.map(InstalledApps.displayName(forBundleID:))
        if !apps.isEmpty { parts.append(apps.joined(separator: ", ")) }
        if !rule.blockedDomains.isEmpty { parts.append(rule.blockedDomains.joined(separator: ", ")) }
        let other = rule.otherPlatformApps.count
        if other > 0 { parts.append("\(other) phone app\(other == 1 ? "" : "s")") }
        return parts.isEmpty ? "Blocks nothing yet" : "Blocks " + parts.joined(separator: " · ")
    }
}
