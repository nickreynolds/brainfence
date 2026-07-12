package dev.brainfence.data.db

import com.powersync.connector.supabase.SupabaseConnector
import com.powersync.db.crud.CrudEntry
import com.powersync.db.crud.UpdateType
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Wrapper around PowerSync's SupabaseConnector that correctly handles columns
 * that PowerSync stores locally as JSON strings but which are a structured type
 * in Postgres — both JSONB columns and native array columns (TEXT[] / UUID[]).
 *
 * The default connector sends all local values as JSON string primitives. For a
 * JSONB column that stores the value as a JSONB *string* instead of an
 * object/array; for a TEXT[]/UUID[] column Postgres rejects the malformed array
 * literal outright, which makes PowerSync roll the local write back (e.g. a
 * blocking rule's condition_task_ids silently reverting after a save).
 *
 * This override parses those column values into proper JsonElements before
 * upserting so Postgrest stores them as native JSONB objects / Postgres arrays.
 */
@Singleton
class SupabasePowerSyncConnector @Inject constructor(
    supabase: SupabaseClient,
    @Named("powerSyncUrl") powerSyncUrl: String,
) : SupabaseConnector(supabase, powerSyncUrl) {

    companion object {
        /**
         * Map of table name → columns that must be parsed from their local JSON
         * string into a real JSON element before upload. Covers every Postgres
         * JSONB column and every native array column (TEXT[] / UUID[]); omitting
         * an array column causes its writes to be rejected and reverted.
         */
        private val JSON_ENCODED_COLUMNS = mapOf(
            "tasks" to setOf(
                "recurrence_config", "verification_config", "blocking_days_of_week",
                "tags", "blocking_rule_ids",
            ),
            "routine_steps" to setOf("config"),
            "task_completions" to setOf("verification_data"),
            "step_completions" to setOf("data"),
            "blocking_rules" to setOf(
                "blocked_apps", "pending_changes",
                "blocked_domains", "condition_task_ids",
            ),
            "groups" to setOf("visibility_schedule"),
            "notes" to setOf("tags", "outgoing_links"),
        )
    }

    override suspend fun uploadCrudEntry(entry: CrudEntry) {
        val jsonEncodedCols = JSON_ENCODED_COLUMNS[entry.table]
        if (jsonEncodedCols == null || entry.opData == null) {
            super.uploadCrudEntry(entry)
            return
        }

        val data = entry.opData!!.jsonValues.toMutableMap()

        // Parse JSON-encoded columns from string primitives into real JSON elements
        for (col in jsonEncodedCols) {
            val value = data[col]
            if (value is JsonPrimitive && value.isString) {
                try {
                    data[col] = Json.parseToJsonElement(value.content)
                } catch (_: Exception) {
                    // Not valid JSON — leave as-is
                }
            }
        }

        val table = supabaseClient.from(entry.table)
        when (entry.op) {
            UpdateType.PUT -> {
                val upsertData = buildMap {
                    put("id", JsonPrimitive(entry.id))
                    putAll(data)
                }
                table.upsert(upsertData)
            }
            UpdateType.PATCH -> {
                table.update(data) {
                    filter { eq("id", entry.id) }
                }
            }
            UpdateType.DELETE -> {
                table.delete {
                    filter { eq("id", entry.id) }
                }
            }
        }
    }
}
