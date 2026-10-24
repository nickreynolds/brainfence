import BrainfenceCore
import SwiftUI

/// Create or edit a task: a three-step wizard (two for shopping lists), like
/// Android's TaskEditorScreen. The state, validation and saved values live in
/// BrainfenceCore's TaskEditorState.
struct TaskEditorView: View {
    let taskId: String?

    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var s: TaskEditorState?
    @State private var step = 0
    @State private var reports: [Report] = []
    @State private var error: String?
    @State private var isSaving = false

    var body: some View {
        VStack(spacing: 0) {
            if let s {
                let state = Binding(get: { s }, set: { self.s = $0; error = nil })
                HStack {
                    Text("\(taskId == nil ? "New Task" : "Edit Task") — Step \(step + 1) of \(s.totalSteps)").font(.headline)
                    Spacer()
                }
                .padding()
                Divider()
                Form {
                    switch step {
                    case 0: BasicsStep(s: state)
                    case 1: ConfigStep(s: state, reports: reports)
                    default: ScheduleStep(s: state)
                    }
                }
                .formStyle(.grouped)
                Divider()
                HStack {
                    ErrorText(error: error)
                    Spacer()
                    Button(step > 0 ? "Back" : "Cancel") {
                        if step > 0 { step -= 1 } else { dismiss() }
                    }
                    .keyboardShortcut(.cancelAction)
                    if step == s.totalSteps - 1 {
                        Button(isSaving ? "Saving…" : "Save") { save(s) }
                            .keyboardShortcut(.defaultAction)
                            .disabled(isSaving)
                    } else {
                        Button("Next") {
                            if let message = s.validateStep(step) { error = message } else { step += 1 }
                        }
                        .keyboardShortcut(.defaultAction)
                    }
                }
                .padding()
            } else if let error {
                Text(error).foregroundStyle(Palette.error).padding()
            } else {
                ProgressView().padding()
            }
        }
        .frame(width: 600, height: 620)
        .task { await load() }
        .reloading(on: ReportRepository.definitionTables, db: model.db) {
            reports = (try? await model.reportRepository.reports()) ?? []
        }
    }

    private func load() async {
        guard s == nil else { return }
        guard let taskId else {
            s = TaskEditorState()
            return
        }
        do {
            guard let row = try await model.taskRepository.taskRow(id: taskId) else {
                error = "Task not found"
                return
            }
            let steps = try await model.routineRepository.steps(taskId: taskId)
            s = TaskEditorState(editing: taskId, row: row, steps: steps)
        } catch {
            self.error = error.localizedDescription
        }
    }

    private func save(_ s: TaskEditorState) {
        if let message = s.validateForSave(reportIds: Set(reports.map(\.id))) {
            error = message
            return
        }
        isSaving = true
        Task {
            do {
                _ = try await model.taskRepository.save(s.write, taskId: s.editingTaskId, userId: model.requireUserId())
                model.engine.tick()
                model.debugLog.log("task", "\(s.editingTaskId == nil ? "Created" : "Saved") task '\(s.title)'")
                dismiss()
            } catch {
                self.error = error.localizedDescription
            }
            isSaving = false
        }
    }
}

// MARK: - Steps

private struct BasicsStep: View {
    @Binding var s: TaskEditorState

    var body: some View {
        Section {
            TextField("Task title", text: $s.title)
            TextField("Description (optional)", text: $s.description, axis: .vertical)
                .lineLimit(2...4)
        }
        Section("Task type") {
            Picker("Type", selection: Binding(get: { s.taskType }, set: { s.setTaskType($0) })) {
                ForEach(taskTypes, id: \.value) { t in
                    VStack(alignment: .leading) {
                        Text(t.label)
                        Text(t.detail).font(.caption).foregroundStyle(.secondary)
                    }
                    .tag(t.value)
                }
            }
            .pickerStyle(.radioGroup)
            .labelsHidden()
        }
    }
}

private struct ConfigStep: View {
    @Binding var s: TaskEditorState
    let reports: [Report]

    var body: some View {
        switch s.taskType {
        case "timed":
            Section("Session duration") {
                Stepper("Minutes: \(s.durationSeconds / 60)", value: Binding(
                    get: { s.durationSeconds / 60 },
                    set: { s.durationSeconds = max(1, $0 * 60 + s.durationSeconds % 60) }
                ), in: 0...600)
                Stepper("Seconds: \(s.durationSeconds % 60)", value: Binding(
                    get: { s.durationSeconds % 60 },
                    set: { s.durationSeconds = max(1, (s.durationSeconds / 60) * 60 + $0) }
                ), in: 0...59)
            }
        case "routine", "workout":
            RoutineStepsEditor(s: $s, reports: reports)
        case "journal":
            Section {
                Text("Completing this task opens a screen to write a short journal entry. Entries are saved and viewable under Journal.")
                    .foregroundStyle(.secondary)
            }
        case "shopping":
            Section {
                Text("This list never completes: add items any time and check them off as you buy them. Your phone reminds you when you arrive at the location below with items on the list.")
                    .foregroundStyle(.secondary)
            }
            LocationFields(s: $s, title: "Reminder location")
            Section("Reminder window") {
                Text("Only remind on these days and between these times.").font(.caption).foregroundStyle(.secondary)
                DayPicker(selection: $s.notifyDays)
                TimeField(label: "From", text: $s.notifyStart)
                TimeField(label: "Until", text: $s.notifyEnd)
            }
        case "report":
            Section("Report") {
                ReportPicker(selection: $s.reportId, reports: reports)
                Text("Completing this task asks each metric in the report. Several tasks or routine steps can share one report.")
                    .font(.caption).foregroundStyle(.secondary)
            }
        default:
            SimpleConfig(s: $s)
        }
    }
}

private struct SimpleConfig: View {
    @Binding var s: TaskEditorState
    @State private var showingAppPicker = false

    var body: some View {
        Section("Verification") {
            Picker("Method", selection: $s.verificationType) {
                Text("Manual").tag("manual")
                Text("GPS location").tag("gps")
                Text("Meditation").tag("meditation")
            }
            .pickerStyle(.segmented)
        }
        if s.verificationType == "gps" {
            LocationFields(s: $s, title: "GPS location")
            Section("Completes when") {
                Picker("Mode", selection: $s.gpsMode) {
                    Text("I arrive").tag("enter")
                    Text("I leave").tag("leave")
                }
                .pickerStyle(.segmented)
                if s.gpsMode == "enter" {
                    Stepper("Minimum stay: \(s.gpsMinDurationMinutes) min (0 = on arrival)", value: $s.gpsMinDurationMinutes, in: 0...600)
                }
                Text("Location tasks are verified by your phone. This Mac still enforces them as blocking conditions.")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
        if s.verificationType == "meditation" {
            Section("Meditation") {
                Stepper("Duration: \(s.meditationSeconds / 60) min", value: Binding(
                    get: { s.meditationSeconds / 60 }, set: { s.meditationSeconds = max(60, $0 * 60) }
                ), in: 1...600)
                Stepper("Short sit: \(s.shortSitSeconds / 60) min (0 = off)", value: Binding(
                    get: { s.shortSitSeconds / 60 }, set: { s.shortSitSeconds = max(0, $0 * 60) }
                ), in: 0...600)
            }
            Section("Companion apps on this Mac") {
                Text("Time spent in these apps counts toward the meditation. Companion apps set up on your other devices are kept as they are.")
                    .font(.caption).foregroundStyle(.secondary)
                ForEach(bundleIDs, id: \.self) { id in
                    HStack {
                        if let icon = InstalledApps.icon(forBundleID: id) {
                            Image(nsImage: icon).resizable().frame(width: 18, height: 18)
                        }
                        Text(InstalledApps.displayName(forBundleID: id))
                        Spacer()
                        Button(role: .destructive) {
                            s.companionApps.removeAll { $0["bundle_id"]?.stringValue == id }
                        } label: { Image(systemName: "minus.circle") }
                            .buttonStyle(.borderless)
                    }
                }
                Button("Add Apps…") { showingAppPicker = true }
            }
            .sheet(isPresented: $showingAppPicker) {
                AppPickerView(selected: Set(bundleIDs)) { picked in
                    for id in picked where !bundleIDs.contains(id) {
                        s.companionApps.append(.object([
                            "platform": .string(localPlatform),
                            "bundle_id": .string(id),
                            "name": .string(InstalledApps.displayName(forBundleID: id)),
                        ]))
                    }
                }
            }
        }
    }

    private var bundleIDs: [String] { s.companionApps.compactMap { $0["bundle_id"]?.stringValue } }
}

/// Latitude / longitude / radius. Desktops don't track location, so this is
/// typed in (or copied from a map).
private struct LocationFields: View {
    @Binding var s: TaskEditorState
    let title: String

    var body: some View {
        Section(title) {
            TextField("Latitude", text: $s.latitude)
            TextField("Longitude", text: $s.longitude)
            Stepper("Radius: \(s.radiusMeters) m", value: $s.radiusMeters, in: 10...5000, step: 10)
            Link("Look up on a map", destination: mapURL)
        }
    }

    private var mapURL: URL {
        if let lat = Double(s.latitude), let lng = Double(s.longitude) {
            return URL(string: "https://www.openstreetmap.org/?mlat=\(lat)&mlon=\(lng)#map=17/\(lat)/\(lng)")!
        }
        return URL(string: "https://www.openstreetmap.org/")!
    }
}

struct ReportPicker: View {
    @Binding var selection: String?
    let reports: [Report]

    var body: some View {
        if reports.isEmpty {
            Text("No reports yet. Create one under Reports first.").foregroundStyle(Palette.error)
        } else {
            Picker("Report", selection: $selection) {
                Text("Choose a report…").tag(String?.none)
                ForEach(reports) { r in
                    Text("\(r.name) (\(plural(r.metrics.count, "metric")))").tag(String?.some(r.id))
                }
            }
        }
    }
}

private struct RoutineStepsEditor: View {
    @Binding var s: TaskEditorState
    let reports: [Report]
    @State private var forSuperset: Set<String> = []

    private var isWorkout: Bool { s.taskType == "workout" }
    private var types: [(String, String)] {
        isWorkout
            ? [("weight_reps", "Weight & Reps"), ("just_reps", "Reps Only"), ("timed", "Timed"), ("checkbox", "Checkbox")]
            : [("checkbox", "Checkbox"), ("timed", "Timed"), ("weight_reps", "Weight & Reps"), ("just_reps", "Reps Only"), ("report", "Report")]
    }

    var body: some View {
        Section {
            if s.routineSteps.isEmpty {
                Text(isWorkout ? "No exercises yet" : "No steps yet").foregroundStyle(.secondary)
            }
            ForEach(Array(s.routineSteps.enumerated()), id: \.element.id) { index, step in
                stepEditor(index: index, step: step)
            }
            HStack {
                Button(isWorkout ? "Add Exercise" : "Add Step") {
                    s.routineSteps.append(EditableStep(stepType: isWorkout ? "weight_reps" : "checkbox"))
                }
                Spacer()
                if forSuperset.count >= 2 {
                    Button("Group as Superset") {
                        let group = String(UUID().uuidString.lowercased().prefix(8))
                        for i in s.routineSteps.indices where forSuperset.contains(s.routineSteps[i].id) {
                            s.routineSteps[i].supersetGroup = group
                        }
                        forSuperset = []
                    }
                }
            }
        } header: {
            Text(isWorkout ? "Exercises" : "Steps")
        }
    }

    @ViewBuilder
    private func stepEditor(index: Int, step: EditableStep) -> some View {
        let binding = Binding(get: { s.routineSteps[safe: index] ?? step }, set: { if s.routineSteps.indices.contains(index) { s.routineSteps[index] = $0 } })
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Toggle("", isOn: Binding(
                    get: { forSuperset.contains(step.id) },
                    set: { if $0 { forSuperset.insert(step.id) } else { forSuperset.remove(step.id) } }
                ))
                .labelsHidden()
                .help("Select for a superset")
                TextField(isWorkout ? "Exercise name" : "Step name", text: binding.title)
                Button { move(index, by: -1) } label: { Image(systemName: "chevron.up") }.disabled(index == 0)
                Button { move(index, by: 1) } label: { Image(systemName: "chevron.down") }.disabled(index == s.routineSteps.count - 1)
                Button(role: .destructive) { s.routineSteps.remove(at: index) } label: { Image(systemName: "trash") }
            }
            .buttonStyle(.borderless)
            TextField("Notes (optional): form cues, weights to use…", text: binding.description)
                .font(.caption)
            HStack {
                Picker("Type", selection: binding.stepType) {
                    ForEach(types, id: \.0) { t in Text(t.1).tag(t.0) }
                    if !types.contains(where: { $0.0 == step.stepType }) { Text(step.stepType).tag(step.stepType) }
                }
                .frame(maxWidth: 220)
                if step.stepType == "weight_reps" || step.stepType == "just_reps" {
                    Stepper("Sets: \(step.defaultSets)", value: binding.defaultSets, in: 1...20)
                }
                if step.stepType == "timed" {
                    Stepper("\(step.durationSeconds) s", value: binding.durationSeconds, in: 5...3600, step: 5)
                }
            }
            if step.stepType == "report" {
                ReportPicker(selection: binding.reportId, reports: reports)
            }
            if let group = step.supersetGroup {
                HStack {
                    Text("In superset").font(.caption).foregroundStyle(Palette.tertiary)
                    Button("Ungroup") {
                        for i in s.routineSteps.indices where s.routineSteps[i].supersetGroup == group {
                            s.routineSteps[i].supersetGroup = nil
                        }
                    }
                    .buttonStyle(.borderless)
                    .font(.caption)
                }
            }
        }
        .padding(.vertical, 4)
    }

    private func move(_ index: Int, by delta: Int) {
        let to = index + delta
        guard s.routineSteps.indices.contains(to) else { return }
        s.routineSteps.swapAt(index, to)
    }
}

private struct ScheduleStep: View {
    @Binding var s: TaskEditorState

    var body: some View {
        Section("Recurrence") {
            Picker("Repeats", selection: $s.recurrenceType) {
                Text("One-time").tag(String?.none)
                Text("Daily").tag(String?.some("daily"))
                Text("Weekly").tag(String?.some("weekly"))
                if s.recurrenceType == "monthly" { Text("Monthly (kept as is)").tag(String?.some("monthly")) }
                if s.recurrenceType == "interval" { Text("Interval (kept as is)").tag(String?.some("interval")) }
            }
            .pickerStyle(.segmented)
            if s.recurrenceType == "weekly" {
                DayPicker(selection: $s.weeklyDays)
            }
        }
        Section {
            TimeField(label: "Available from", text: $s.availableFrom)
            TimeField(label: "Due at", text: $s.dueAt)
        } header: {
            Text("Availability window")
        } footer: {
            Text("When this task can be completed and when it becomes overdue.")
        }
        Section("Blocking") {
            Toggle(isOn: $s.isBlockingCondition) {
                VStack(alignment: .leading) {
                    Text("Use as blocking condition")
                    Text("Makes this task available to blocking rules. Add it to a rule to block apps and websites until it's done.")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            if s.isBlockingCondition {
                Toggle(isOn: $s.homeOnlyBlocking) {
                    VStack(alignment: .leading) {
                        Text("Only block when at home")
                        Text("Your phone decides whether you're home. On this Mac the task always blocks.")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }
                VStack(alignment: .leading, spacing: 4) {
                    Text("Blocking days")
                    Text("Days this task enforces blocking. Leave empty to block every day.")
                        .font(.caption).foregroundStyle(.secondary)
                    DayPicker(selection: $s.blockingDaysOfWeek)
                }
            }
        }
    }
}
