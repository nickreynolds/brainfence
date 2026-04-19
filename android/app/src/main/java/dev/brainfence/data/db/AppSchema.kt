package dev.brainfence.data.db

import com.powersync.db.schema.Column
import com.powersync.db.schema.Schema
import com.powersync.db.schema.Table

/**
 * PowerSync local SQLite schema — mirrors the Supabase Postgres tables.
 *
 * Rules:
 *  - `id` is implicit (managed by PowerSync, do not declare it)
 *  - JSONB / arrays → Column.text (stored as JSON strings)
 *  - BOOLEAN        → Column.integer (0 / 1)
 *  - TIMESTAMPTZ    → Column.text (ISO-8601 string)
 *  - UUID refs      → Column.text
 */
val AppSchema = Schema(
    Table(
        name = "groups",
        columns = listOf(
            Column.text("user_id"),
            Column.text("name"),
            Column.text("color"),
            Column.text("icon"),
            Column.text("visibility_schedule"),   // JSONB
            Column.integer("sort_order"),
            Column.text("created_at"),
        ),
    ),
    Table(
        name = "tasks",
        columns = listOf(
            Column.text("user_id"),
            Column.text("title"),
            Column.text("description"),
            Column.text("task_type"),
            Column.text("status"),
            Column.text("recurrence_type"),
            Column.text("recurrence_config"),     // JSONB
            Column.text("verification_type"),
            Column.text("verification_config"),   // JSONB
            Column.text("tags"),                  // TEXT[] as JSON array
            Column.text("group_id"),
            Column.integer("sort_order"),
            Column.integer("is_blocking_condition"),
            Column.text("blocking_rule_ids"),     // UUID[] as JSON array
            Column.text("available_from"),        // HH:MM — when task becomes completable
            Column.text("due_at"),                // HH:MM — when task becomes overdue / triggers blocking
            Column.text("created_at"),
            Column.text("updated_at"),
        ),
    ),
    Table(
        name = "routine_steps",
        columns = listOf(
            Column.text("user_id"),
            Column.text("task_id"),
            Column.text("title"),
            Column.integer("step_order"),
            Column.text("step_type"),
            Column.text("config"),               // JSONB
            Column.text("superset_group"),
            Column.text("created_at"),
        ),
    ),
    Table(
        name = "task_completions",
        columns = listOf(
            Column.text("task_id"),
            Column.text("user_id"),
            Column.text("completed_at"),
            Column.text("occurrence_date"),
            Column.text("verification_data"),    // JSONB
            Column.text("created_at"),
        ),
    ),
    Table(
        name = "step_completions",
        columns = listOf(
            Column.text("user_id"),
            Column.text("task_completion_id"),
            Column.text("routine_step_id"),
            Column.integer("set_number"),
            Column.text("data"),                 // JSONB
            Column.text("completed_at"),
        ),
    ),
    Table(
        name = "blocking_rules",
        columns = listOf(
            Column.text("user_id"),
            Column.text("name"),
            Column.text("blocked_apps"),         // JSONB
            Column.text("blocked_domains"),      // TEXT[] as JSON array
            Column.text("condition_task_ids"),   // UUID[] as JSON array
            Column.text("condition_logic"),
            Column.integer("config_lock_hours"),
            Column.text("pending_changes"),      // JSONB
            Column.text("changes_apply_at"),
            Column.integer("is_active"),
            Column.text("created_at"),
            Column.text("updated_at"),
        ),
    ),
    Table(
        name = "notes",
        columns = listOf(
            Column.text("user_id"),
            Column.text("title"),
            Column.text("content"),
            Column.text("tags"),                 // TEXT[] as JSON array
            Column.text("group_id"),
            Column.text("outgoing_links"),       // UUID[] as JSON array
            Column.text("created_at"),
            Column.text("updated_at"),
        ),
    ),
)
