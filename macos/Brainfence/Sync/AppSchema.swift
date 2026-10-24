import PowerSync

/// PowerSync local SQLite schema — mirrors the Supabase Postgres tables and
/// android/…/data/db/AppSchema.kt. Keep the two in step when migrating.
///
/// - `id` is implicit (managed by PowerSync, never declared)
/// - JSONB / arrays → text (JSON). Every such column must also be listed in
///   `UploadNormalizer.jsonColumns`, or uploads are rejected and reverted.
/// - BOOLEAN → integer (0 / 1), listed in `UploadNormalizer.booleanColumns`
/// - TIMESTAMPTZ / UUID → text
let AppSchema = Schema(
    Table(name: "groups", columns: [
        .text("user_id"),
        .text("name"),
        .text("color"),
        .text("icon"),
        .text("visibility_schedule"),   // JSONB
        .integer("sort_order"),
        .text("created_at"),
    ]),
    Table(name: "tasks", columns: [
        .text("user_id"),
        .text("title"),
        .text("description"),
        .text("task_type"),
        .text("status"),
        .text("recurrence_type"),
        .text("recurrence_config"),     // JSONB
        .text("verification_type"),
        .text("verification_config"),   // JSONB
        .text("tags"),                  // TEXT[]
        .text("group_id"),
        .integer("sort_order"),
        .integer("is_blocking_condition"),
        .text("blocking_rule_ids"),     // UUID[]
        .text("available_from"),        // HH:MM
        .text("due_at"),                // HH:MM
        .integer("home_only_blocking"),
        .text("blocking_days_of_week"), // JSONB array of "mon"…"sun"
        .text("created_at"),
        .text("updated_at"),
    ]),
    Table(name: "routine_steps", columns: [
        .text("user_id"),
        .text("task_id"),
        .text("title"),
        .integer("step_order"),
        .text("step_type"),
        .text("config"),                // JSONB
        .text("superset_group"),
        .text("description"),
        .text("created_at"),
    ]),
    Table(name: "shopping_items", columns: [
        .text("user_id"),
        .text("task_id"),
        .text("title"),
        .integer("sort_order"),
        .text("completed_at"),
        .text("created_at"),
    ]),
    Table(name: "task_completions", columns: [
        .text("task_id"),
        .text("user_id"),
        .text("completed_at"),
        .text("occurrence_date"),
        .text("verification_data"),     // JSONB
        .text("created_at"),
    ]),
    Table(name: "step_completions", columns: [
        .text("user_id"),
        .text("task_completion_id"),
        .text("routine_step_id"),
        .integer("set_number"),
        .text("data"),                  // JSONB
        .text("completed_at"),
    ]),
    Table(name: "blocking_rules", columns: [
        .text("user_id"),
        .text("name"),
        .text("blocked_apps"),          // JSONB
        .text("blocked_domains"),       // TEXT[]
        .text("condition_task_ids"),    // UUID[]
        .text("condition_logic"),
        .integer("config_lock_hours"),
        .text("pending_changes"),       // JSONB
        .text("changes_apply_at"),
        .integer("is_active"),
        .text("created_at"),
        .text("updated_at"),
    ]),
    Table(name: "contemplation_decks", columns: [
        .text("user_id"),
        .text("name"),
        .text("description"),
        .text("ordering"),
        .text("response_type"),
        .text("response_label"),
        .integer("weight"),
        .integer("is_enabled"),
        .text("library_key"),
        .integer("sort_order"),
        .text("created_at"),
        .text("updated_at"),
    ]),
    Table(name: "contemplations", columns: [
        .text("user_id"),
        .text("deck_id"),
        .text("title"),
        .text("body"),
        .text("attribution"),
        .integer("sort_order"),
        .text("created_at"),
        .text("updated_at"),
    ]),
    Table(name: "contemplation_deliveries", columns: [
        .text("user_id"),
        .text("deck_id"),
        .text("contemplation_id"),
        .text("title"),
        .text("body"),
        .text("attribution"),
        .text("delivered_at"),
        .text("response"),
        .text("responded_at"),
        .text("created_at"),
    ]),
    Table(name: "metrics", columns: [
        .text("user_id"),
        .text("name"),
        .text("description"),
        .text("kind"),
        .text("created_at"),
        .text("updated_at"),
    ]),
    Table(name: "metric_options", columns: [
        .text("user_id"),
        .text("metric_id"),
        .text("label"),
        .integer("sort_order"),
        .text("created_at"),
        .text("updated_at"),
    ]),
    Table(name: "reports", columns: [
        .text("user_id"),
        .text("name"),
        .integer("include_note"),
        .text("library_key"),
        .text("created_at"),
        .text("updated_at"),
    ]),
    Table(name: "report_metrics", columns: [
        .text("user_id"),
        .text("report_id"),
        .text("metric_id"),
        .integer("sort_order"),
        .text("created_at"),
    ]),
    Table(name: "report_submissions", columns: [
        .text("user_id"),
        .text("report_id"),
        .text("task_id"),
        .text("routine_step_id"),
        .text("report_name"),
        .text("note"),
        .text("submitted_at"),
        .text("created_at"),
    ]),
    Table(name: "metric_entries", columns: [
        .text("user_id"),
        .text("submission_id"),
        .text("metric_id"),
        .text("option_id"),
        .text("metric_name"),
        .text("metric_kind"),
        .text("option_label"),
        .integer("option_index"),
        .integer("option_count"),
        .integer("position"),
        .text("recorded_at"),
        .text("created_at"),
    ]),
    Table(name: "notes", columns: [
        .text("user_id"),
        .text("title"),
        .text("content"),
        .text("tags"),                  // TEXT[]
        .text("group_id"),
        .text("outgoing_links"),        // UUID[]
        .text("created_at"),
        .text("updated_at"),
    ])
)
