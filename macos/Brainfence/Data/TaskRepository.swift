import BrainfenceCore
import Foundation
import PowerSync

/// Active tasks with their completion state for the current occurrence.
/// Same query as android/…/data/task/TaskRepository.kt.
struct TaskRepository: Sendable {
    let db: PowerSyncDatabaseProtocol

    private static let activeTasksSQL = """
        SELECT
            t.id, t.user_id, t.title, t.description, t.task_type, t.status,
            t.recurrence_type, t.recurrence_config, t.verification_type, t.verification_config,
            t.group_id, t.sort_order, t.is_blocking_condition,
            t.available_from, t.due_at, t.home_only_blocking, t.blocking_days_of_week,
            t.created_at, t.updated_at,
            CASE WHEN COUNT(tc.id) > 0 THEN 1 ELSE 0 END AS completed_today,
            (SELECT MAX(tc2.completed_at) FROM task_completions tc2 WHERE tc2.task_id = t.id) AS last_completion_at
        FROM tasks t
        LEFT JOIN task_completions tc
            ON tc.task_id = t.id
            AND date(tc.completed_at, 'localtime') = date('now', 'localtime')
        WHERE t.status = 'active'
        GROUP BY t.id
        ORDER BY t.sort_order, t.created_at
        """

    /// Emits on every change to `tasks` or `task_completions`. Note that
    /// "today" is evaluated when the query runs, so callers must also re-query
    /// periodically to notice the date rolling over.
    func watchActiveTasks() throws -> AsyncThrowingStream<[BrainTask], any Error> {
        try db.watch(sql: Self.activeTasksSQL, parameters: [], mapper: Self.map)
    }

    func activeTasks() async throws -> [BrainTask] {
        try await db.getAll(sql: Self.activeTasksSQL, parameters: [], mapper: Self.map)
    }

    /// The columns the task editor reads, for any status.
    func taskRow(id: String) async throws -> TaskRow? {
        try await db.getOptional(
            sql: """
                SELECT title, description, task_type, recurrence_type, recurrence_config,
                       verification_type, verification_config, is_blocking_condition,
                       home_only_blocking, blocking_days_of_week, available_from, due_at
                FROM tasks WHERE id = ?
                """,
            parameters: [id]
        ) { c in
            TaskRow(
                title: try c.getStringOptional(name: "title") ?? "",
                description: try c.getStringOptional(name: "description"),
                taskType: try c.getStringOptional(name: "task_type") ?? "simple",
                recurrenceType: try c.getStringOptional(name: "recurrence_type"),
                recurrenceConfig: try c.getStringOptional(name: "recurrence_config"),
                verificationType: try c.getStringOptional(name: "verification_type"),
                verificationConfig: try c.getStringOptional(name: "verification_config"),
                isBlockingCondition: (try c.getInt64Optional(name: "is_blocking_condition") ?? 0) != 0,
                homeOnlyBlocking: (try c.getInt64Optional(name: "home_only_blocking") ?? 0) != 0,
                blockingDaysOfWeek: try c.getStringOptional(name: "blocking_days_of_week"),
                availableFrom: try c.getStringOptional(name: "available_from"),
                dueAt: try c.getStringOptional(name: "due_at")
            )
        }
    }

    /// Inserts or updates a task from the editor, diff-syncing its routine
    /// steps in place (insert new, update existing, delete removed): step
    /// completions reference steps by id, so keeping ids keeps the history.
    /// Returns the task id.
    func save(_ w: TaskWrite, taskId existing: String?, userId: String) async throws -> String {
        let taskId = existing ?? UUID().uuidString.lowercased()
        let isNew = existing == nil
        let now = Timestamp.format(Date())
        let existingStepIds: Set<String>
        if isNew {
            existingStepIds = []
        } else {
            existingStepIds = Set(try await db.getAll(
                sql: "SELECT id FROM routine_steps WHERE task_id = ?",
                parameters: [taskId]
            ) { try $0.getString(index: 0) })
        }

        try await db.writeTransaction { tx in
            if isNew {
                // tags / blocking_rule_ids are Postgres arrays: locally a JSON
                // array, which the connector uploads as a real array.
                try tx.execute(
                    sql: """
                        INSERT INTO tasks
                            (id, user_id, title, description, task_type, status, recurrence_type, recurrence_config,
                             verification_type, verification_config, tags, sort_order, is_blocking_condition,
                             blocking_rule_ids, available_from, due_at, home_only_blocking, blocking_days_of_week,
                             created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, 'active', ?, ?, ?, ?, '[]', 0, ?, '[]', ?, ?, ?, ?, ?, ?)
                        """,
                    parameters: [
                        taskId, userId, w.title, w.description, w.taskType, w.recurrenceType, w.recurrenceConfig,
                        w.verificationType, w.verificationConfig, w.isBlockingCondition ? 1 : 0,
                        w.availableFrom, w.dueAt, w.homeOnlyBlocking ? 1 : 0, w.blockingDaysOfWeek, now, now,
                    ]
                )
            } else {
                try tx.execute(
                    sql: """
                        UPDATE tasks SET
                            title = ?, description = ?, task_type = ?, recurrence_type = ?, recurrence_config = ?,
                            verification_type = ?, verification_config = ?, is_blocking_condition = ?,
                            available_from = ?, due_at = ?, home_only_blocking = ?, blocking_days_of_week = ?,
                            updated_at = ?
                        WHERE id = ?
                        """,
                    parameters: [
                        w.title, w.description, w.taskType, w.recurrenceType, w.recurrenceConfig,
                        w.verificationType, w.verificationConfig, w.isBlockingCondition ? 1 : 0,
                        w.availableFrom, w.dueAt, w.homeOnlyBlocking ? 1 : 0, w.blockingDaysOfWeek,
                        now, taskId,
                    ]
                )
            }
            guard let steps = w.routineSteps else { return }
            let keep = Set(steps.map { $0.id })
            for id in existingStepIds where !keep.contains(id) {
                try tx.execute(sql: "DELETE FROM routine_steps WHERE id = ?", parameters: [id])
            }
            for (index, step) in steps.enumerated() {
                if existingStepIds.contains(step.id) {
                    try tx.execute(
                        sql: """
                            UPDATE routine_steps SET title = ?, step_order = ?, step_type = ?, config = ?,
                                superset_group = ?, description = ?
                            WHERE id = ?
                            """,
                        parameters: [step.title, index, step.stepType, step.config, step.supersetGroup, step.description, step.id]
                    )
                } else {
                    try RoutineRepository.insertStep(
                        tx, id: step.id, userId: userId, taskId: taskId, title: step.title, stepOrder: index,
                        stepType: step.stepType, config: step.config, supersetGroup: step.supersetGroup,
                        description: step.description
                    )
                }
            }
        }
        return taskId
    }

    @Sendable
    private static func map(_ c: SqlCursor) throws -> BrainTask {
        let recurrenceType = try c.getStringOptional(name: "recurrence_type")
        let lastCompletionAt = try c.getStringOptional(name: "last_completion_at")
        // completed_today only counts completions dated today. Right for
        // recurring tasks, wrong for one-offs: a one-off has a single
        // occurrence, so once ever completed it stays done (and must not
        // re-block tomorrow). Mirrors the recurrence engine's one-off rule.
        let completedToday = recurrenceType == nil
            ? lastCompletionAt != nil
            : (try c.getInt64Optional(name: "completed_today") ?? 0) != 0

        return BrainTask(
            id: try c.getString(name: "id"),
            userId: try c.getStringOptional(name: "user_id") ?? "",
            title: try c.getStringOptional(name: "title") ?? "",
            description: try c.getStringOptional(name: "description"),
            taskType: try c.getStringOptional(name: "task_type") ?? "simple",
            status: try c.getStringOptional(name: "status") ?? "active",
            recurrenceType: recurrenceType,
            recurrenceConfig: try c.getStringOptional(name: "recurrence_config") ?? "{}",
            verificationType: try c.getStringOptional(name: "verification_type"),
            verificationConfig: try c.getStringOptional(name: "verification_config") ?? "{}",
            groupId: try c.getStringOptional(name: "group_id"),
            sortOrder: Int(try c.getInt64Optional(name: "sort_order") ?? 0),
            isBlockingCondition: (try c.getInt64Optional(name: "is_blocking_condition") ?? 0) != 0,
            availableFrom: try c.getStringOptional(name: "available_from"),
            dueAt: try c.getStringOptional(name: "due_at"),
            homeOnlyBlocking: (try c.getInt64Optional(name: "home_only_blocking") ?? 0) != 0,
            blockingDaysOfWeek: try c.getStringOptional(name: "blocking_days_of_week") ?? "[]",
            createdAt: try c.getStringOptional(name: "created_at") ?? "",
            updatedAt: try c.getStringOptional(name: "updated_at") ?? "",
            completedToday: completedToday,
            lastCompletionAt: lastCompletionAt
        )
    }
}

/// One completion of a task, for history lists.
struct CompletionRecord: Sendable, Identifiable {
    let id: String
    let completedAt: String
    let verificationData: String
}

struct CompletionRepository: Sendable {
    let db: PowerSyncDatabaseProtocol

    /// Most recent completions of a task, newest first.
    func history(taskId: String, limit: Int = 14) async throws -> [CompletionRecord] {
        try await db.getAll(
            sql: "SELECT id, completed_at, verification_data FROM task_completions WHERE task_id = ? ORDER BY completed_at DESC LIMIT ?",
            parameters: [taskId, limit]
        ) { c in
            CompletionRecord(
                id: try c.getString(index: 0),
                completedAt: c.getStringOptional(index: 1) ?? "",
                verificationData: c.getStringOptional(index: 2) ?? "{}"
            )
        }
    }

    /// verification_data of today's completion of [taskId], or nil if none.
    func todaysVerificationData(taskId: String) async throws -> String? {
        try await db.getOptional(
            sql: """
                SELECT verification_data FROM task_completions
                WHERE task_id = ? AND date(completed_at, 'localtime') = date('now', 'localtime')
                ORDER BY completed_at DESC LIMIT 1
                """,
            parameters: [taskId]
        ) { c in c.getStringOptional(index: 0) ?? "{}" }
    }

    /// Replaces the proof data on today's completion of [taskId].
    func updateTodaysVerificationData(taskId: String, data: String) async throws {
        try await db.execute(
            sql: """
                UPDATE task_completions SET verification_data = ?
                WHERE id = (
                    SELECT id FROM task_completions
                    WHERE task_id = ? AND date(completed_at, 'localtime') = date('now', 'localtime')
                    ORDER BY completed_at DESC LIMIT 1
                )
                """,
            parameters: [data, taskId]
        )
    }

    /// Records a completion locally; PowerSync uploads it.
    ///
    /// Idempotent per occurrence, exactly like Android: at most one completion
    /// per one-off task (ever) and per recurring task per local day, enforced
    /// atomically by INSERT … WHERE NOT EXISTS.
    func completeTask(
        taskId: String,
        userId: String,
        verificationData: String = "{}",
        now: Date = Date()
    ) async throws {
        let timestamp = Timestamp.format(now)
        try await db.execute(
            sql: """
                INSERT INTO task_completions
                    (id, task_id, user_id, completed_at, occurrence_date, verification_data, created_at)
                SELECT ?, ?, ?, ?, ?, ?, ?
                WHERE NOT EXISTS (
                    SELECT 1 FROM task_completions existing
                    WHERE existing.task_id = ?
                      AND (
                        (SELECT t.recurrence_type FROM tasks t WHERE t.id = ?) IS NULL
                        OR date(existing.completed_at, 'localtime') = date('now', 'localtime')
                      )
                )
                """,
            parameters: [
                UUID().uuidString.lowercased(), taskId, userId, timestamp, timestamp, verificationData, timestamp,
                taskId, taskId,
            ]
        )
    }
}
