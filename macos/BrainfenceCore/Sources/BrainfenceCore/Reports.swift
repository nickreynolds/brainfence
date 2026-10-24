import Foundation

// Reports and metrics. Port of android/…/domain/report/ReportAnswers.kt and
// data/report/ReportLibrary.kt (via desktop-app/src/shared/reports.ts).

public enum MetricKind: String, Sendable, Equatable, CaseIterable {
    case ordinal, categorical

    public init(raw: String?) { self = raw == "categorical" ? .categorical : .ordinal }
}

public struct MetricOption: Sendable, Hashable, Identifiable {
    public let id: String
    public let metricId: String
    public var label: String
    public var sortOrder: Int

    public init(id: String, metricId: String, label: String, sortOrder: Int) {
        self.id = id
        self.metricId = metricId
        self.label = label
        self.sortOrder = sortOrder
    }
}

public struct Metric: Sendable, Hashable, Identifiable {
    public let id: String
    public var name: String
    public var description: String?
    public var kind: MetricKind
    public var options: [MetricOption]

    public init(id: String, name: String, description: String? = nil, kind: MetricKind = .ordinal, options: [MetricOption] = []) {
        self.id = id
        self.name = name
        self.description = description
        self.kind = kind
        self.options = options
    }
}

public struct Report: Sendable, Hashable, Identifiable {
    public let id: String
    public var name: String
    public var includeNote: Bool
    public var libraryKey: String?
    public var metrics: [Metric]

    public init(id: String, name: String, includeNote: Bool = true, libraryKey: String? = nil, metrics: [Metric] = []) {
        self.id = id
        self.name = name
        self.includeNote = includeNote
        self.libraryKey = libraryKey
        self.metrics = metrics
    }
}

/// One recorded answer, snapshotting the metric and option at the time.
public struct MetricEntry: Sendable, Hashable {
    public let metricId: String?
    public let optionId: String?
    public let metricName: String
    public let metricKind: MetricKind
    public let optionLabel: String
    public let optionIndex: Int
    public let optionCount: Int

    public init(metricId: String?, optionId: String?, metricName: String, metricKind: MetricKind, optionLabel: String, optionIndex: Int, optionCount: Int) {
        self.metricId = metricId
        self.optionId = optionId
        self.metricName = metricName
        self.metricKind = metricKind
        self.optionLabel = optionLabel
        self.optionIndex = optionIndex
        self.optionCount = optionCount
    }
}

public struct ReportSubmission: Sendable, Hashable, Identifiable {
    public let id: String
    public let reportId: String?
    public let taskId: String?
    public let routineStepId: String?
    public let reportName: String
    public let note: String?
    public let submittedAt: String
    public var entries: [MetricEntry]

    public init(id: String, reportId: String?, taskId: String?, routineStepId: String?, reportName: String, note: String?, submittedAt: String, entries: [MetricEntry] = []) {
        self.id = id
        self.reportId = reportId
        self.taskId = taskId
        self.routineStepId = routineStepId
        self.reportName = reportName
        self.note = note
        self.submittedAt = submittedAt
        self.entries = entries
    }
}

public let minMetricOptions = 2
public let maxMetricOptions = 5

public enum ReportError: LocalizedError, Equatable {
    case unanswered([String])

    public var errorDescription: String? {
        switch self {
        case .unanswered(let names): "Unanswered: \(names.joined(separator: ", "))"
        }
    }
}

/// Metrics in [report] without a valid answer in [answers] (metric id → option id).
public func unansweredMetrics(_ report: Report, answers: [String: String]) -> [Metric] {
    report.metrics.filter { metric in !metric.options.contains { $0.id == answers[metric.id] } }
}

/// Entries to record for a complete set of answers, snapshotting each
/// metric's name/kind and the chosen option's label, position and the
/// number of options.
public func buildMetricEntries(_ report: Report, answers: [String: String]) throws -> [MetricEntry] {
    let missing = unansweredMetrics(report, answers: answers)
    if !missing.isEmpty { throw ReportError.unanswered(missing.map(\.name)) }
    return report.metrics.map { metric in
        let index = metric.options.firstIndex { $0.id == answers[metric.id] }!
        let option = metric.options[index]
        return MetricEntry(
            metricId: metric.id,
            optionId: option.id,
            metricName: metric.name,
            metricKind: metric.kind,
            optionLabel: option.label,
            optionIndex: index,
            optionCount: metric.options.count
        )
    }
}

/// Why a metric draft can't be saved, or nil if it's valid.
public func validateMetricDraft(name: String, optionLabels: [String]) -> String? {
    if name.trimmingCharacters(in: .whitespaces).isEmpty { return "Name is required" }
    if optionLabels.contains(where: { $0.trimmingCharacters(in: .whitespaces).isEmpty }) { return "Options can't be blank" }
    if optionLabels.count < minMetricOptions || optionLabels.count > maxMetricOptions {
        return "Use \(minMetricOptions)–\(maxMetricOptions) options"
    }
    let distinct = Set(optionLabels.map { $0.trimmingCharacters(in: .whitespaces).lowercased() })
    if distinct.count != optionLabels.count { return "Options must be different from each other" }
    return nil
}

// MARK: - Library

public struct StarterMetric: Sendable {
    public let name: String
    public let description: String?
    public let kind: MetricKind
    public let options: [String]
}

public struct StarterReport: Sendable {
    public let key: String
    public let name: String
    public let description: String
    public let metrics: [StarterMetric]
}

private let oneToFive = ["1", "2", "3", "4", "5"]
private let oneToFiveHint = "1 = not at all · 5 = very much"

public let reportLibrary: [StarterReport] = [
    StarterReport(
        key: "mood",
        name: "Mood",
        description: "Depression, anxiety and happiness on a 1–5 scale.",
        metrics: [
            StarterMetric(name: "Depression", description: oneToFiveHint, kind: .ordinal, options: oneToFive),
            StarterMetric(name: "Anxiety", description: oneToFiveHint, kind: .ordinal, options: oneToFive),
            StarterMetric(name: "Happiness", description: oneToFiveHint, kind: .ordinal, options: oneToFive),
        ]
    ),
    StarterReport(
        key: "habits",
        name: "Habits",
        description: "Examples to edit: a categorical and a none/moderate/a lot metric.",
        metrics: [
            StarterMetric(name: "Nicotine", description: "Since the last report", kind: .categorical, options: ["None", "Patch", "Vape"]),
            StarterMetric(name: "Alcohol", description: "Since the last report", kind: .ordinal, options: ["None", "Moderate", "A lot"]),
        ]
    ),
]

/// Default options for a new metric — the common none/moderate/a lot scale.
public let newMetricOptions = ["None", "Moderate", "A lot"]
