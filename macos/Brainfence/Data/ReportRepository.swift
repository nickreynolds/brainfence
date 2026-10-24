import BrainfenceCore
import Foundation
import PowerSync

/// Reports, their metrics and recorded answers.
/// Port of android/…/data/report/ReportRepository.kt.
struct ReportRepository: Sendable {
    let db: PowerSyncDatabaseProtocol

    static let definitionTables = ["metrics", "metric_options", "reports", "report_metrics"]
    static let submissionTables = ["report_submissions", "metric_entries"]

    private static let today = "date(s.submitted_at, 'localtime') = date('now', 'localtime')"

    private func metricMap() async throws -> [String: Metric] {
        let options = try await db.getAll(
            sql: "SELECT id, metric_id, label, sort_order FROM metric_options ORDER BY sort_order, created_at",
            parameters: []
        ) { c in
            MetricOption(
                id: try c.getString(index: 0),
                metricId: c.getStringOptional(index: 1) ?? "",
                label: c.getStringOptional(index: 2) ?? "",
                sortOrder: Int(c.getInt64Optional(index: 3) ?? 0)
            )
        }
        let byMetric = Dictionary(grouping: options, by: \.metricId)
        let metrics = try await db.getAll(
            sql: "SELECT id, name, description, kind FROM metrics ORDER BY created_at",
            parameters: []
        ) { c in
            Metric(
                id: try c.getString(index: 0),
                name: c.getStringOptional(index: 1) ?? "",
                description: c.getStringOptional(index: 2),
                kind: MetricKind(raw: c.getStringOptional(index: 3))
            )
        }
        var out: [String: Metric] = [:]
        for var m in metrics {
            m.options = byMetric[m.id] ?? []
            out[m.id] = m
        }
        return out
    }

    func metrics() async throws -> [Metric] {
        try await metricMap().values.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }

    func reports() async throws -> [Report] {
        let metrics = try await metricMap()
        let membership = try await db.getAll(
            sql: "SELECT report_id, metric_id FROM report_metrics ORDER BY sort_order, created_at",
            parameters: []
        ) { c in (c.getStringOptional(index: 0) ?? "", c.getStringOptional(index: 1) ?? "") }
        var byReport: [String: [String]] = [:]
        for (reportId, metricId) in membership { byReport[reportId, default: []].append(metricId) }
        let rows = try await db.getAll(
            sql: "SELECT id, name, include_note, library_key FROM reports ORDER BY created_at",
            parameters: []
        ) { c in
            Report(
                id: try c.getString(index: 0),
                name: c.getStringOptional(index: 1) ?? "",
                includeNote: (c.getInt64Optional(index: 2) ?? 1) != 0,
                libraryKey: c.getStringOptional(index: 3)
            )
        }
        return rows.map { r in
            var r = r
            r.metrics = (byReport[r.id] ?? []).compactMap { metrics[$0] }
            return r
        }
    }

    func report(id: String) async throws -> Report? {
        try await reports().first { $0.id == id }
    }

    func createReport(userId: String, name: String) async throws -> String {
        let id = UUID().uuidString.lowercased()
        let now = Timestamp.format(Date())
        try await db.execute(
            sql: """
                INSERT INTO reports (id, user_id, name, include_note, library_key, created_at, updated_at)
                VALUES (?, ?, ?, 1, NULL, ?, ?)
                """,
            parameters: [id, userId, name, now, now]
        )
        return id
    }

    func updateReport(id: String, name: String, includeNote: Bool) async throws {
        try await db.execute(
            sql: "UPDATE reports SET name = ?, include_note = ?, updated_at = ? WHERE id = ?",
            parameters: [name, includeNote ? 1 : 0, Timestamp.format(Date()), id]
        )
    }

    /// Deletes a report and its metric list (metrics are shared and kept). Past submissions stay.
    func deleteReport(id: String) async throws {
        try await db.writeTransaction { tx in
            try tx.execute(sql: "DELETE FROM report_metrics WHERE report_id = ?", parameters: [id])
            try tx.execute(sql: "DELETE FROM reports WHERE id = ?", parameters: [id])
        }
    }

    func addMetric(_ metricId: String, toReport reportId: String, userId: String) async throws {
        try await db.execute(
            sql: """
                INSERT INTO report_metrics (id, user_id, report_id, metric_id, sort_order, created_at)
                SELECT ?, ?, ?, ?, (SELECT COALESCE(MAX(sort_order), -1) + 1 FROM report_metrics WHERE report_id = ?), ?
                WHERE NOT EXISTS (SELECT 1 FROM report_metrics WHERE report_id = ? AND metric_id = ?)
                """,
            parameters: [UUID().uuidString.lowercased(), userId, reportId, metricId, reportId, Timestamp.format(Date()), reportId, metricId]
        )
    }

    func removeMetric(_ metricId: String, fromReport reportId: String) async throws {
        try await db.execute(sql: "DELETE FROM report_metrics WHERE report_id = ? AND metric_id = ?", parameters: [reportId, metricId])
    }

    /// Moves a metric up (delta -1) or down (+1) within a report.
    func moveMetric(_ metricId: String, inReport reportId: String, by delta: Int) async throws {
        var rows = try await db.getAll(
            sql: "SELECT id, metric_id FROM report_metrics WHERE report_id = ? ORDER BY sort_order, created_at",
            parameters: [reportId]
        ) { c in (id: try c.getString(index: 0), metricId: c.getStringOptional(index: 1) ?? "") }
        guard let from = rows.firstIndex(where: { $0.metricId == metricId }) else { return }
        let to = from + delta
        guard rows.indices.contains(to) else { return }
        rows.insert(rows.remove(at: from), at: to)
        let ordered = rows.map(\.id)
        try await db.writeTransaction { tx in
            for (i, id) in ordered.enumerated() {
                try tx.execute(sql: "UPDATE report_metrics SET sort_order = ? WHERE id = ?", parameters: [i, id])
            }
        }
    }

    private static func insertMetric(
        _ tx: any ConnectionContext, id: String, userId: String, name: String, description: String?,
        kind: MetricKind, labels: [String], now: String
    ) throws {
        try tx.execute(
            sql: "INSERT INTO metrics (id, user_id, name, description, kind, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
            parameters: [id, userId, name, description, kind.rawValue, now, now]
        )
        for (index, label) in labels.enumerated() {
            try tx.execute(
                sql: "INSERT INTO metric_options (id, user_id, metric_id, label, sort_order, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                parameters: [UUID().uuidString.lowercased(), userId, id, label, index, now, now]
            )
        }
    }

    func createMetric(userId: String, name: String, description: String?, kind: MetricKind, labels: [String]) async throws -> String {
        let id = UUID().uuidString.lowercased()
        let now = Timestamp.format(Date())
        try await db.writeTransaction { tx in
            try Self.insertMetric(tx, id: id, userId: userId, name: name, description: description, kind: kind, labels: labels, now: now)
        }
        return id
    }

    /// An option being edited: id nil = new.
    struct OptionDraft: Sendable, Hashable {
        var id: String?
        var label: String
    }

    /// Saves a metric and its options in order. Existing options keep their
    /// ids (history stays linked through renames and reorders); missing ones
    /// are deleted.
    func saveMetric(id metricId: String, userId: String, name: String, description: String?, kind: MetricKind, options: [OptionDraft]) async throws {
        let now = Timestamp.format(Date())
        let existing = Set(try await db.getAll(
            sql: "SELECT id FROM metric_options WHERE metric_id = ?",
            parameters: [metricId]
        ) { try $0.getString(index: 0) })
        try await db.writeTransaction { tx in
            try tx.execute(
                sql: "UPDATE metrics SET name = ?, description = ?, kind = ?, updated_at = ? WHERE id = ?",
                parameters: [name, description, kind.rawValue, now, metricId]
            )
            let keep = Set(options.compactMap(\.id))
            for id in existing where !keep.contains(id) {
                try tx.execute(sql: "DELETE FROM metric_options WHERE id = ?", parameters: [id])
            }
            for (index, option) in options.enumerated() {
                if let id = option.id, existing.contains(id) {
                    try tx.execute(
                        sql: "UPDATE metric_options SET label = ?, sort_order = ?, updated_at = ? WHERE id = ?",
                        parameters: [option.label, index, now, id]
                    )
                } else {
                    try tx.execute(
                        sql: "INSERT INTO metric_options (id, user_id, metric_id, label, sort_order, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                        parameters: [UUID().uuidString.lowercased(), userId, metricId, option.label, index, now, now]
                    )
                }
            }
        }
    }

    /// Deletes a metric everywhere. Recorded answers keep their snapshots.
    func deleteMetric(id: String) async throws {
        try await db.writeTransaction { tx in
            try tx.execute(sql: "DELETE FROM report_metrics WHERE metric_id = ?", parameters: [id])
            try tx.execute(sql: "DELETE FROM metric_options WHERE metric_id = ?", parameters: [id])
            try tx.execute(sql: "DELETE FROM metrics WHERE id = ?", parameters: [id])
        }
    }

    /// Copies a starter report and its metrics into the user's tables.
    func addFromLibrary(_ starter: StarterReport, userId: String) async throws {
        let now = Timestamp.format(Date())
        let reportId = UUID().uuidString.lowercased()
        let metrics = starter.metrics.map { (id: UUID().uuidString.lowercased(), metric: $0) }
        try await db.writeTransaction { tx in
            for m in metrics {
                try Self.insertMetric(
                    tx, id: m.id, userId: userId, name: m.metric.name, description: m.metric.description,
                    kind: m.metric.kind, labels: m.metric.options, now: now
                )
            }
            try tx.execute(
                sql: "INSERT INTO reports (id, user_id, name, include_note, library_key, created_at, updated_at) VALUES (?, ?, ?, 1, ?, ?, ?)",
                parameters: [reportId, userId, starter.name, starter.key, now, now]
            )
            for (index, m) in metrics.enumerated() {
                try tx.execute(
                    sql: "INSERT INTO report_metrics (id, user_id, report_id, metric_id, sort_order, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                    parameters: [UUID().uuidString.lowercased(), userId, reportId, m.id, index, now]
                )
            }
        }
    }

    /// Records a filled-in report: one submission plus an entry per answer.
    /// [answers] maps metric id → option id and must cover every metric.
    /// Returns the submission id.
    @discardableResult
    func submit(
        _ report: Report, answers: [String: String], note: String?, taskId: String?, routineStepId: String?, userId: String
    ) async throws -> String {
        let entries = try buildMetricEntries(report, answers: answers)
        let now = Timestamp.format(Date())
        let submissionId = UUID().uuidString.lowercased()
        let trimmed = note?.trimmingCharacters(in: .whitespacesAndNewlines)
        let note = trimmed?.isEmpty == false ? trimmed : nil
        try await db.writeTransaction { tx in
            try tx.execute(
                sql: """
                    INSERT INTO report_submissions
                        (id, user_id, report_id, task_id, routine_step_id, report_name, note, submitted_at, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                parameters: [submissionId, userId, report.id, taskId, routineStepId, report.name, note, now, now]
            )
            for (position, e) in entries.enumerated() {
                try tx.execute(
                    sql: """
                        INSERT INTO metric_entries
                            (id, user_id, submission_id, metric_id, option_id, metric_name, metric_kind,
                             option_label, option_index, option_count, position, recorded_at, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                    parameters: [
                        UUID().uuidString.lowercased(), userId, submissionId, e.metricId, e.optionId, e.metricName,
                        e.metricKind.rawValue, e.optionLabel, e.optionIndex, e.optionCount, position, now, now,
                    ]
                )
            }
        }
        return submissionId
    }

    /// Today's submission from the report task [taskId] (not from routine steps).
    func todaysTaskSubmission(taskId: String) async throws -> ReportSubmission? {
        try await submissions(where: "s.task_id = ? AND s.routine_step_id IS NULL AND \(Self.today)", parameters: [taskId]).first
    }

    /// Today's submissions from the report steps of routine [taskId], keyed by step id.
    func todaysRoutineSubmissions(taskId: String) async throws -> [String: ReportSubmission] {
        let subs = try await submissions(where: "s.task_id = ? AND s.routine_step_id IS NOT NULL AND \(Self.today)", parameters: [taskId])
        var out: [String: ReportSubmission] = [:]
        // Oldest first so the newest wins.
        for s in subs.reversed() { if let step = s.routineStepId { out[step] = s } }
        return out
    }

    func recentSubmissions(limit: Int = 20) async throws -> [ReportSubmission] {
        try await submissions(where: "1 = 1", parameters: [], limit: limit)
    }

    private func submissions(where clause: String, parameters: [Sendable?], limit: Int? = nil) async throws -> [ReportSubmission] {
        let rows = try await db.getAll(
            sql: """
                SELECT s.id, s.report_id, s.task_id, s.routine_step_id, s.report_name, s.note, s.submitted_at
                FROM report_submissions s WHERE \(clause) ORDER BY s.submitted_at DESC\(limit.map { " LIMIT \($0)" } ?? "")
                """,
            parameters: parameters
        ) { c in
            ReportSubmission(
                id: try c.getString(index: 0),
                reportId: c.getStringOptional(index: 1),
                taskId: c.getStringOptional(index: 2),
                routineStepId: c.getStringOptional(index: 3),
                reportName: c.getStringOptional(index: 4) ?? "",
                note: c.getStringOptional(index: 5),
                submittedAt: c.getStringOptional(index: 6) ?? ""
            )
        }
        guard !rows.isEmpty else { return [] }
        let placeholders = Array(repeating: "?", count: rows.count).joined(separator: ",")
        let entries = try await db.getAll(
            sql: """
                SELECT submission_id, metric_id, option_id, metric_name, metric_kind, option_label, option_index, option_count
                FROM metric_entries WHERE submission_id IN (\(placeholders)) ORDER BY position
                """,
            parameters: rows.map { $0.id as Sendable? }
        ) { c in
            (c.getStringOptional(index: 0) ?? "", MetricEntry(
                metricId: c.getStringOptional(index: 1),
                optionId: c.getStringOptional(index: 2),
                metricName: c.getStringOptional(index: 3) ?? "",
                metricKind: MetricKind(raw: c.getStringOptional(index: 4)),
                optionLabel: c.getStringOptional(index: 5) ?? "",
                optionIndex: Int(c.getInt64Optional(index: 6) ?? 0),
                optionCount: Int(c.getInt64Optional(index: 7) ?? 0)
            ))
        }
        var bySubmission: [String: [MetricEntry]] = [:]
        for (id, entry) in entries { bySubmission[id, default: []].append(entry) }
        return rows.map { s in
            var s = s
            s.entries = bySubmission[s.id] ?? []
            return s
        }
    }
}
