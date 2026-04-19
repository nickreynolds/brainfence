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
 * Wrapper around PowerSync's SupabaseConnector that correctly handles JSONB
 * columns. The default connector sends all TEXT values as JSON string
 * primitives, which causes Postgres JSONB columns to store the value as a
 * JSONB string type instead of a JSONB object/array.
 *
 * This override parses known JSONB column values into proper JsonElements
 * before upserting so that Postgrest stores them as native JSONB objects.
 */
@Singleton
class SupabasePowerSyncConnector @Inject constructor(
    supabase: SupabaseClient,
    @Named("powerSyncUrl") powerSyncUrl: String,
) : SupabaseConnector(supabase, powerSyncUrl) {

    companion object {
        /** Map of table name → set of columns that are JSONB in Postgres. */
        private val JSONB_COLUMNS = mapOf(
            "tasks" to setOf("recurrence_config", "verification_config"),
            "routine_steps" to setOf("config"),
            "task_completions" to setOf("verification_data"),
            "step_completions" to setOf("data"),
            "blocking_rules" to setOf("blocked_apps", "active_schedule", "pending_changes"),
            "groups" to setOf("visibility_schedule"),
            "notes" to setOf("tags"),
        )
    }

    override suspend fun uploadCrudEntry(entry: CrudEntry) {
        val jsonbCols = JSONB_COLUMNS[entry.table]
        if (jsonbCols == null || entry.opData == null) {
            super.uploadCrudEntry(entry)
            return
        }

        val data = entry.opData!!.jsonValues.toMutableMap()

        // Parse JSONB columns from string primitives into real JSON elements
        for (col in jsonbCols) {
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
