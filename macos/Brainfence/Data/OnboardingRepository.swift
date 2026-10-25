import BrainfenceCore
import Foundation
import PowerSync

/// The synced setup-guide state (one `onboarding` row per user, id = user_id)
/// and the writes that finish the guide or a device's setup. Shared semantics:
/// shared/CONTRACTS.md (Onboarding); same behaviour as
/// desktop-app/src/shared/repos/onboarding.ts.
struct OnboardingRepository: Sendable {
    let db: PowerSyncDatabaseProtocol

    struct GuideResult: Sendable {
        let taskIds: [StarterKey: String]
        let ruleId: String?
    }

    func row() async throws -> OnboardingRow? {
        try await db.getOptional(sql: "SELECT * FROM onboarding LIMIT 1", parameters: []) { c in
            var setupAt: [String: String] = [:]
            for p in OnboardingRow.platforms {
                if let at = try c.getStringOptional(name: OnboardingRow.setupColumn(p)) { setupAt[p] = at }
            }
            return OnboardingRow(
                status: try c.getStringOptional(name: "status") ?? "",
                deferredSteps: DeferredStep.parseList(try c.getStringOptional(name: "deferred_steps")),
                blockingRuleId: try c.getStringOptional(name: "blocking_rule_id"),
                meditationTaskId: try c.getStringOptional(name: "meditation_task_id"),
                setupAt: setupAt
            )
        }
    }

    func activeTaskCount() async throws -> Int {
        try await db.get(sql: "SELECT COUNT(*) FROM tasks WHERE status = 'active'", parameters: []) { c in
            Int(try c.getInt64(index: 0))
        }
    }

    /// Skipped, or an existing account that never saw the guide.
    func dismiss(userId: String) async throws {
        let exists = try await rowExists(userId)
        try await db.writeTransaction { tx in
            try Self.upsert(tx, userId: userId, exists: exists, columns: ["status": "dismissed"])
        }
    }

    /// Writes everything the guide planned, and marks the guide completed.
    func applyGuide(_ plan: OnboardingPlan, userId: String) async throws -> GuideResult {
        let previous = try await row()
        let exists = try await rowExists(userId)
        let hasRemembrances = try await db.getOptional(
            sql: "SELECT id FROM contemplation_decks WHERE library_key = ?",
            parameters: [StarterKey.fiveRemembrances.rawValue]
        ) { try $0.getString(index: 0) } != nil
        let deck = contemplationLibrary.first { $0.key == StarterKey.fiveRemembrances.rawValue }

        let taskIds = Dictionary(uniqueKeysWithValues: plan.tasks.map { ($0.key, UUID().uuidString.lowercased()) })
        let ruleId = plan.rule == nil ? nil : UUID().uuidString.lowercased()
        let now = Timestamp.format(Date())
        let madeShared = ruleId != nil || taskIds[.meditation] != nil

        var columns: [String: Sendable?] = [
            "status": "completed",
            "completed_at": now,
            "deferred_steps": DeferredStep.encodeList(plan.deferredSteps),
            "blocking_rule_id": ruleId ?? previous?.blockingRuleId,
            "meditation_task_id": taskIds[.meditation] ?? previous?.meditationTaskId,
        ]
        // Something new for other devices to add their own apps to: ask them again.
        for p in OnboardingRow.platforms {
            if p == localPlatform { columns[OnboardingRow.setupColumn(p)] = now }
            else if madeShared { columns.updateValue(nil, forKey: OnboardingRow.setupColumn(p)) }
        }
        let rowColumns = columns

        try await db.writeTransaction { tx in
            for task in plan.tasks {
                try Self.insert(tx, task: task, id: taskIds[task.key]!, userId: userId)
            }
            if plan.addRemembrances, !hasRemembrances, let deck {
                try ContemplationRepository.insertLibraryDeck(tx, deck, userId: userId)
            }
            if let rule = plan.rule, let ruleId {
                try tx.execute(
                    sql: """
                        INSERT INTO blocking_rules
                            (id, user_id, name, blocked_apps, blocked_domains, condition_task_ids,
                             condition_logic, config_lock_hours, is_active, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, 24, 1, ?, ?)
                        """,
                    parameters: [
                        ruleId, userId, rule.name,
                        BlockedApp.encodeList(rule.blockedApps).jsonString,
                        JSONValue.array(of: rule.blockedDomains).jsonString,
                        JSONValue.array(of: rule.conditionKeys.compactMap { taskIds[$0] }).jsonString,
                        rule.conditionLogic, now, now,
                    ]
                )
            }
            try Self.upsert(tx, userId: userId, exists: exists, columns: rowColumns)
        }
        return GuideResult(taskIds: taskIds, ruleId: ruleId)
    }

    /// Replaces this platform's companion apps on the guide's meditation task,
    /// keeping every other platform's entries.
    func setCompanionApps(_ apps: [BlockedApp], taskId: String) async throws {
        let raw = try await db.getOptional(sql: "SELECT verification_config FROM tasks WHERE id = ?", parameters: [taskId]) {
            try $0.getStringOptional(index: 0)
        }
        guard let raw else { return }
        var config = JSONValue.parse(raw)?.objectValue ?? [:]
        let others = (config["companion_apps"]?.arrayValue ?? []).filter { entry in
            if case .string = entry { return true }
            return entry["platform"]?.stringValue != localPlatform
        }
        config["companion_apps"] = .array(others + apps.map(\.raw))
        try await db.execute(
            sql: "UPDATE tasks SET verification_config = ?, updated_at = ? WHERE id = ?",
            parameters: [JSONValue.object(config).jsonString, Timestamp.format(Date()), taskId]
        )
    }

    /// Records this device's setup. The Mac can't locate, so deferred steps stay for another device.
    func markDeviceSetUp(userId: String) async throws {
        let exists = try await rowExists(userId)
        var columns: [String: Sendable?] = [OnboardingRow.setupColumn(localPlatform): Timestamp.format(Date())]
        if !exists { columns["status"] = "completed" }
        let rowColumns = columns
        try await db.writeTransaction { tx in
            try Self.upsert(tx, userId: userId, exists: exists, columns: rowColumns)
        }
    }

    // MARK: Helpers

    private func rowExists(_ userId: String) async throws -> Bool {
        try await db.getOptional(sql: "SELECT id FROM onboarding WHERE id = ?", parameters: [userId]) { try $0.getString(index: 0) } != nil
    }

    /// Inserts the user's row, or updates the given columns of it.
    private static func upsert(_ tx: any ConnectionContext, userId: String, exists: Bool, columns: [String: Sendable?]) throws {
        let now = Timestamp.format(Date())
        if exists {
            let keys = columns.keys.sorted()
            try tx.execute(
                sql: "UPDATE onboarding SET \(keys.map { "\($0) = ?" }.joined(separator: ", ")), updated_at = ? WHERE id = ?",
                parameters: keys.map { columns[$0]! } + [now, userId]
            )
        } else {
            var row = columns
            row["deferred_steps"] = row["deferred_steps"] ?? "[]"
            row["user_id"] = userId
            row["created_at"] = now
            row["updated_at"] = now
            let keys = row.keys.sorted()
            try tx.execute(
                sql: "INSERT INTO onboarding (id, \(keys.joined(separator: ", "))) VALUES (?, \(keys.map { _ in "?" }.joined(separator: ", ")))",
                parameters: [userId] + keys.map { row[$0]! }
            )
        }
    }

    private static func insert(_ tx: any ConnectionContext, task: PlannedTask, id: String, userId: String) throws {
        let now = Timestamp.format(Date())
        // tags / blocking_rule_ids are Postgres arrays: locally a JSON array.
        try tx.execute(
            sql: """
                INSERT INTO tasks
                    (id, user_id, title, description, task_type, status, recurrence_type, recurrence_config,
                     verification_type, verification_config, tags, sort_order, is_blocking_condition,
                     blocking_rule_ids, available_from, due_at, home_only_blocking, blocking_days_of_week,
                     created_at, updated_at)
                VALUES (?, ?, ?, NULL, ?, 'active', ?, ?, ?, ?, '[]', ?, ?, '[]', ?, ?, 0, '[]', ?, ?)
                """,
            parameters: [
                id, userId, task.title, task.taskType, task.recurrenceType, task.recurrenceConfig.jsonString,
                task.verificationType, task.verificationConfig.jsonString, task.sortOrder, task.isBlockingCondition ? 1 : 0,
                task.availableFrom, task.dueAt, now, now,
            ]
        )
        for (index, step) in task.steps.enumerated() {
            try RoutineRepository.insertStep(
                tx, id: UUID().uuidString.lowercased(), userId: userId, taskId: id, title: step.title, stepOrder: index,
                stepType: step.stepType, config: step.config.jsonString, supersetGroup: nil, description: nil
            )
        }
    }
}
