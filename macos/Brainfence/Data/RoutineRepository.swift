import BrainfenceCore
import Foundation
import PowerSync

/// Routine / workout steps and their per-set completions.
/// Port of android/…/data/routine/RoutineRepository.kt.
struct RoutineRepository: Sendable {
    let db: PowerSyncDatabaseProtocol

    static let tables = ["routine_steps"]

    func steps(taskId: String) async throws -> [RoutineStep] {
        try await db.getAll(
            sql: """
                SELECT id, task_id, title, step_order, step_type, config, superset_group, description
                FROM routine_steps WHERE task_id = ? ORDER BY step_order
                """,
            parameters: [taskId]
        ) { c in
            RoutineStep(
                id: try c.getString(index: 0),
                taskId: try c.getString(index: 1),
                title: c.getStringOptional(index: 2) ?? "",
                stepOrder: Int(c.getInt64Optional(index: 3) ?? 0),
                stepType: c.getStringOptional(index: 4) ?? "checkbox",
                config: c.getStringOptional(index: 5) ?? "{}",
                supersetGroup: c.getStringOptional(index: 6),
                description: c.getStringOptional(index: 7)
            )
        }
    }

    /// Step completions from the most recent completion of [taskId], for pre-filling.
    func lastStepCompletions(taskId: String) async throws -> [StepCompletion] {
        try await db.getAll(
            sql: """
                SELECT routine_step_id, set_number, data FROM step_completions
                WHERE task_completion_id = (
                    SELECT id FROM task_completions WHERE task_id = ? ORDER BY completed_at DESC LIMIT 1
                )
                ORDER BY routine_step_id, set_number
                """,
            parameters: [taskId]
        ) { c in
            StepCompletion(
                routineStepId: try c.getString(index: 0),
                setNumber: Int(c.getInt64Optional(index: 1) ?? 1),
                data: c.getStringOptional(index: 2) ?? "{}"
            )
        }
    }

    /// Completes a routine: one task_completion plus one step_completion per
    /// set per step. [stepData] maps step id → per-set JSON.
    func completeRoutine(taskId: String, userId: String, stepData: [String: [String]]) async throws {
        let now = Timestamp.format(Date())
        let completionId = UUID().uuidString.lowercased()
        let proof = JSONValue.object(["method": .string("routine"), "steps": .int(stepData.count)]).jsonString
        try await db.writeTransaction { tx in
            try tx.execute(
                sql: """
                    INSERT INTO task_completions
                        (id, task_id, user_id, completed_at, occurrence_date, verification_data, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                parameters: [completionId, taskId, userId, now, now, proof, now]
            )
            for (stepId, sets) in stepData {
                for (index, data) in sets.enumerated() {
                    try tx.execute(
                        sql: """
                            INSERT INTO step_completions
                                (id, user_id, task_completion_id, routine_step_id, set_number, data, completed_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?)
                            """,
                        parameters: [UUID().uuidString.lowercased(), userId, completionId, stepId, index + 1, data, now]
                    )
                }
            }
        }
    }

    /// Adds a step to the end of a routine from the runner.
    func addStep(taskId: String, userId: String, title: String, stepType: String, config: String, stepOrder: Int) async throws {
        try await db.writeTransaction { tx in
            try Self.insertStep(
                tx, id: UUID().uuidString.lowercased(), userId: userId, taskId: taskId, title: title,
                stepOrder: stepOrder, stepType: stepType, config: config, supersetGroup: nil, description: nil
            )
        }
    }

    func deleteStep(id: String) async throws {
        try await db.execute(sql: "DELETE FROM routine_steps WHERE id = ?", parameters: [id])
    }

    static func insertStep(
        _ tx: any ConnectionContext, id: String, userId: String, taskId: String, title: String, stepOrder: Int,
        stepType: String, config: String, supersetGroup: String?, description: String?
    ) throws {
        try tx.execute(
            sql: """
                INSERT INTO routine_steps
                    (id, user_id, task_id, title, step_order, step_type, config, superset_group, created_at, description)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            parameters: [id, userId, taskId, title, stepOrder, stepType, config, supersetGroup, Timestamp.format(Date()), description]
        )
    }
}

struct ShoppingItem: Sendable, Identifiable, Hashable {
    let id: String
    let title: String
}

struct JournalEntry: Sendable, Identifiable {
    let id: String
    let taskTitle: String
    let text: String
    let completedAt: String
}

/// Shopping list items and journal entries. Port of Android's
/// ShoppingRepository and JournalRepository.
struct ShoppingRepository: Sendable {
    let db: PowerSyncDatabaseProtocol

    static let tables = ["shopping_items"]

    func openItems(taskId: String) async throws -> [ShoppingItem] {
        try await db.getAll(
            sql: """
                SELECT id, title FROM shopping_items
                WHERE task_id = ? AND completed_at IS NULL
                ORDER BY sort_order, created_at
                """,
            parameters: [taskId]
        ) { c in ShoppingItem(id: try c.getString(index: 0), title: c.getStringOptional(index: 1) ?? "") }
    }

    /// Open-item counts for all shopping tasks, keyed by task id.
    func openItemCounts() async throws -> [String: Int] {
        let rows = try await db.getAll(
            sql: "SELECT task_id, COUNT(*) FROM shopping_items WHERE completed_at IS NULL GROUP BY task_id",
            parameters: []
        ) { c in (try c.getString(index: 0), Int(c.getInt64Optional(index: 1) ?? 0)) }
        return Dictionary(rows, uniquingKeysWith: { a, _ in a })
    }

    func addItem(taskId: String, userId: String, title: String) async throws {
        try await db.execute(
            sql: """
                INSERT INTO shopping_items (id, user_id, task_id, title, sort_order, completed_at, created_at)
                VALUES (?, ?, ?, ?, 0, NULL, ?)
                """,
            parameters: [UUID().uuidString.lowercased(), userId, taskId, title, Timestamp.format(Date())]
        )
    }

    /// Checks an item off: it leaves the open list but is kept as history.
    func completeItem(id: String) async throws {
        try await db.execute(sql: "UPDATE shopping_items SET completed_at = ? WHERE id = ?", parameters: [Timestamp.format(Date()), id])
    }

    func uncompleteItem(id: String) async throws {
        try await db.execute(sql: "UPDATE shopping_items SET completed_at = NULL WHERE id = ?", parameters: [id])
    }

    func deleteItem(id: String) async throws {
        try await db.execute(sql: "DELETE FROM shopping_items WHERE id = ?", parameters: [id])
    }

    static let journalTables = ["tasks", "task_completions"]

    func journalEntries() async throws -> [JournalEntry] {
        try await db.getAll(
            sql: """
                SELECT tc.id, t.title, tc.verification_data, tc.completed_at
                FROM task_completions tc JOIN tasks t ON t.id = tc.task_id
                WHERE t.task_type = 'journal'
                ORDER BY tc.completed_at DESC
                """,
            parameters: []
        ) { c in
            JournalEntry(
                id: try c.getString(index: 0),
                taskTitle: c.getStringOptional(index: 1) ?? "Journal",
                text: JSONValue.parse(c.getStringOptional(index: 2))?["text"]?.stringValue ?? "",
                completedAt: c.getStringOptional(index: 3) ?? ""
            )
        }
    }
}

extension PowerSyncDatabaseProtocol {
    /// Emits once now and again after every write to any of [tables]
    /// (PowerSync re-runs a watch on each change without de-duplicating), so
    /// screens that load from several tables can simply reload.
    func changes(of tables: [String]) throws -> AsyncThrowingStream<[Int64], any Error> {
        let sql = "SELECT " + tables.map { "(SELECT COUNT(*) FROM \($0))" }.joined(separator: " + ") + " AS n"
        return try watch(sql: sql, parameters: [], mapper: { c in try c.getInt64(index: 0) })
    }
}
