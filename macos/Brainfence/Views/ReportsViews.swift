import BrainfenceCore
import SwiftUI

/// Every journal entry, newest first. Port of Android's JournalHistoryScreen.
/// Journal entries, newest first: Review's Journal tab.
private struct JournalHistoryView: View {
    @Environment(AppModel.self) private var model
    @State private var entries: [JournalEntry] = []
    @State private var loaded = false

    var body: some View {
        List {
            if loaded && entries.isEmpty {
                Text("No journal entries yet.").foregroundStyle(.secondary)
            }
            ForEach(entries) { e in
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Text(e.taskTitle).font(.headline)
                        Spacer()
                        Text(formatTimestamp(e.completedAt)).font(.caption).foregroundStyle(.secondary)
                    }
                    Text(e.text.isEmpty ? "(empty)" : e.text)
                        .foregroundStyle(e.text.isEmpty ? .secondary : .primary)
                        .textSelection(.enabled)
                }
                .padding(.vertical, 4)
            }
        }
        .reloading(on: ShoppingRepository.journalTables, db: model.db) {
            entries = (try? await model.shoppingRepository.journalEntries()) ?? []
            loaded = true
        }
    }
}

/// Reports, metrics and recent answers, with editors. Port of Android's
/// ReportsScreen, ReportEditorScreen and MetricEditorScreen.
/// The Review section: journal entries and reports, as tabs. Port of
/// Android's ReviewScreen.
struct ReviewView: View {
    @Environment(AppModel.self) private var model
    @State private var error: String?

    var body: some View {
        @Bindable var navigation = model.navigation
        NavigationStack(path: $navigation.reportPath) {
            Group {
                switch navigation.reviewTab {
                case .journal: JournalHistoryView()
                case .reports: ReportListView()
                }
            }
            .navigationTitle("Review")
            .toolbar {
                ToolbarItem(placement: .principal) {
                    Picker("Review", selection: $navigation.reviewTab) {
                        ForEach(AppNavigation.ReviewTab.allCases) { Text($0.rawValue).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    .labelsHidden()
                }
                if navigation.reviewTab == .reports {
                    ToolbarItem(placement: .primaryAction) {
                        Button(action: newReport) {
                            Label("New Report", systemImage: "plus")
                        }
                        .buttonStyle(.create)
                    }
                }
            }
            .navigationDestination(for: AppNavigation.ReportRoute.self) { route in
                switch route {
                case .report(let id): ReportEditorView(reportId: id)
                case .metric(let id): MetricEditorView(metricId: id)
                }
            }
            .alert("Couldn't create the report", isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) {
                Button("OK") { error = nil }
            } message: {
                Text(error ?? "")
            }
        }
    }

    private func newReport() {
        Task {
            do {
                let id = try await model.reportRepository.createReport(userId: model.requireUserId(), name: "New report")
                model.navigation.reportPath.append(.report(id))
            } catch {
                self.error = error.localizedDescription
            }
        }
    }
}

private struct ReportListView: View {
    @Environment(AppModel.self) private var model
    @State private var reports: [Report] = []
    @State private var metrics: [Metric] = []
    @State private var recent: [ReportSubmission] = []
    @State private var error: String?

    var body: some View {
        let added = Set(reports.compactMap(\.libraryKey))
        List {
            Text("A report is a set of metrics you answer from a Report task or a routine's report step. Metrics are shared, so every report that asks one feeds a single history.")
                .font(.caption).foregroundStyle(.secondary)
            ErrorText(error: error)
            Section("Reports") {
                ForEach(reports) { r in
                    NavigationLink(value: AppNavigation.ReportRoute.report(r.id)) {
                        VStack(alignment: .leading) {
                            Text(r.name).font(.headline)
                            Text(r.metrics.isEmpty ? "No metrics yet" : r.metrics.map(\.name).joined(separator: ", "))
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
                ForEach(reportLibrary.filter { !added.contains($0.key) }, id: \.key) { starter in
                    HStack {
                        VStack(alignment: .leading) {
                            Text("Starter: \(starter.name)")
                            Text(starter.description).font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                        Button("Add") { act { try await model.reportRepository.addFromLibrary(starter, userId: model.requireUserId()) } }
                    }
                }
            }
            if !metrics.isEmpty {
                Section("All metrics") {
                    ForEach(metrics) { m in
                        NavigationLink(value: AppNavigation.ReportRoute.metric(m.id)) {
                            VStack(alignment: .leading) {
                                Text(m.name)
                                Text("\(m.kind == .ordinal ? "Scale" : "Categories"): \(m.options.map(\.label).joined(separator: " · "))")
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            }
            if !recent.isEmpty {
                Section("Recent answers") {
                    ForEach(recent) { s in
                        VStack(alignment: .leading, spacing: 4) {
                            HStack {
                                Text(s.reportName).font(.headline)
                                Spacer()
                                Text(formatTimestamp(s.submittedAt)).font(.caption).foregroundStyle(.secondary)
                            }
                            ReportSubmissionSummary(submission: s)
                        }
                    }
                }
            }
        }
        .reloading(on: ReportRepository.definitionTables + ReportRepository.submissionTables, db: model.db) {
            reports = (try? await model.reportRepository.reports()) ?? []
            metrics = (try? await model.reportRepository.metrics()) ?? []
            recent = (try? await model.reportRepository.recentSubmissions()) ?? []
        }
    }

    private func act(_ work: @escaping @MainActor () async throws -> Void) {
        Task {
            do { try await work() } catch { self.error = error.localizedDescription }
        }
    }
}

private struct ReportEditorView: View {
    let reportId: String
    @Environment(AppModel.self) private var model
    @State private var report: Report?
    @State private var metrics: [Metric] = []
    @State private var loaded = false
    @State private var name = ""
    @State private var includeNote = true
    @State private var seeded = false
    @State private var error: String?
    @State private var addingMetric = false
    @State private var confirmingDelete = false

    var body: some View {
        Group {
            if let report {
                Form {
                    Section {
                        TextField("Name", text: $name)
                        Toggle("Ask for an optional note", isOn: $includeNote)
                        ErrorText(error: error)
                        Button("Save") { save() }.keyboardShortcut(.defaultAction)
                    }
                    Section {
                        ForEach(Array(report.metrics.enumerated()), id: \.element.id) { i, m in
                            HStack {
                                NavigationLink(value: AppNavigation.ReportRoute.metric(m.id)) {
                                    VStack(alignment: .leading) {
                                        Text(m.name)
                                        Text(m.options.map(\.label).joined(separator: " · ")).font(.caption).foregroundStyle(.secondary)
                                    }
                                }
                                Button { act { try await model.reportRepository.moveMetric(m.id, inReport: reportId, by: -1) } } label: { Image(systemName: "chevron.up") }
                                    .disabled(i == 0)
                                Button { act { try await model.reportRepository.moveMetric(m.id, inReport: reportId, by: 1) } } label: { Image(systemName: "chevron.down") }
                                    .disabled(i == report.metrics.count - 1)
                                Button { act { try await model.reportRepository.removeMetric(m.id, fromReport: reportId) } } label: { Image(systemName: "xmark") }
                                    .help("Remove from report")
                            }
                            .buttonStyle(.borderless)
                        }
                        Button("Add Metric…") { addingMetric = true }
                    } header: {
                        Text("Metrics, in the order they're asked")
                    } footer: {
                        Text("Changes here save immediately.")
                    }
                }
                .formStyle(.grouped)
            } else if loaded {
                Text("This report no longer exists.").foregroundStyle(.secondary)
            } else {
                ProgressView()
            }
        }
        .navigationTitle(report?.name ?? "Report")
        .toolbar {
            Button(role: .destructive) { confirmingDelete = true } label: { Label("Delete Report", systemImage: "trash") }
                .disabled(report == nil)
        }
        .confirmationDialog("Delete report?", isPresented: $confirmingDelete) {
            Button("Delete", role: .destructive) {
                act {
                    try await model.reportRepository.deleteReport(id: reportId)
                    model.navigation.reportPath.removeAll()
                }
            }
            Button("Keep", role: .cancel) {}
        } message: {
            Text("Its metrics are kept (other reports may use them) and past answers stay in your history. Tasks or routine steps that ask it will need a new report.")
        }
        .sheet(isPresented: $addingMetric) {
            AddMetricSheet(available: metrics.filter { m in !(report?.metrics.contains { $0.id == m.id } ?? false) }) { choice in
                act {
                    let userId = try model.requireUserId()
                    switch choice {
                    case .existing(let id):
                        try await model.reportRepository.addMetric(id, toReport: reportId, userId: userId)
                    case .new:
                        let id = try await model.reportRepository.createMetric(userId: userId, name: "New metric", description: nil, kind: .ordinal, labels: newMetricOptions)
                        try await model.reportRepository.addMetric(id, toReport: reportId, userId: userId)
                        model.navigation.reportPath.append(.metric(id))
                    }
                }
            }
        }
        .reloading(on: ReportRepository.definitionTables, db: model.db, id: reportId) {
            report = try? await model.reportRepository.report(id: reportId)
            metrics = (try? await model.reportRepository.metrics()) ?? []
            loaded = true
            // Seed once so later metric edits don't clobber typing.
            if let report, !seeded {
                name = report.name
                includeNote = report.includeNote
                seeded = true
            }
        }
    }

    private func save() {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else {
            error = "Name is required"
            return
        }
        act {
            try await model.reportRepository.updateReport(id: reportId, name: trimmed, includeNote: includeNote)
            model.navigation.reportPath.removeLast()
        }
    }

    private func act(_ work: @escaping @MainActor () async throws -> Void) {
        Task {
            do { try await work() } catch { self.error = error.localizedDescription }
        }
    }
}

private enum MetricChoice {
    case existing(String)
    case new
}

private struct AddMetricSheet: View {
    let available: [Metric]
    let onChoose: (MetricChoice) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Add metric").font(.headline)
            Button("New Metric") {
                onChoose(.new)
                dismiss()
            }
            List(available) { m in
                Button {
                    onChoose(.existing(m.id))
                    dismiss()
                } label: {
                    VStack(alignment: .leading) {
                        Text(m.name)
                        Text(m.options.map(\.label).joined(separator: " · ")).font(.caption).foregroundStyle(.secondary)
                    }
                }
                .buttonStyle(.plain)
            }
            .frame(minHeight: 200)
            HStack {
                Spacer()
                Button("Cancel") { dismiss() }.keyboardShortcut(.cancelAction)
            }
        }
        .padding(20)
        .frame(width: 400, height: 420)
    }
}

private struct MetricEditorView: View {
    let metricId: String
    @Environment(AppModel.self) private var model
    @State private var metric: Metric?
    @State private var usedIn: [String] = []
    @State private var loaded = false
    @State private var name = ""
    @State private var hint = ""
    @State private var kind: MetricKind = .ordinal
    @State private var options: [EditableOption] = []
    @State private var seeded = false
    @State private var error: String?
    @State private var confirmingDelete = false

    var body: some View {
        Group {
            if metric != nil {
                Form {
                    if !usedIn.isEmpty {
                        Text("Used in \(usedIn.joined(separator: ", "))").font(.caption).foregroundStyle(.secondary)
                    }
                    Section {
                        TextField("Name", text: $name)
                        TextField("Hint (optional), e.g. 1 = not at all · 5 = very much", text: $hint)
                        Picker("Answers are", selection: $kind) {
                            Text("A scale (ordered)").tag(MetricKind.ordinal)
                            Text("Categories").tag(MetricKind.categorical)
                        }
                        .pickerStyle(.segmented)
                    }
                    Section {
                        ForEach($options) { $option in
                            HStack {
                                TextField("Option", text: $option.label)
                                Button { move(option.key, by: -1) } label: { Image(systemName: "chevron.up") }
                                    .disabled(options.first?.key == option.key)
                                Button { move(option.key, by: 1) } label: { Image(systemName: "chevron.down") }
                                    .disabled(options.last?.key == option.key)
                                Button { options.removeAll { $0.key == option.key } } label: { Image(systemName: "xmark") }
                                    .disabled(options.count <= minMetricOptions)
                            }
                            .buttonStyle(.borderless)
                        }
                        if options.count < maxMetricOptions {
                            Button("Add Option") { options.append(EditableOption(optionId: nil, label: "")) }
                        }
                    } header: {
                        Text("Options (\(minMetricOptions)–\(maxMetricOptions))")
                    } footer: {
                        Text("Renaming or reordering keeps past answers as they were recorded.")
                    }
                    ErrorText(error: error)
                    Button("Save") { save() }.keyboardShortcut(.defaultAction)
                }
                .formStyle(.grouped)
            } else if loaded {
                Text("This metric no longer exists.").foregroundStyle(.secondary)
            } else {
                ProgressView()
            }
        }
        .navigationTitle(metric?.name ?? "Metric")
        .toolbar {
            Button(role: .destructive) { confirmingDelete = true } label: { Label("Delete Metric", systemImage: "trash") }
                .disabled(metric == nil)
        }
        .confirmationDialog("Delete metric?", isPresented: $confirmingDelete) {
            Button("Delete", role: .destructive) {
                Task {
                    do {
                        try await model.reportRepository.deleteMetric(id: metricId)
                        model.navigation.reportPath.removeLast()
                    } catch {
                        self.error = error.localizedDescription
                    }
                }
            }
            Button("Keep", role: .cancel) {}
        } message: {
            Text("It's removed from every report that asks it. Answers already recorded stay in your history.")
        }
        .reloading(on: ReportRepository.definitionTables, db: model.db, id: metricId) {
            let metrics = (try? await model.reportRepository.metrics()) ?? []
            let reports = (try? await model.reportRepository.reports()) ?? []
            metric = metrics.first { $0.id == metricId }
            usedIn = reports.filter { $0.metrics.contains { $0.id == metricId } }.map(\.name)
            loaded = true
            if let metric, !seeded {
                name = metric.name
                hint = metric.description ?? ""
                kind = metric.kind
                options = metric.options.map { EditableOption(optionId: $0.id, label: $0.label) }
                seeded = true
            }
        }
    }

    /// An option row; `key` keeps rows stable while editing, `optionId` is
    /// the stored option's id (nil for new ones).
    struct EditableOption: Identifiable {
        let key = UUID()
        var optionId: String?
        var label: String

        var id: UUID { key }
    }

    private func move(_ key: UUID, by delta: Int) {
        guard let from = options.firstIndex(where: { $0.key == key }), options.indices.contains(from + delta) else { return }
        options.swapAt(from, from + delta)
    }

    private func save() {
        if let message = validateMetricDraft(name: name, optionLabels: options.map(\.label)) {
            error = message
            return
        }
        let trimmedHint = hint.trimmingCharacters(in: .whitespaces)
        let drafts = options.map { ReportRepository.OptionDraft(id: $0.optionId, label: $0.label.trimmingCharacters(in: .whitespaces)) }
        Task {
            do {
                try await model.reportRepository.saveMetric(
                    id: metricId, userId: model.requireUserId(), name: name.trimmingCharacters(in: .whitespaces),
                    description: trimmedHint.isEmpty ? nil : trimmedHint, kind: kind, options: drafts
                )
                model.navigation.reportPath.removeLast()
            } catch {
                self.error = error.localizedDescription
            }
        }
    }
}

/// Option buttons per metric, an optional note, and Submit once every metric
/// is answered. Used by report tasks and routine report steps.
struct ReportForm: View {
    let report: Report
    let isSubmitting: Bool
    let onSubmit: ([String: String], String) -> Void
    @State private var answers: [String: String] = [:]
    @State private var note = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            if report.metrics.isEmpty {
                Text("This report has no metrics yet. Add some under Reports.").foregroundStyle(.secondary)
            }
            ForEach(report.metrics) { metric in
                VStack(alignment: .leading, spacing: 6) {
                    Text(metric.name).font(.headline)
                    if let hint = metric.description { Text(hint).font(.caption).foregroundStyle(.secondary) }
                    HStack {
                        ForEach(metric.options) { option in
                            let selected = answers[metric.id] == option.id
                            Button(option.label) { answers[metric.id] = option.id }
                                .buttonStyle(.bordered)
                                .tint(selected ? Palette.onPrimaryContainer : nil)
                                .background(selected ? Palette.primaryContainer : .clear, in: RoundedRectangle(cornerRadius: 6))
                                .disabled(isSubmitting)
                        }
                    }
                }
            }
            if report.includeNote {
                TextField("Note (optional)", text: $note, axis: .vertical)
                    .lineLimit(2...4)
                    .disabled(isSubmitting)
            }
            Button(isSubmitting ? "Saving…" : "Submit") { onSubmit(answers, note) }
                .keyboardShortcut(.defaultAction)
                .disabled(!unansweredMetrics(report, answers: answers).isEmpty || report.metrics.isEmpty || isSubmitting)
        }
    }
}

struct ReportSubmissionSummary: View {
    let submission: ReportSubmission

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            ForEach(Array(submission.entries.enumerated()), id: \.offset) { _, e in
                (Text("\(e.metricName): ").foregroundStyle(.secondary) + Text(e.optionLabel).bold())
            }
            if let note = submission.note {
                Text("“\(note)”").font(.caption).foregroundStyle(.secondary)
            }
        }
    }
}
