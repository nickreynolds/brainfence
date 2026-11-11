import AppKit
import BrainfenceCore
import SwiftUI

/// Runs a routine or workout. Routines finish themselves once every step is
/// done; workouts have a Finish button. Progress is saved as you go, so
/// leaving and coming back resumes. Port of Android's RoutineTaskScreen.
struct RoutineTaskScreen: View {
    let task: BrainTask
    @Environment(AppModel.self) private var model
    @State private var session: RoutineSession?
    @State private var auto = AutoProgress()
    @State private var reports: [Report] = []
    @State private var submissions: [String: ReportSubmission] = [:]
    @State private var submitting: Set<String> = []
    @State private var isCompleting = false
    @State private var showingAddStep = false
    @State private var autoRunner: Task<Void, Never>?
    @State private var error: String?

    private var progressKey: String { "routineProgress:\(task.id)" }
    private var isRoutine: Bool { task.taskType == "routine" }
    private var isAutoRoutine: Bool { JSONValue.parse(task.verificationConfig)?["execution_mode"]?.stringValue == "automatic" }

    var body: some View {
        content
            .reloading(on: RoutineRepository.tables, db: model.db, id: task.id) { await loadSteps() }
            .reloading(on: ReportRepository.definitionTables + ReportRepository.submissionTables, db: model.db, id: task.id) {
                reports = (try? await model.reportRepository.reports()) ?? []
                submissions = (try? await model.reportRepository.todaysRoutineSubmissions(taskId: task.id)) ?? [:]
                session?.markReportSteps(submitted: Set(submissions.keys))
            }
            .task(id: session?.anyTimerRunning ?? false) {
                // Timed steps tick once a second.
                while session?.anyTimerRunning == true && !Task.isCancelled {
                    try? await Task.sleep(for: .seconds(1))
                    session?.tickTimers()
                }
            }
            .onChange(of: session) { _, value in
                guard let value, !isCompleting else { return }
                saveProgress(value)
                if isRoutine && value.allStepsCompleted && !task.isDone { finish() }
            }
            .onChange(of: auto.phase) { _, phase in
                if isAutoRoutine && phase == .completed && !task.isDone { finish() }
            }
            .onDisappear {
                // Stop a running automatic routine; it resumes from where it was.
                autoRunner?.cancel()
                if auto.phase != .idle && auto.phase != .completed { auto.phase = .paused }
            }
            .sheet(isPresented: $showingAddStep) {
                AddStepSheet(isWorkout: !isRoutine) { title, type, sets, seconds in addStep(title, type, sets, seconds) }
            }
    }

    @ViewBuilder
    private var content: some View {
        if task.isDone {
            CompletedToday()
        } else if session == nil {
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if isBeforeStart(task) {
            NotYetAvailable(task: task, now: Date()).padding(20)
        } else if isAutoRoutine {
            AutoRoutineView(
                task: task, session: sessionBinding, auto: auto, isCompleting: isCompleting,
                onStart: startAuto, onStop: stopAuto, onConfirmSetWeight: { auto.phase = .countdown }
            )
        } else {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    AlternativesNote(task: task)
                    ForEach(items, id: \.id) { item in
                        switch item {
                        case .step(let step):
                            if let state = session?.state(step.id) {
                                StepCard(
                                    state: state,
                                    session: sessionBinding,
                                    report: report(for: step),
                                    submission: submissions[step.id],
                                    isSubmitting: submitting.contains(step.id),
                                    onRemove: { removeStep(step.id) },
                                    onSubmitReport: { report, answers, note in submitReport(step.id, report, answers, note) }
                                )
                            }
                        case .superset(let groupId, let steps):
                            SupersetCard(groupId: groupId, steps: steps, session: sessionBinding)
                        }
                    }
                    Button { showingAddStep = true } label: {
                        Label(isRoutine ? "Add Step" : "Add Exercise", systemImage: "plus").frame(maxWidth: .infinity)
                    }
                    if !isRoutine {
                        Button(isCompleting ? "Saving…" : "Finish Workout") { finish() }
                            .keyboardShortcut(.defaultAction)
                            .disabled(isCompleting)
                            .frame(maxWidth: .infinity)
                    }
                    ErrorText(error: error)
                }
                .padding(20)
            }
        }
    }

    private func report(for step: RoutineStep) -> Report? {
        guard step.stepType == "report", let id = reportId(fromConfig: step.config) else { return nil }
        return reports.first(where: { $0.id == id })
    }

    private var sessionBinding: Binding<RoutineSession> {
        Binding(get: { session ?? RoutineSession(steps: [], last: []) }, set: { session = $0 })
    }

    private enum Item {
        case step(RoutineStep)
        case superset(String, [RoutineStep])

        var id: String {
            switch self {
            case .step(let s): s.id
            case .superset(let g, _): "superset:\(g)"
            }
        }
    }

    /// Individual steps, plus superset groups shown once at their first step.
    private var items: [Item] {
        guard let steps = session?.steps else { return [] }
        var out: [Item] = []
        var seen = Set<String>()
        for step in steps {
            if let group = step.supersetGroup {
                if seen.insert(group).inserted {
                    out.append(.superset(group, steps.filter { $0.supersetGroup == group }))
                }
            } else {
                out.append(.step(step))
            }
        }
        return out
    }

    // MARK: Loading and saving

    private func loadSteps() async {
        guard let steps = try? await model.routineRepository.steps(taskId: task.id) else { return }
        if session != nil {
            session?.sync(steps: steps)
            return
        }
        guard !steps.isEmpty else { return }
        // Restore an in-progress session, else pre-fill from the last completion.
        let saved = JSONValue.parse(UserDefaults.standard.string(forKey: progressKey))
        if let restored = RoutineSession(steps: steps, saved: saved) {
            session = restored
            if let progress = RoutineSession.savedAutoProgress(saved) { auto = progress }
        } else {
            UserDefaults.standard.removeObject(forKey: progressKey)
            let last = (try? await model.routineRepository.lastStepCompletions(taskId: task.id)) ?? []
            session = RoutineSession(steps: steps, last: last)
        }
        session?.markReportSteps(submitted: Set(submissions.keys))
    }

    private func saveProgress(_ session: RoutineSession) {
        UserDefaults.standard.set(session.serialized(auto: auto).jsonString, forKey: progressKey)
    }

    private func finish() {
        guard !isCompleting, let session else { return }
        isCompleting = true
        autoRunner?.cancel()
        let data = session.stepData(submissionIds: submissions.mapValues(\.id))
        Task {
            do {
                try await model.routineRepository.completeRoutine(taskId: task.id, userId: model.requireUserId(), stepData: data)
                UserDefaults.standard.removeObject(forKey: progressKey)
                model.engine.tick()
                model.debugLog.log("task", "Completed routine '\(task.title)'")
                model.navigation.taskPath.removeAll()
            } catch {
                self.error = error.localizedDescription
            }
            isCompleting = false
        }
    }

    private func submitReport(_ stepId: String, _ report: Report, _ answers: [String: String], _ note: String) {
        guard !submitting.contains(stepId) else { return }
        submitting.insert(stepId)
        Task {
            do {
                try await model.reportRepository.submit(report, answers: answers, note: note, taskId: task.id, routineStepId: stepId, userId: model.requireUserId())
            } catch {
                self.error = error.localizedDescription
            }
            submitting.remove(stepId)
        }
    }

    private func addStep(_ title: String, _ type: String, _ sets: Int, _ seconds: Int) {
        let config: JSONValue = switch type {
        case "weight_reps", "just_reps": .object(["default_sets": .int(sets)])
        case "timed": .object(["duration_seconds": .int(seconds)])
        default: .object([:])
        }
        let order = (session?.steps.map(\.stepOrder).max() ?? -1) + 1
        Task {
            do {
                try await model.routineRepository.addStep(
                    taskId: task.id, userId: model.requireUserId(), title: title, stepType: type, config: config.jsonString, stepOrder: order
                )
            } catch {
                self.error = error.localizedDescription
            }
        }
    }

    private func removeStep(_ id: String) {
        Task {
            do { try await model.routineRepository.deleteStep(id: id) } catch { self.error = error.localizedDescription }
        }
    }

    // MARK: Automatic routine

    private func startAuto() {
        guard autoRunner == nil || autoRunner?.isCancelled == true, let session, !session.steps.isEmpty else { return }
        guard let start = session.autoStartPosition else {
            auto = AutoProgress(phase: .completed)
            return
        }
        let steps = session.steps
        let countdown = Int(JSONValue.parse(task.verificationConfig)?.first("countdown_seconds")?.doubleValue ?? 3)
        autoRunner = Task { @MainActor in
            var first = true
            // A set whose weight is asked right before it (weight_prompt each_set).
            func promptsBefore(_ exIndex: Int, _ hang: Int) -> Bool {
                exIndex < steps.count && steps[exIndex].timedSets.promptsBefore(hang)
            }
            for ex in start.exercise..<steps.count {
                let step = steps[ex]
                let firstRep = ex == start.exercise ? start.rep : 0
                let timed = step.timedSets
                let hangs = timed.hangCount
                for rep in firstRep..<max(firstRep, hangs) {
                    if promptsBefore(ex, rep) {
                        // Wait until the weight is confirmed (Start set moves the phase on).
                        auto = AutoProgress(phase: .weight, exerciseIndex: ex, rep: rep)
                        Self.tick()
                        while auto.phase == .weight {
                            do { try await Task.sleep(for: .milliseconds(200)) } catch { return }
                        }
                        first = true
                    }
                    if first {
                        first = false
                        for s in stride(from: countdown, through: 1, by: -1) {
                            auto = AutoProgress(phase: .countdown, exerciseIndex: ex, rep: rep, secondsRemaining: s)
                            if s <= 2 { Self.tick() }
                            guard await Self.sleepSecond() else { return }
                        }
                        Self.beep()
                    }
                    for s in stride(from: timed.workSeconds, through: 1, by: -1) {
                        auto = AutoProgress(phase: .work, exerciseIndex: ex, rep: rep, secondsRemaining: s)
                        guard await Self.sleepSecond() else { return }
                    }
                    self.session?.completeAutoRep(step.id, rep: rep, workSeconds: timed.workSeconds)
                    let lastRep = rep == hangs - 1
                    let lastExercise = ex == steps.count - 1
                    if !(lastRep && lastExercise) {
                        let nextEx = lastRep ? ex + 1 : ex
                        let nextRep = lastRep ? 0 : rep + 1
                        let rest = timed.restAfter(rep, isLastStep: lastExercise)
                        // Before a set that asks for its weight, rest without the
                        // countdown: it runs once the weight is confirmed.
                        let promptNext = promptsBefore(nextEx, nextRep)
                        for s in stride(from: rest, through: 1, by: -1) {
                            let inCountdown = !promptNext && s <= countdown
                            auto = AutoProgress(phase: inCountdown ? .countdown : .rest, exerciseIndex: nextEx, rep: nextRep, secondsRemaining: s)
                            if inCountdown && s <= 2 { Self.tick() }
                            guard await Self.sleepSecond() else { return }
                        }
                        if !promptNext { Self.beep() }
                    }
                }
            }
            auto = AutoProgress(phase: .completed)
            Self.beep()
        }
    }

    private func stopAuto() {
        autoRunner?.cancel()
        autoRunner = nil
        auto.phase = .paused
    }

    /// Sleeps a second; false if the runner was cancelled.
    private static func sleepSecond() async -> Bool {
        do {
            try await Task.sleep(for: .seconds(1))
            return true
        } catch {
            return false
        }
    }

    private static func tick() { NSSound(named: "Tink")?.play() }
    private static func beep() { NSSound(named: "Glass")?.play() }
}

// MARK: - Steps

private struct StepCard: View {
    let state: StepState
    @Binding var session: RoutineSession
    let report: Report?
    let submission: ReportSubmission?
    let isSubmitting: Bool
    let onRemove: () -> Void
    let onSubmitReport: (Report, [String: String], String) -> Void

    var body: some View {
        let step = state.step
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Image(systemName: state.isDone ? "checkmark.circle.fill" : "circle")
                    .foregroundStyle(state.isDone ? Palette.primary : .secondary)
                Text(step.title).font(.headline)
                Spacer()
                Button(action: onRemove) { Image(systemName: "xmark") }
                    .buttonStyle(.borderless)
                    .help("Remove step")
            }
            if let notes = step.description, !notes.isEmpty {
                Text(notes).font(.caption).foregroundStyle(.secondary).lineLimit(3)
            }
            if let url = step.imageUrl { StepImage(url: url) }
            switch step.stepType {
            case "checkbox":
                Button(state.sets.first?.completed == true ? "Done" : "Mark Complete") { session.toggleCheckbox(step.id) }
            case "weight_reps", "just_reps":
                SetsContent(state: state, showWeight: step.stepType == "weight_reps", session: $session)
            case "timed":
                if let entry = state.sets.first {
                    TimedContent(entry: entry, target: step.durationSeconds,
                                 onStart: { session.startTimer(step.id, index: 0) },
                                 onStop: { session.stopTimer(step.id, index: 0) })
                }
            case "timed_sets":
                Text("\(state.sets.filter(\.completed).count) / \(state.sets.count) reps done").font(.caption).foregroundStyle(.secondary)
            case "report":
                if let submission {
                    ReportSubmissionSummary(submission: submission)
                } else if let report {
                    ReportForm(report: report, isSubmitting: isSubmitting) { answers, note in onSubmitReport(report, answers, note) }
                } else {
                    Text("This step's report can't be found. Edit the routine to choose one.").font(.caption).foregroundStyle(.secondary)
                }
            default:
                EmptyView()
            }
        }
        .padding(12)
        .background(state.isDone ? Palette.primaryContainer.opacity(0.3) : Palette.surfaceContainerLow, in: RoundedRectangle(cornerRadius: 10))
    }
}

private struct SetsContent: View {
    let state: StepState
    let showWeight: Bool
    @Binding var session: RoutineSession

    var body: some View {
        let id = state.step.id
        let allDone = state.isDone
        VStack(alignment: .leading, spacing: 6) {
            ForEach(Array(state.sets.enumerated()), id: \.offset) { i, entry in
                if entry.completed && (allDone || i != state.activeSetIndex) {
                    Button { session.goToSet(id, index: i) } label: {
                        Label(setSummary(i, entry), systemImage: "checkmark").font(.caption)
                    }
                    .buttonStyle(.plain)
                }
            }
            if !allDone, state.sets.indices.contains(state.activeSetIndex) {
                let i = state.activeSetIndex
                let active = state.sets[i]
                if state.sets.count > 1 {
                    Text("Set \(i + 1) of \(state.sets.count)").font(.subheadline.weight(.semibold)).foregroundStyle(Palette.primary)
                }
                if showWeight {
                    NumberStepper(label: "Weight (lbs)", value: active.weight ?? 0, step: 2.5, max: 500) { w in
                        var e = active
                        e.weight = w
                        session.updateSet(id, index: i, e)
                    }
                }
                NumberStepper(label: "Reps", value: Double(active.reps ?? 0), step: 1, max: 100) { r in
                    var e = active
                    e.reps = Int(r)
                    session.updateSet(id, index: i, e)
                }
                if active.completed {
                    Button("Undo Complete") {
                        var e = active
                        e.completed = false
                        session.updateSet(id, index: i, e)
                    }
                } else {
                    Button("Complete Set") { session.completeCurrentSet(id) }
                        .keyboardShortcut(.defaultAction)
                }
            }
            HStack {
                Button("Remove Set") { session.removeSet(id) }.disabled(state.sets.count <= 1)
                Button("Add Set") { session.addSet(id) }
            }
            .buttonStyle(.borderless)
            .font(.caption)
        }
    }

    private func setSummary(_ i: Int, _ e: SetEntry) -> String {
        let weight = showWeight ? e.weight.map { "\(formatWeight($0)) lbs × " } ?? "" : ""
        return "Set \(i + 1): \(weight)\(e.reps ?? 0) reps"
    }
}

func formatWeight(_ w: Double) -> String {
    w == w.rounded() ? String(Int(w)) : String(w)
}

private struct NumberStepper: View {
    let label: String
    let value: Double
    let step: Double
    let max: Double
    let onChange: (Double) -> Void

    var body: some View {
        HStack {
            Text(label).frame(width: 110, alignment: .leading)
            Button { onChange(clamp(value - step)) } label: { Image(systemName: "minus") }.disabled(value <= 0)
            TextField(label, value: Binding(get: { value }, set: { onChange(clamp($0)) }), format: .number)
                .frame(width: 70)
                .multilineTextAlignment(.center)
            Button { onChange(clamp(value + step)) } label: { Image(systemName: "plus") }.disabled(value >= max)
        }
    }

    private func clamp(_ v: Double) -> Double { Swift.min(max, Swift.max(0, (v / step).rounded() * step)) }
}

private struct TimedContent: View {
    let entry: SetEntry
    let target: Int
    let onStart: () -> Void
    let onStop: () -> Void

    var body: some View {
        VStack(spacing: 6) {
            Text(formatClock(entry.completed ? 0 : max(0, target - entry.timerElapsed)))
                .font(.system(size: 28, weight: .medium, design: .rounded).monospacedDigit())
                .foregroundStyle(entry.completed ? Palette.primary : entry.timerRunning ? Palette.tertiary : .primary)
            Text(entry.completed ? "Completed" : entry.timerRunning ? "Running" : entry.timerElapsed > 0 ? "Stopped" : "\(formatClock(target)) required")
                .font(.caption).foregroundStyle(.secondary)
            if !entry.completed {
                if entry.timerRunning {
                    Button("Stop", action: onStop)
                } else {
                    Button(entry.timerElapsed > 0 ? "Restart" : "Start", action: onStart)
                }
            }
        }
        .frame(maxWidth: .infinity)
    }
}

private struct SupersetCard: View {
    let groupId: String
    let steps: [RoutineStep]
    @Binding var session: RoutineSession

    var body: some View {
        let round = session.rounds[groupId]
        let current = round?.currentRound ?? 1
        let total = round?.totalRounds ?? 1
        let setIndex = current - 1
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("Superset").font(.headline).foregroundStyle(Palette.tertiary)
                Spacer()
                Text("Round \(current) of \(total)").font(.caption.weight(.semibold)).foregroundStyle(Palette.tertiary)
            }
            ForEach(steps) { step in
                if let entry = session.state(step.id)?.sets[safe: setIndex] {
                    VStack(alignment: .leading, spacing: 6) {
                        Label(step.title, systemImage: entry.completed ? "checkmark.circle.fill" : "circle").font(.subheadline.weight(.semibold))
                        switch step.stepType {
                        case "checkbox":
                            Button(entry.completed ? "Done" : "Mark Complete") { session.toggleCheckbox(step.id) }
                        case "weight_reps", "just_reps":
                            if step.stepType == "weight_reps" {
                                NumberStepper(label: "Weight (lbs)", value: entry.weight ?? 0, step: 2.5, max: 500) { w in
                                    var e = entry
                                    e.weight = w
                                    session.updateSet(step.id, index: setIndex, e)
                                }
                            }
                            NumberStepper(label: "Reps", value: Double(entry.reps ?? 0), step: 1, max: 100) { r in
                                var e = entry
                                e.reps = Int(r)
                                session.updateSet(step.id, index: setIndex, e)
                            }
                            Button(entry.completed ? "Done" : "Complete") {
                                var e = entry
                                e.completed.toggle()
                                session.updateSet(step.id, index: setIndex, e)
                            }
                        case "timed":
                            TimedContent(entry: entry, target: step.durationSeconds,
                                         onStart: { session.startTimer(step.id, index: setIndex) },
                                         onStop: { session.stopTimer(step.id, index: setIndex) })
                        default:
                            EmptyView()
                        }
                    }
                    .padding(8)
                    .background(Palette.surface, in: RoundedRectangle(cornerRadius: 8))
                }
            }
            HStack {
                ForEach(1...max(1, total), id: \.self) { r in
                    let complete = steps.allSatisfy { session.state($0.id)?.sets[safe: r - 1]?.completed == true }
                    Button { session.goToRound(groupId, round: r) } label: {
                        Label("\(r)", systemImage: complete ? "checkmark" : "circle.dashed")
                    }
                    .buttonStyle(.bordered)
                    .tint(r == current ? Palette.tertiary : nil)
                }
            }
            if current < total {
                Button("Next Round") { session.advanceRound(groupId) }
            }
        }
        .padding(12)
        .background(Palette.tertiaryContainer.opacity(0.4), in: RoundedRectangle(cornerRadius: 10))
    }
}

extension Array {
    subscript(safe index: Int) -> Element? { indices.contains(index) ? self[index] : nil }
}

private struct AutoRoutineView: View {
    let task: BrainTask
    @Binding var session: RoutineSession
    let auto: AutoProgress
    let isCompleting: Bool
    let onStart: () -> Void
    let onStop: () -> Void
    let onConfirmSetWeight: () -> Void
    @State private var confirming = false

    private var steps: [RoutineStep] { session.steps }
    /// Steps whose weight is confirmed once, before the routine starts.
    private var startWeighted: [RoutineStep] {
        steps.filter { $0.timedSets.weight != nil && $0.timedSets.weightPrompt == "start" }
    }

    var body: some View {
        let current = steps[safe: auto.exerciseIndex]
        let config = current?.timedSets
        let totalWork = steps.reduce(0) { $0 + $1.timedSets.hangCount }
        let doneWork = steps.prefix(auto.exerciseIndex).reduce(0) { $0 + $1.timedSets.hangCount } + auto.rep
        let setIndex = config?.setOf(auto.rep) ?? 0
        let hangInSide = config.map { auto.rep % $0.reps + 1 } ?? auto.rep + 1
        let side: String? = config.flatMap { $0.sides == 2 ? ($0.sideOf(auto.rep) == 0 ? "Left hand" : "Right hand") : nil }
        let color = phaseColor
        VStack(spacing: 12) {
            switch auto.phase {
            case .idle where confirming:
                Text("Confirm weights").font(.title2.weight(.semibold))
                Text("Added weight for each hang (negative = assisted). Pre-filled from last time.")
                    .font(.callout).foregroundStyle(.secondary)
                Form {
                    ForEach(startWeighted) { s in
                        LabeledContent(s.title) {
                            WeightField(kind: s.timedSets.weight ?? .added, weight: weightBinding(s.id, setIndex: 0)).frame(width: 180)
                        }
                    }
                }
                HStack {
                    Button("Back") { confirming = false }
                    Button("Confirm and start") { confirming = false; onStart() }.keyboardShortcut(.defaultAction)
                }
            case .idle:
                ScrollView {
                    VStack(alignment: .leading, spacing: 12) {
                        AlternativesNote(task: task)
                        ForEach(Array(steps.enumerated()), id: \.element.id) { i, s in
                            VStack(alignment: .leading, spacing: 6) {
                                Text("\(i + 1). \(s.title)").font(.headline)
                                Text(describe(s.timedSets)).font(.caption).foregroundStyle(.secondary)
                                if let notes = s.description, !notes.isEmpty {
                                    Text(notes).font(.caption).foregroundStyle(.secondary)
                                }
                                if let url = s.imageUrl { StepImage(url: url, maxHeight: 140) }
                                if let kind = s.timedSets.weight {
                                    Text(s.timedSets.weightPrompt == "each_set"
                                         ? "Weight asked before each set"
                                         : "Last time: \(TimedSetsConfig.format(kind, session.timedSetWeight(s.id, setIndex: 0)))")
                                        .font(.caption).foregroundStyle(.secondary)
                                }
                            }
                            .padding(12)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(.quaternary.opacity(0.4), in: RoundedRectangle(cornerRadius: 10))
                        }
                    }
                }
                Button("Start Routine") { if startWeighted.isEmpty { onStart() } else { confirming = true } }
                    .keyboardShortcut(.defaultAction)
            case .completed:
                ProgressView()
                if isCompleting { Text("Saving…").foregroundStyle(.secondary) }
            case .weight:
                Text("Exercise \(auto.exerciseIndex + 1) of \(steps.count)").foregroundStyle(.secondary)
                Text(current?.title ?? "").font(.title.weight(.semibold))
                Text("Set \(setIndex + 1) of \(config?.sets ?? 1)" + (config?.sides == 2 ? " · left, then right, same weight" : ""))
                    .foregroundStyle(.secondary)
                if let url = current?.imageUrl { StepImage(url: url, maxHeight: 200).frame(maxWidth: 420) }
                if let current, let kind = config?.weight {
                    WeightField(kind: kind, weight: weightBinding(current.id, setIndex: setIndex)).frame(maxWidth: 240)
                }
                Button("Start set", action: onConfirmSetWeight).keyboardShortcut(.defaultAction)
                Button("Pause", action: onStop)
            default:
                Text("Exercise \(auto.exerciseIndex + 1) of \(steps.count)").foregroundStyle(.secondary)
                if (auto.phase == .rest || auto.phase == .countdown) && config?.startsSet(auto.rep) == true {
                    Text("UP NEXT").font(.caption.weight(.bold)).foregroundStyle(color)
                }
                Text(current?.title ?? "").font(.title.weight(.semibold))
                Text(((config?.sets ?? 1) > 1 ? "Set \(setIndex + 1) of \(config?.sets ?? 1) · " : "")
                     + (side.map { "\($0) · " } ?? "")
                     + "Rep \(hangInSide) of \(config?.reps ?? 1)")
                    .foregroundStyle(.secondary)
                if let current, let kind = config?.weight {
                    Text(TimedSetsConfig.format(kind, session.timedSetWeight(current.id, setIndex: setIndex))).foregroundStyle(.secondary)
                }
                if let url = current?.imageUrl { StepImage(url: url, maxHeight: 200).frame(maxWidth: 420) }
                if auto.phase == .paused {
                    Text("PAUSED").font(.title2).tracking(2)
                } else {
                    Text("\(auto.secondsRemaining)").font(.system(size: 96, weight: .bold, design: .rounded).monospacedDigit()).foregroundStyle(color)
                    Text(auto.phase == .work ? "WORK" : auto.phase == .rest ? "REST" : "GET READY").font(.headline).foregroundStyle(color)
                }
                ProgressView(value: Double(doneWork), total: Double(max(1, totalWork))).frame(maxWidth: 420)
                if auto.phase == .paused {
                    Button("Resume Routine", action: onStart).keyboardShortcut(.defaultAction)
                } else {
                    Button("Pause", action: onStop)
                }
            }
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func weightBinding(_ stepId: String, setIndex: Int) -> Binding<Double?> {
        Binding(
            get: { session.timedSetWeight(stepId, setIndex: setIndex) },
            set: { session.setTimedSetWeight(stepId, setIndex: setIndex, weight: $0) }
        )
    }

    private func describe(_ c: TimedSetsConfig) -> String {
        let hangs = "\(c.reps)× \(c.workSeconds)s on / \(c.restSeconds)s off" + (c.sides == 2 ? " per hand (left, then right)" : "")
        guard c.sets > 1 else { return hangs }
        return "\(c.sets) sets of \(hangs) · \(c.setRestSeconds / 60):\(String(format: "%02d", c.setRestSeconds % 60)) between sets"
    }

    private var phaseColor: Color {
        switch auto.phase {
        case .countdown: Palette.tertiary
        case .work: Palette.primary
        case .rest: Palette.secondary
        default: .primary
        }
    }
}

/// Added weight (negative = assisted) or the load lifted, in lb. Empty = bodyweight / none.
private struct WeightField: View {
    let kind: TimedSetsConfig.WeightKind
    @Binding var weight: Double?

    var body: some View {
        TextField(
            kind == .added ? "Added weight (lb, − = assisted)" : "Weight (lb)",
            value: Binding(get: { weight }, set: { v in weight = kind == .load ? v.map { max(0, $0) } : v }),
            format: .number,
            prompt: Text(kind == .added ? "Bodyweight" : "0")
        )
        .textFieldStyle(.roundedBorder)
    }
}

/// A step's picture (e.g. which holds to use), scaled to fit. URLSession's
/// shared cache keeps it for offline use after the first load.
struct StepImage: View {
    let url: String
    var maxHeight: CGFloat = 220

    var body: some View {
        if url.hasPrefix("https://"), let u = URL(string: url) {
            AsyncImage(url: u) { phase in
                if let image = phase.image {
                    image.resizable().scaledToFit()
                } else if phase.error == nil {
                    ProgressView()
                }
            }
            .frame(maxWidth: .infinity, maxHeight: maxHeight)
            .clipShape(RoundedRectangle(cornerRadius: 10))
        }
    }
}

/// "Or be at Climbing gym for 30 min": the task's other ways to complete
/// (shared/CONTRACTS.md). The Mac doesn't use location, so the phone completes it.
struct AlternativesNote: View {
    let task: BrainTask

    var body: some View {
        let alternatives = parseGpsAlternatives(task.completionAlternatives)
        if !alternatives.isEmpty {
            Label(
                "Or \(alternatives.map(\.phrase).joined(separator: ", or ")) (your phone completes this).",
                systemImage: "mappin.and.ellipse"
            )
            .font(.callout)
            .padding(10)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Palette.secondary.opacity(0.12), in: RoundedRectangle(cornerRadius: 10))
        }
    }
}

private struct AddStepSheet: View {
    let isWorkout: Bool
    let onAdd: (String, String, Int, Int) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var title = ""
    @State private var type = ""
    @State private var sets = 3
    @State private var seconds = 60

    private var types: [(String, String)] {
        isWorkout
            ? [("weight_reps", "Weight & Reps"), ("just_reps", "Reps Only"), ("timed", "Timed"), ("checkbox", "Checkbox")]
            : [("checkbox", "Checkbox"), ("timed", "Timed"), ("weight_reps", "Weight & Reps"), ("just_reps", "Reps Only")]
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(isWorkout ? "Add Exercise" : "Add Step").font(.headline)
            Form {
                TextField(isWorkout ? "Exercise name" : "Step name", text: $title)
                Picker("Type", selection: $type) {
                    ForEach(types, id: \.0) { t in Text(t.1).tag(t.0) }
                }
                if type == "weight_reps" || type == "just_reps" {
                    Stepper("Default sets: \(sets)", value: $sets, in: 1...20)
                }
                if type == "timed" {
                    Stepper("Duration: \(seconds) s", value: $seconds, in: 5...3600, step: 5)
                }
            }
            HStack {
                Spacer()
                Button("Cancel") { dismiss() }.keyboardShortcut(.cancelAction)
                Button("Add") {
                    onAdd(title.trimmingCharacters(in: .whitespaces), type, sets, seconds)
                    dismiss()
                }
                .keyboardShortcut(.defaultAction)
                .disabled(title.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
        .padding(20)
        .frame(width: 380)
        .onAppear { if type.isEmpty { type = isWorkout ? "weight_reps" : "checkbox" } }
    }
}
