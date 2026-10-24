import BrainfenceCore
import SwiftUI

/// The task list (Active / Done / Upcoming) with a stack to each task's
/// screen. Port of Android's TaskListScreen.
struct TasksView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var navigation = model.navigation
        NavigationStack(path: $navigation.taskPath) {
            TaskListView()
                .navigationDestination(for: String.self) { taskId in
                    TaskScreen(taskId: taskId)
                }
        }
    }
}

private struct TaskListView: View {
    enum Tab: String, CaseIterable, Identifiable {
        case active = "Active", done = "Done", upcoming = "Upcoming"
        var id: String { rawValue }
    }

    @Environment(AppModel.self) private var model
    @State private var tab: Tab = .active
    @State private var confirming: BrainTask?
    @State private var shoppingCounts: [String: Int] = [:]
    @State private var error: String?

    var body: some View {
        TimelineView(.periodic(from: .now, by: 30)) { context in
            let now = context.date
            let partition = partitionTasks(model.engine.tasks, now: now)
            let shown = tasks(in: tab, partition)
            List {
                Picker("Show", selection: $tab) {
                    ForEach(Tab.allCases) { t in
                        let count = tasks(in: t, partition).count
                        Text(count > 0 ? "\(t.rawValue) (\(count))" : t.rawValue).tag(t)
                    }
                }
                .pickerStyle(.segmented)
                .labelsHidden()

                if tab == .active && !model.engine.rules.isEmpty {
                    BlockingStatusCard()
                }
                ErrorText(error: error)

                if shown.isEmpty {
                    Text(emptyMessage).foregroundStyle(.secondary)
                }
                ForEach(shown) { task in
                    TaskListRow(
                        task: task,
                        now: now,
                        tab: tab,
                        shoppingCount: task.taskType == "shopping" ? shoppingCounts[task.id] ?? 0 : nil
                    ) {
                        tap(task)
                    }
                }
            }
        }
        .navigationTitle("Tasks")
        .toolbar {
            Button {
                model.navigation.editor = .init(taskId: nil)
            } label: {
                Label("New Task", systemImage: "plus")
            }
        }
        .confirmationDialog("Complete task?", isPresented: Binding(get: { confirming != nil }, set: { if !$0 { confirming = nil } }), presenting: confirming) { task in
            Button("Complete") { complete(task) }
            Button("Cancel", role: .cancel) {}
        } message: { task in
            Text(task.title)
        }
        .reloading(on: ShoppingRepository.tables, db: model.db) {
            shoppingCounts = (try? await model.shoppingRepository.openItemCounts()) ?? [:]
        }
    }

    private func tasks(in tab: Tab, _ partition: TaskPartition) -> [BrainTask] {
        switch tab {
        case .active: partition.active
        case .done: partition.done
        case .upcoming: partition.upcoming
        }
    }

    private var emptyMessage: String {
        if model.engine.tasks.isEmpty { return "No tasks yet. Click + to create one." }
        return switch tab {
        case .active: "All tasks completed or upcoming"
        case .done: "No tasks completed yet today"
        case .upcoming: "No upcoming tasks"
        }
    }

    private func tap(_ task: BrainTask) {
        if tab == .upcoming {
            model.navigation.editor = .init(taskId: task.id)
        } else if task.opensTaskScreen {
            model.navigation.taskPath.append(task.id)
        } else if !task.completedToday && !isBeforeStart(task) {
            confirming = task
        }
    }

    private func complete(_ task: BrainTask) {
        Task {
            do {
                try await model.complete(task)
                error = nil
            } catch {
                self.error = error.localizedDescription
            }
        }
    }
}

private struct TaskListRow: View {
    let task: BrainTask
    let now: Date
    let tab: TaskListView.Tab
    let shoppingCount: Int?
    let onTap: () -> Void
    @Environment(AppModel.self) private var model

    var body: some View {
        if let shoppingCount {
            // Shopping lists are permanent: no completion, no schedule, just the open count.
            Button(action: onTap) {
                HStack(spacing: 10) {
                    Image(systemName: "cart").foregroundStyle(shoppingCount > 0 ? Palette.primary : .secondary)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(task.title)
                        Text(shoppingCount > 0 ? "\(plural(shoppingCount, "item")) to buy" : "List is empty")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        } else {
            row
        }
    }

    private var phase: TimeGatePhase? { computeTaskPhase(availableFrom: task.availableFrom, dueAt: task.dueAt, now: now) }
    private var isEnforced: Bool { !model.engine.enforcingRules(for: task.id).isEmpty }
    private var isBlocking: Bool { model.engine.state.unmetTaskIds.contains(task.id) }

    @ViewBuilder
    private var row: some View {
        let showAsDone = tab == .done
        let completedRecurring = tab == .upcoming && task.completedToday && task.recurrenceType != nil
        let locked = (tab == .upcoming && !completedRecurring) || (phase == .beforeStart && !task.completedToday)
        let overdue = phase == .pastDue && !task.completedToday
        let canTap = tab == .upcoming || (!showAsDone && !locked && !completedRecurring)

        Button(action: onTap) {
            HStack(spacing: 10) {
                leadingIcon(done: showAsDone || completedRecurring, locked: locked, overdue: overdue)
                VStack(alignment: .leading, spacing: 2) {
                    Text(task.title)
                    if let meditation = model.timers.meditations[task.id], !showAsDone, !completedRecurring {
                        MeditationProgressLine(session: meditation, now: model.timers.now)
                    }
                    if let line = supportingLine(showAsDone: showAsDone, completedRecurring: completedRecurring, overdue: overdue) {
                        Text(line)
                            .font(.caption)
                            .foregroundStyle(overdue && isBlocking ? Palette.error : .secondary)
                    }
                }
                Spacer()
                if isEnforced {
                    Image(systemName: "nosign")
                        .foregroundStyle(Palette.error)
                        .help("A condition of an active blocking rule")
                }
            }
            .contentShape(Rectangle())
            .opacity(showAsDone || completedRecurring || locked ? 0.6 : 1)
        }
        .buttonStyle(.plain)
        .disabled(!canTap)
    }

    @ViewBuilder
    private func leadingIcon(done: Bool, locked: Bool, overdue: Bool) -> some View {
        if done {
            Image(systemName: "checkmark.circle.fill").foregroundStyle(Palette.primary)
        } else if locked {
            Image(systemName: "lock").foregroundStyle(.secondary)
        } else if overdue {
            Image(systemName: "exclamationmark.triangle").foregroundStyle(isBlocking ? Palette.error : .secondary)
        } else {
            Image(systemName: "circle").foregroundStyle(.secondary)
        }
    }

    private func supportingLine(showAsDone: Bool, completedRecurring: Bool, overdue: Bool) -> String? {
        if completedRecurring {
            let next = computeNextOccurrence(
                recurrenceType: task.recurrenceType, recurrenceConfig: task.recurrenceConfig,
                lastCompletionAt: Timestamp.parse(task.lastCompletionAt), now: now
            )
            return next.map { "Next: \($0.formatted(.relative(presentation: .named)))" } ?? "Completed"
        }
        if tab == .upcoming, let from = task.availableFrom { return "Available at \(from)" }
        if overdue && isBlocking { return "Overdue — blocking until completed" }
        if overdue { return "Overdue" }
        if phase == .active, let due = task.dueAt, !task.completedToday { return "Complete before \(due)" }
        var parts: [String] = []
        if let r = task.recurrenceType {
            var line = r.prefix(1).uppercased() + r.dropFirst()
            if !showAsDone && isEnforced { line += task.dueAt.map { " · Due by \($0)" } ?? " · Due today" }
            parts.append(line)
        }
        if !showAsDone, task.isLocationTask { parts.append("Complete on your phone") }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }
}

private struct MeditationProgressLine: View {
    let session: MeditationSession
    let now: Date

    var body: some View {
        let target = max(1, session.timer.targetSeconds)
        let elapsed = min(session.elapsed(at: now), target)
        VStack(alignment: .leading, spacing: 2) {
            Text("\(elapsed / 60) / \(target / 60) min today" + (session.timer.isRunning ? " · counting" : ""))
                .font(.caption)
                .foregroundStyle(session.timer.isRunning ? Palette.primary : .secondary)
            ProgressView(value: Double(elapsed), total: Double(target))
                .frame(maxWidth: 240)
        }
    }
}

/// What's blocked right now and which tasks lift it. Port of Android's
/// BlockingStatusCard.
private struct BlockingStatusCard: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let state = model.engine.state
        if !state.isBlocking {
            Label("All clear — nothing blocked", systemImage: "checkmark.circle")
                .padding(10)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Palette.primaryContainer, in: RoundedRectangle(cornerRadius: 10))
        } else {
            let unmet = state.activeBlocks.flatMap { $0.rule.conditionTaskIds.filter($0.unmetTaskIds.contains) }
            let unique = unmet.reduce(into: [String]()) { if !$0.contains($1) { $0.append($1) } }
            VStack(alignment: .leading, spacing: 8) {
                Label(summary(state), systemImage: "nosign").font(.headline)
                ForEach(state.activeBlocks) { block in
                    Text("\(block.rule.name): \(BlockedTargetsSummary(rule: block.rule).text)")
                        .font(.caption)
                }
                if !unique.isEmpty {
                    Text("Complete to unblock:").font(.caption.weight(.semibold)).padding(.top, 4)
                    ForEach(unique, id: \.self) { id in
                        BlockingTaskRow(taskId: id, task: model.engine.task(id: id))
                    }
                }
            }
            .padding(10)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Palette.errorContainer, in: RoundedRectangle(cornerRadius: 10))
            .foregroundStyle(Palette.onErrorContainer)
        }
    }

    private func summary(_ state: BlockingState) -> String {
        var parts: [String] = []
        if !state.blockedBundleIDs.isEmpty { parts.append(plural(state.blockedBundleIDs.count, "app")) }
        if !state.blockedDomains.isEmpty { parts.append(plural(state.blockedDomains.count, "website")) }
        return parts.isEmpty ? "Blocking on your other devices" : parts.joined(separator: " and ") + " blocked"
    }
}

/// Routes a task to its screen by type.
struct TaskScreen: View {
    let taskId: String
    @Environment(AppModel.self) private var model

    var body: some View {
        Group {
            if let task = model.engine.task(id: taskId) {
                content(task)
                    .navigationTitle(task.title)
                    .toolbar {
                        Button {
                            model.navigation.editor = .init(taskId: task.id)
                        } label: {
                            Label("Edit Task", systemImage: "pencil")
                        }
                    }
            } else {
                Text("This task is no longer active.")
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
    }

    @ViewBuilder
    private func content(_ task: BrainTask) -> some View {
        if task.taskType == "shopping" {
            ShoppingListScreen(task: task)
        } else if task.taskType == "routine" || task.taskType == "workout" {
            RoutineTaskScreen(task: task)
        } else if task.taskType == "journal" {
            JournalTaskScreen(task: task)
        } else if task.taskType == "report" {
            ReportTaskScreen(task: task)
        } else if task.verificationType == "gps" {
            LocationTaskScreen(task: task)
        } else if task.verificationType == "meditation" {
            MeditationTaskScreen(task: task)
        } else if task.verificationType == "duration" || task.taskType == "timed" {
            DurationTaskScreen(task: task)
        } else {
            SimpleTaskScreen(task: task)
        }
    }
}
