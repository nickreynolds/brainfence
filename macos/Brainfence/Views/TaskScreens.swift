import AppKit
import BrainfenceCore
import SwiftUI

/// A manual task opened directly (e.g. from the shield or a notification).
struct SimpleTaskScreen: View {
    let task: BrainTask
    @Environment(AppModel.self) private var model
    @State private var confirming = false
    @State private var error: String?

    var body: some View {
        TimelineView(.periodic(from: .now, by: 30)) { context in
            Form {
                if task.completedToday {
                    CompletedToday()
                } else {
                    if let description = task.description { Text(description) }
                    Text(scheduleSummary(task)).font(.caption).foregroundStyle(.secondary)
                    NotYetAvailable(task: task, now: context.date)
                    Button("Complete") { confirming = true }
                        .keyboardShortcut(.defaultAction)
                        .disabled(isBeforeStart(task, now: context.date))
                    ErrorText(error: error)
                }
                CompletionHistory(taskId: task.id)
            }
            .formStyle(.grouped)
        }
        .confirmationDialog("Complete task?", isPresented: $confirming) {
            Button("Complete") {
                Task {
                    do { try await model.complete(task) } catch { self.error = error.localizedDescription }
                }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text(task.title)
        }
    }
}

/// A timed session. The timer runs in the TimerManager, so it keeps going
/// when the window closes.
struct DurationTaskScreen: View {
    let task: BrainTask
    @Environment(AppModel.self) private var model
    @State private var confirmingCancel = false

    var body: some View {
        let config = parseDurationConfig(task.verificationConfig)
        let session = model.timers.durations[task.id]
        // The timer manager's clock only advances while a timer runs.
        let now = session != nil ? model.timers.now : Date()
        VStack(spacing: 16) {
            if task.completedToday {
                CompletedToday()
            } else if let config {
                if let session {
                    let elapsed = session.elapsed(at: now)
                    ProgressRing(progress: Double(elapsed) / Double(max(1, session.targetSeconds))) {
                        Text(formatClock(session.targetSeconds - elapsed)).font(.system(size: 36, weight: .medium, design: .rounded).monospacedDigit())
                    }
                    Text(session.isRunning ? "Running" : "Paused").foregroundStyle(.secondary)
                    Text("\(formatClock(elapsed)) elapsed of \(formatClock(session.targetSeconds))")
                        .font(.caption).foregroundStyle(.secondary)
                    HStack(spacing: 16) {
                        Button("Cancel", role: .destructive) { confirmingCancel = true }
                        if session.isRunning {
                            Button("Pause") { model.timers.pauseDuration(task.id) }
                        } else {
                            Button("Resume") { model.timers.resumeDuration(task.id) }
                                .keyboardShortcut(.defaultAction)
                        }
                    }
                } else {
                    Image(systemName: "timer").font(.system(size: 44)).foregroundStyle(Palette.primary)
                    Text("Duration required").font(.title3)
                    Text(formatClock(config.targetSeconds)).font(.system(size: 48, weight: .medium, design: .rounded).monospacedDigit())
                    NotYetAvailable(task: task, now: now)
                    Button("Start Timer") { model.timers.startDuration(task: task, targetSeconds: config.targetSeconds) }
                        .keyboardShortcut(.defaultAction)
                        .disabled(isBeforeStart(task, now: now))
                    Text("The timer keeps running if you close this window.").font(.caption).foregroundStyle(.secondary)
                }
            } else {
                Text("This task has no duration configured. Edit it to set one.").foregroundStyle(.secondary)
            }
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .confirmationDialog("Cancel timer?", isPresented: $confirmingCancel) {
            Button("Cancel Timer", role: .destructive) {
                model.timers.cancelDuration(task.id)
                model.navigation.taskPath.removeAll()
            }
            Button("Keep Going", role: .cancel) {}
        } message: {
            Text("Your progress will be lost.")
        }
    }
}

/// In-app meditation timer with the "short sit" middle way, or tracking time
/// in companion apps.
struct MeditationTaskScreen: View {
    let task: BrainTask
    @Environment(AppModel.self) private var model
    @State private var confirmingCancel = false
    @State private var todaysProof: String?

    var body: some View {
        let config = parseMeditationConfig(task.verificationConfig)
        let session = model.timers.meditations[task.id]
        let now = session != nil ? model.timers.now : Date()
        VStack(spacing: 14) {
            if let config {
                // Checked before completedToday: a session that banked a short
                // sit is already "completed" but may still be running.
                if let session, session.method == .inApp {
                    inApp(session, now: now)
                } else if task.completedToday {
                    completed
                } else if let session {
                    companion(session, config: config, now: now)
                } else {
                    notStarted(config, now: now)
                }
            } else {
                Text("This task has no meditation duration configured. Edit it to set one.").foregroundStyle(.secondary)
            }
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .reloading(on: ["task_completions"], db: model.db, id: task.id) {
            todaysProof = try? await model.completionRepository.todaysVerificationData(taskId: task.id)
        }
        .confirmationDialog("Cancel meditation?", isPresented: $confirmingCancel) {
            Button("Cancel Session", role: .destructive) {
                model.timers.cancelMeditation(task.id)
                model.navigation.taskPath.removeAll()
            }
            Button("Keep Going", role: .cancel) {}
        } message: {
            if let session, session.method == .inApp, session.shortSitSeconds > session.elapsed(at: now) {
                Text("Your progress will be lost. Stay until \(formatClock(session.shortSitSeconds)) and it counts as a short sit.")
            } else {
                Text("Your progress will be lost.")
            }
        }
    }

    private var completed: some View {
        let proof = JSONValue.parse(todaysProof) ?? .object([:])
        let actual = proof["actual_seconds"]?.intValue ?? 0
        let detail = actual > 0 ? (proof["short_sit"]?.boolValue == true ? "Short sit · \(formatMinutes(actual))" : formatMinutes(actual)) : nil
        return CompletedToday(detail: detail)
    }

    @ViewBuilder
    private func notStarted(_ config: MeditationConfig, now: Date) -> some View {
        let locked = isBeforeStart(task, now: now)
        Image(systemName: "figure.mind.and.body").font(.system(size: 44)).foregroundStyle(Palette.primary)
        Text("Meditation session").font(.title3)
        Text(formatClock(config.durationSeconds)).font(.system(size: 48, weight: .medium, design: .rounded).monospacedDigit())
        if config.bellIntervalSeconds > 0 {
            Text("Bell every \(formatClock(config.bellIntervalSeconds))").font(.caption).foregroundStyle(.secondary)
        }
        NotYetAvailable(task: task, now: now)
        Button("Start Meditation") { model.timers.startMeditationInApp(task: task, config: config, shortSit: false) }
            .keyboardShortcut(.defaultAction)
            .disabled(locked)
        if config.shortSitSeconds > 0 {
            Button("Short sit (\(formatMinutes(config.shortSitSeconds)))") {
                model.timers.startMeditationInApp(task: task, config: config, shortSit: true)
            }
            .disabled(locked)
            Text("Counts for today. You'll be offered the rest afterwards.").font(.caption).foregroundStyle(.secondary)
        }
        if !config.companionApps.isEmpty {
            Button("Use Companion App") { model.timers.startCompanionTracking(task: task, config: config) }
                .disabled(locked)
            Text("Counts time in \(config.companionApps.map(InstalledApps.displayName(forBundleID:)).joined(separator: ", "))")
                .font(.caption).foregroundStyle(.secondary)
        }
    }

    @ViewBuilder
    private func inApp(_ session: MeditationSession, now: Date) -> some View {
        let elapsed = session.elapsed(at: now)
        let target = session.timer.targetSeconds
        // A short sit counts down to its threshold first, then to the full target.
        let inShortSitPhase = session.shortSitIntent && !session.shortSitBanked
        let phaseTarget = inShortSitPhase ? session.shortSitSeconds : target
        if session.shortSitIntent && session.shortSitBanked {
            VStack(alignment: .leading, spacing: 4) {
                Text("Short sit complete — it counts for today.").font(.headline)
                Text("Keep going for the remaining \(formatClock(max(0, target - elapsed))), or finish here.").font(.caption)
            }
            .padding(10)
            .frame(maxWidth: 460, alignment: .leading)
            .background(Palette.tertiaryContainer, in: RoundedRectangle(cornerRadius: 8))
        }
        ProgressRing(progress: Double(elapsed) / Double(max(1, phaseTarget)), color: Palette.tertiary) {
            Text(formatClock(max(0, phaseTarget - elapsed))).font(.system(size: 36, weight: .medium, design: .rounded).monospacedDigit())
        }
        Text(!session.timer.isRunning ? "Paused" : inShortSitPhase ? "Short sit" : "Meditating")
            .foregroundStyle(session.timer.isRunning ? Palette.tertiary : .secondary)
        Text("\(formatClock(elapsed)) elapsed of \(formatClock(target))").font(.caption).foregroundStyle(.secondary)
        if session.pauses > 0 {
            Text(plural(session.pauses, "pause")).font(.caption).foregroundStyle(.secondary)
        }
        HStack(spacing: 16) {
            if session.shortSitBanked {
                // Past the threshold nothing is lost by stopping: offer Finish, not Cancel.
                Button("Finish here") { model.timers.finishMeditationEarly(task.id) }
            } else {
                Button("Cancel", role: .destructive) { confirmingCancel = true }
            }
            if session.timer.isRunning {
                Button("Pause") { model.timers.pauseMeditation(task.id) }
            } else {
                Button("Resume") { model.timers.resumeMeditation(task.id) }
                    .keyboardShortcut(.defaultAction)
            }
        }
    }

    @ViewBuilder
    private func companion(_ session: MeditationSession, config: MeditationConfig, now: Date) -> some View {
        let elapsed = session.elapsed(at: now)
        let names = config.companionApps.map(InstalledApps.displayName(forBundleID:))
        ProgressRing(progress: Double(elapsed) / Double(max(1, session.timer.targetSeconds))) {
            Text(formatClock(max(0, session.timer.targetSeconds - elapsed))).font(.system(size: 36, weight: .medium, design: .rounded).monospacedDigit())
        }
        Text("\(formatClock(elapsed)) accumulated of \(formatClock(session.timer.targetSeconds))").font(.caption).foregroundStyle(.secondary)
        Text(session.timer.isRunning
            ? "Counting in \(session.companionApp.map(InstalledApps.displayName(forBundleID:)) ?? "companion app")"
            : "Open \(names.joined(separator: " or ")) — time accumulates automatically")
            .foregroundStyle(session.timer.isRunning ? Palette.primary : .secondary)
        Text("Or meditate here instead").font(.caption).foregroundStyle(.secondary).padding(.top, 8)
        HStack {
            Button("Start timer") { model.timers.startMeditationInApp(task: task, config: config, shortSit: false) }
            if config.shortSitSeconds > 0 {
                Button("Short sit (\(formatMinutes(config.shortSitSeconds)))") {
                    model.timers.startMeditationInApp(task: task, config: config, shortSit: true)
                }
            }
        }
        .disabled(isBeforeStart(task, now: now))
        Button("Cancel", role: .destructive) { confirmingCancel = true }
            .buttonStyle(.borderless)
    }
}

/// Write a journal entry. The draft survives closing the window.
struct JournalTaskScreen: View {
    let task: BrainTask
    @Environment(AppModel.self) private var model
    @State private var text = ""
    @State private var isSaving = false
    @State private var error: String?

    private var draftKey: String { "journalDraft:\(task.id)" }

    var body: some View {
        TimelineView(.periodic(from: .now, by: 30)) { context in
            VStack(alignment: .leading, spacing: 12) {
                if task.completedToday {
                    CompletedToday()
                    Button("View journal history") { model.navigation.showJournal() }
                        .frame(maxWidth: .infinity)
                } else {
                    if let description = task.description { Text(description).foregroundStyle(.secondary) }
                    NotYetAvailable(task: task, now: context.date)
                    TextEditor(text: $text)
                        .font(.body)
                        .frame(minHeight: 240)
                        .overlay(RoundedRectangle(cornerRadius: 6).stroke(Palette.outlineVariant))
                        .onChange(of: text) { _, value in
                            UserDefaults.standard.set(value.isEmpty ? nil : value, forKey: draftKey)
                        }
                    ErrorText(error: error)
                    Button(isSaving ? "Saving…" : "Save entry") { save() }
                        .keyboardShortcut(.defaultAction)
                        .disabled(isSaving || text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || isBeforeStart(task, now: context.date))
                }
            }
            .padding(20)
        }
        .onAppear { text = UserDefaults.standard.string(forKey: draftKey) ?? "" }
    }

    private func save() {
        isSaving = true
        Task {
            do {
                try await model.complete(task, verificationData: JSONValue.object(["text": .string(text)]).jsonString)
                UserDefaults.standard.removeObject(forKey: draftKey)
                model.navigation.taskPath.removeAll()
            } catch {
                self.error = error.localizedDescription
            }
            isSaving = false
        }
    }
}

/// Answer the task's report.
struct ReportTaskScreen: View {
    let task: BrainTask
    @Environment(AppModel.self) private var model
    @State private var report: Report?
    @State private var loaded = false
    @State private var today: ReportSubmission?
    @State private var isSubmitting = false
    @State private var error: String?

    var body: some View {
        TimelineView(.periodic(from: .now, by: 30)) { context in
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    if task.completedToday, let today {
                        Text("Reported today").font(.title3.weight(.semibold))
                        ReportSubmissionSummary(submission: today)
                    } else if task.completedToday {
                        CompletedToday()
                    } else if !loaded {
                        ProgressView()
                    } else if let report {
                        if isBeforeStart(task, now: context.date) {
                            NotYetAvailable(task: task, now: context.date)
                        } else {
                            ReportForm(report: report, isSubmitting: isSubmitting) { answers, note in submit(report, answers, note) }
                            ErrorText(error: error)
                        }
                    } else {
                        Text("This task's report can't be found. Edit the task to choose one.").foregroundStyle(.secondary)
                    }
                }
                .padding(20)
            }
        }
        .reloading(on: ReportRepository.definitionTables + ReportRepository.submissionTables, db: model.db, id: task.id) {
            if let id = reportId(fromConfig: task.verificationConfig) {
                report = try? await model.reportRepository.report(id: id)
            }
            today = try? await model.reportRepository.todaysTaskSubmission(taskId: task.id)
            loaded = true
        }
    }

    private func submit(_ report: Report, _ answers: [String: String], _ note: String) {
        isSubmitting = true
        Task {
            do {
                let userId = try model.requireUserId()
                let submissionId = try await model.reportRepository.submit(report, answers: answers, note: note, taskId: task.id, routineStepId: nil, userId: userId)
                let proof = JSONValue.object(["method": .string("report"), "submission_id": .string(submissionId)]).jsonString
                try await model.complete(task, verificationData: proof)
                model.navigation.taskPath.removeAll()
            } catch {
                self.error = error.localizedDescription
            }
            isSubmitting = false
        }
    }
}

/// A permanent shopping list: checked items leave the list (with undo).
struct ShoppingListScreen: View {
    let task: BrainTask
    @Environment(AppModel.self) private var model
    @State private var items: [ShoppingItem] = []
    @State private var draft = ""
    @State private var lastChecked: ShoppingItem?
    @State private var error: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                TextField("Add item", text: $draft).onSubmit(add)
                Button("Add", action: add).disabled(draft.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            ErrorText(error: error)
            if items.isEmpty {
                VStack(spacing: 4) {
                    Text("Nothing to buy").font(.headline)
                    Text("Arrival reminders for this list come from your phone.").font(.caption).foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 24)
            } else {
                Text("\(plural(items.count, "item")) to buy").font(.caption).foregroundStyle(.secondary)
                List(items) { item in
                    HStack {
                        Button {
                            act { try await model.shoppingRepository.completeItem(id: item.id) }
                            lastChecked = item
                        } label: { Image(systemName: "circle") }
                            .buttonStyle(.borderless)
                            .help("Check off")
                        Text(item.title)
                        Spacer()
                        Button {
                            act { try await model.shoppingRepository.deleteItem(id: item.id) }
                        } label: { Image(systemName: "trash") }
                            .buttonStyle(.borderless)
                            .help("Delete item")
                    }
                }
            }
            if let lastChecked {
                HStack {
                    Text("Checked off “\(lastChecked.title)”").font(.callout)
                    Spacer()
                    Button("Undo") {
                        act { try await model.shoppingRepository.uncompleteItem(id: lastChecked.id) }
                        self.lastChecked = nil
                    }
                }
                .padding(8)
                .background(Palette.surfaceContainerHigh, in: RoundedRectangle(cornerRadius: 8))
            }
        }
        .padding(20)
        .reloading(on: ShoppingRepository.tables, db: model.db, id: task.id) {
            items = (try? await model.shoppingRepository.openItems(taskId: task.id)) ?? []
        }
    }

    private func add() {
        let title = draft.trimmingCharacters(in: .whitespaces)
        guard !title.isEmpty else { return }
        draft = ""
        act { try await model.shoppingRepository.addItem(taskId: task.id, userId: try model.requireUserId(), title: title) }
    }

    private func act(_ work: @escaping @MainActor () async throws -> Void) {
        Task {
            do {
                try await work()
                error = nil
            } catch {
                self.error = error.localizedDescription
            }
        }
    }
}

/// GPS tasks are verified on the phone. The Mac still enforces them as
/// blocking conditions; it just can't complete them.
struct LocationTaskScreen: View {
    let task: BrainTask

    var body: some View {
        VStack(spacing: 12) {
            if task.completedToday {
                CompletedToday()
            } else {
                Image(systemName: "location.circle").font(.system(size: 44)).foregroundStyle(Palette.primary)
                Text("Complete this on your phone").font(.title3)
                if let config = parseGpsConfig(task.verificationConfig) {
                    Text(description(config)).foregroundStyle(.secondary).multilineTextAlignment(.center)
                    Link(
                        "Show on a map",
                        destination: URL(string: "https://www.openstreetmap.org/?mlat=\(config.latitude)&mlon=\(config.longitude)#map=17/\(config.latitude)/\(config.longitude)")!
                    )
                }
                Text("Location tasks are verified by the Brainfence app on your phone. It still counts here: if this task is a blocking condition, it blocks until your phone verifies it.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 420)
            }
            CompletionHistory(taskId: task.id)
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func description(_ config: GpsConfig) -> String {
        let radius = "\(Int(config.radiusMeters)) m"
        switch config.mode {
        case "leave": return "Completes when you leave the area (\(radius))."
        default:
            return config.minDurationMinutes > 0
                ? "Completes after \(config.minDurationMinutes) min within \(radius) of the location."
                : "Completes when you arrive (within \(radius))."
        }
    }
}
