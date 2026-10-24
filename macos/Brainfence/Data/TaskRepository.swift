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

struct CompletionRepository: Sendable {
    let db: PowerSyncDatabaseProtocol

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
