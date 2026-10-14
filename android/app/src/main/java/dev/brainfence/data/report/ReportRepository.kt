package dev.brainfence.data.report

import com.powersync.PowerSyncDatabase
import com.powersync.db.SqlCursor
import dev.brainfence.data.auth.SessionRepository
import dev.brainfence.domain.model.Metric
import dev.brainfence.domain.model.MetricEntry
import dev.brainfence.domain.model.MetricKind
import dev.brainfence.domain.model.MetricOption
import dev.brainfence.domain.model.Report
import dev.brainfence.domain.model.ReportSubmission
import dev.brainfence.domain.report.buildMetricEntries
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** An option being edited: [id] null = new. */
data class OptionDraft(val id: String?, val label: String)

private val DEFINITION_TABLES = setOf("metrics", "metric_options", "reports", "report_metrics")
private val SUBMISSION_TABLES = setOf("report_submissions", "metric_entries")

private const val TODAY = "date(s.submitted_at, 'localtime') = date('now', 'localtime')"

@Singleton
class ReportRepository @Inject constructor(
    private val database: PowerSyncDatabase,
    private val sessionRepository: SessionRepository,
) {
    // ── Definitions ──────────────────────────────────────────────────

    @OptIn(ExperimentalCoroutinesApi::class)
    fun watchReports(): Flow<List<Report>> =
        database.onChange(tables = DEFINITION_TABLES)
            .onStart { emit(emptySet()) }
            .flatMapLatest { flow { emit(getReports()) } }

    fun watchReport(reportId: String): Flow<Report?> =
        watchReports().map { reports -> reports.firstOrNull { it.id == reportId } }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun watchMetrics(): Flow<List<Metric>> =
        database.onChange(tables = DEFINITION_TABLES)
            .onStart { emit(emptySet()) }
            .flatMapLatest { flow { emit(getMetrics().values.sortedBy { it.name.lowercase() }) } }

    fun watchMetric(metricId: String): Flow<Metric?> =
        watchMetrics().map { metrics -> metrics.firstOrNull { it.id == metricId } }

    suspend fun getReport(reportId: String): Report? = getReports().firstOrNull { it.id == reportId }

    private suspend fun getMetrics(): Map<String, Metric> {
        val options = database.getAll(
            sql = "SELECT id, metric_id, label, sort_order FROM metric_options ORDER BY sort_order, created_at",
        ) { c: SqlCursor ->
            MetricOption(c.getString(0)!!, c.getString(1)!!, c.getString(2) ?: "", (c.getLong(3) ?: 0L).toInt())
        }.groupBy { it.metricId }
        return database.getAll(
            sql = "SELECT id, name, description, kind FROM metrics ORDER BY created_at",
        ) { c: SqlCursor ->
            val id = c.getString(0)!!
            Metric(
                id = id,
                name = c.getString(1) ?: "",
                description = c.getString(2),
                kind = MetricKind.from(c.getString(3)),
                options = options[id].orEmpty(),
            )
        }.associateBy { it.id }
    }

    private suspend fun getReports(): List<Report> {
        val metrics = getMetrics()
        val membership = database.getAll(
            sql = "SELECT report_id, metric_id FROM report_metrics ORDER BY sort_order, created_at",
        ) { c: SqlCursor -> c.getString(0)!! to c.getString(1)!! }
            .groupBy({ it.first }, { it.second })
        return database.getAll(
            sql = "SELECT id, name, include_note, library_key FROM reports ORDER BY created_at",
        ) { c: SqlCursor ->
            val id = c.getString(0)!!
            Report(
                id = id,
                name = c.getString(1) ?: "",
                includeNote = (c.getLong(2) ?: 1L) != 0L,
                libraryKey = c.getString(3),
                metrics = membership[id].orEmpty().mapNotNull { metrics[it] },
            )
        }
    }

    suspend fun createReport(name: String): String {
        val userId = requireUserId()
        val now = Instant.now().toString()
        val id = UUID.randomUUID().toString()
        database.execute(
            sql = """
                INSERT INTO reports (id, user_id, name, include_note, library_key, created_at, updated_at)
                VALUES (?, ?, ?, 1, NULL, ?, ?)
            """.trimIndent(),
            parameters = listOf(id, userId, name, now, now),
        )
        return id
    }

    suspend fun updateReport(reportId: String, name: String, includeNote: Boolean) {
        database.execute(
            sql = "UPDATE reports SET name = ?, include_note = ?, updated_at = ? WHERE id = ?",
            parameters = listOf(name, if (includeNote) 1L else 0L, Instant.now().toString(), reportId),
        )
    }

    /**
     * Deletes a report and its metric list (metrics themselves are shared and
     * kept). Membership rows are deleted explicitly because the server-side
     * cascade doesn't apply to the local SQLite views. Past submissions stay.
     */
    suspend fun deleteReport(reportId: String) {
        database.writeTransaction { tx ->
            tx.execute(sql = "DELETE FROM report_metrics WHERE report_id = ?", parameters = listOf(reportId))
            tx.execute(sql = "DELETE FROM reports WHERE id = ?", parameters = listOf(reportId))
        }
    }

    suspend fun addMetricToReport(reportId: String, metricId: String) {
        val userId = requireUserId()
        database.execute(
            sql = """
                INSERT INTO report_metrics (id, user_id, report_id, metric_id, sort_order, created_at)
                SELECT ?, ?, ?, ?,
                    (SELECT COALESCE(MAX(sort_order), -1) + 1 FROM report_metrics WHERE report_id = ?), ?
                WHERE NOT EXISTS (SELECT 1 FROM report_metrics WHERE report_id = ? AND metric_id = ?)
            """.trimIndent(),
            parameters = listOf(
                UUID.randomUUID().toString(), userId, reportId, metricId, reportId,
                Instant.now().toString(), reportId, metricId,
            ),
        )
    }

    suspend fun removeMetricFromReport(reportId: String, metricId: String) {
        database.execute(
            sql = "DELETE FROM report_metrics WHERE report_id = ? AND metric_id = ?",
            parameters = listOf(reportId, metricId),
        )
    }

    /** Move a metric up ([delta] = -1) or down (+1) within a report. */
    suspend fun moveMetricInReport(reportId: String, metricId: String, delta: Int) {
        database.writeTransaction { tx ->
            val ids = tx.getAll(
                sql = "SELECT id, metric_id FROM report_metrics WHERE report_id = ? ORDER BY sort_order, created_at",
                parameters = listOf(reportId),
            ) { c: SqlCursor -> c.getString(0)!! to c.getString(1)!! }.toMutableList()
            val from = ids.indexOfFirst { it.second == metricId }
            val to = from + delta
            if (from < 0 || to !in ids.indices) return@writeTransaction
            ids.add(to, ids.removeAt(from))
            ids.forEachIndexed { index, (rowId, _) ->
                tx.execute(
                    sql = "UPDATE report_metrics SET sort_order = ? WHERE id = ?",
                    parameters = listOf(index.toLong(), rowId),
                )
            }
        }
    }

    suspend fun createMetric(name: String, description: String?, kind: MetricKind, labels: List<String>): String {
        val userId = requireUserId()
        val metricId = UUID.randomUUID().toString()
        database.writeTransaction { tx ->
            val now = Instant.now().toString()
            tx.execute(
                sql = """
                    INSERT INTO metrics (id, user_id, name, description, kind, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                parameters = listOf(metricId, userId, name, description, kind.key, now, now),
            )
            labels.forEachIndexed { index, label ->
                tx.execute(
                    sql = """
                        INSERT INTO metric_options (id, user_id, metric_id, label, sort_order, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    parameters = listOf(UUID.randomUUID().toString(), userId, metricId, label, index.toLong(), now, now),
                )
            }
        }
        return metricId
    }

    /**
     * Saves a metric and its options in [options] order. Existing options keep
     * their ids (so history stays linked through renames and reorders); options
     * missing from [options] are deleted.
     */
    suspend fun saveMetric(
        metricId: String,
        name: String,
        description: String?,
        kind: MetricKind,
        options: List<OptionDraft>,
    ) {
        val userId = requireUserId()
        database.writeTransaction { tx ->
            val now = Instant.now().toString()
            tx.execute(
                sql = "UPDATE metrics SET name = ?, description = ?, kind = ?, updated_at = ? WHERE id = ?",
                parameters = listOf(name, description, kind.key, now, metricId),
            )
            val existing = tx.getAll(
                sql = "SELECT id FROM metric_options WHERE metric_id = ?",
                parameters = listOf(metricId),
            ) { c: SqlCursor -> c.getString(0)!! }.toSet()
            val keep = options.mapNotNull { it.id }.toSet()
            for (id in existing - keep) {
                tx.execute(sql = "DELETE FROM metric_options WHERE id = ?", parameters = listOf(id))
            }
            options.forEachIndexed { index, option ->
                if (option.id != null && option.id in existing) {
                    tx.execute(
                        sql = "UPDATE metric_options SET label = ?, sort_order = ?, updated_at = ? WHERE id = ?",
                        parameters = listOf(option.label, index.toLong(), now, option.id),
                    )
                } else {
                    tx.execute(
                        sql = """
                            INSERT INTO metric_options (id, user_id, metric_id, label, sort_order, created_at, updated_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                        parameters = listOf(
                            UUID.randomUUID().toString(), userId, metricId, option.label, index.toLong(), now, now,
                        ),
                    )
                }
            }
        }
    }

    /** Deletes a metric everywhere. Recorded answers keep their snapshots. */
    suspend fun deleteMetric(metricId: String) {
        database.writeTransaction { tx ->
            tx.execute(sql = "DELETE FROM report_metrics WHERE metric_id = ?", parameters = listOf(metricId))
            tx.execute(sql = "DELETE FROM metric_options WHERE metric_id = ?", parameters = listOf(metricId))
            tx.execute(sql = "DELETE FROM metrics WHERE id = ?", parameters = listOf(metricId))
        }
    }

    /** Copies a starter report and its metrics into the user's tables. */
    suspend fun addFromLibrary(starter: ReportLibrary.StarterReport): String {
        val metricIds = starter.metrics.map { createMetric(it.name, it.description, it.kind, it.options) }
        val userId = requireUserId()
        val now = Instant.now().toString()
        val reportId = UUID.randomUUID().toString()
        database.writeTransaction { tx ->
            tx.execute(
                sql = """
                    INSERT INTO reports (id, user_id, name, include_note, library_key, created_at, updated_at)
                    VALUES (?, ?, ?, 1, ?, ?, ?)
                """.trimIndent(),
                parameters = listOf(reportId, userId, starter.name, starter.key, now, now),
            )
            metricIds.forEachIndexed { index, metricId ->
                tx.execute(
                    sql = """
                        INSERT INTO report_metrics (id, user_id, report_id, metric_id, sort_order, created_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    parameters = listOf(UUID.randomUUID().toString(), userId, reportId, metricId, index.toLong(), now),
                )
            }
        }
        return reportId
    }

    // ── Submissions ──────────────────────────────────────────────────

    /**
     * Records a filled-in report: one submission plus an entry per answer.
     * [answers] maps metric id → option id and must cover every metric.
     *
     * @param routineStepId set when filled in as a step of the routine [taskId].
     */
    suspend fun submit(
        report: Report,
        answers: Map<String, String>,
        note: String?,
        taskId: String?,
        routineStepId: String?,
    ): String {
        val entries = buildMetricEntries(report, answers)
        val userId = requireUserId()
        val now = Instant.now().toString()
        val submissionId = UUID.randomUUID().toString()
        database.writeTransaction { tx ->
            tx.execute(
                sql = """
                    INSERT INTO report_submissions
                        (id, user_id, report_id, task_id, routine_step_id, report_name, note, submitted_at, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                parameters = listOf(
                    submissionId, userId, report.id, taskId, routineStepId, report.name,
                    note?.trim()?.ifBlank { null }, now, now,
                ),
            )
            entries.forEachIndexed { position, entry ->
                tx.execute(
                    sql = """
                        INSERT INTO metric_entries
                            (id, user_id, submission_id, metric_id, option_id, metric_name, metric_kind,
                             option_label, option_index, option_count, position, recorded_at, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    parameters = listOf(
                        UUID.randomUUID().toString(), userId, submissionId, entry.metricId, entry.optionId,
                        entry.metricName, entry.metricKind.key, entry.optionLabel,
                        entry.optionIndex.toLong(), entry.optionCount.toLong(), position.toLong(), now, now,
                    ),
                )
            }
        }
        return submissionId
    }

    /** Today's submission from the report task [taskId] (not from routine steps), if any. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun watchTodaysTaskSubmission(taskId: String): Flow<ReportSubmission?> =
        database.onChange(tables = SUBMISSION_TABLES)
            .onStart { emit(emptySet()) }
            .flatMapLatest {
                flow {
                    emit(getSubmissions("s.task_id = ? AND s.routine_step_id IS NULL AND $TODAY", listOf(taskId)).firstOrNull())
                }
            }

    /** Today's submissions from the report steps of routine [taskId], keyed by step id. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun watchTodaysRoutineSubmissions(taskId: String): Flow<Map<String, ReportSubmission>> =
        database.onChange(tables = SUBMISSION_TABLES)
            .onStart { emit(emptySet()) }
            .flatMapLatest {
                flow {
                    val submissions = getSubmissions("s.task_id = ? AND s.routine_step_id IS NOT NULL AND $TODAY", listOf(taskId))
                    // Newest first, so the latest wins if a step was submitted twice.
                    emit(submissions.reversed().associateBy { it.routineStepId!! })
                }
            }

    private suspend fun getSubmissions(where: String, parameters: List<Any?>): List<ReportSubmission> {
        val submissions = database.getAll(
            sql = """
                SELECT s.id, s.report_id, s.task_id, s.routine_step_id, s.report_name, s.note, s.submitted_at
                FROM report_submissions s
                WHERE $where
                ORDER BY s.submitted_at DESC
            """.trimIndent(),
            parameters = parameters,
        ) { c: SqlCursor ->
            ReportSubmission(
                id = c.getString(0)!!,
                reportId = c.getString(1),
                taskId = c.getString(2),
                routineStepId = c.getString(3),
                reportName = c.getString(4) ?: "",
                note = c.getString(5),
                submittedAt = c.getString(6) ?: "",
                entries = emptyList(),
            )
        }
        if (submissions.isEmpty()) return emptyList()
        val placeholders = submissions.joinToString(",") { "?" }
        val entries = database.getAll(
            sql = """
                SELECT submission_id, metric_id, option_id, metric_name, metric_kind,
                       option_label, option_index, option_count
                FROM metric_entries
                WHERE submission_id IN ($placeholders)
                ORDER BY position
            """.trimIndent(),
            parameters = submissions.map { it.id },
        ) { c: SqlCursor ->
            c.getString(0)!! to MetricEntry(
                metricId = c.getString(1),
                optionId = c.getString(2),
                metricName = c.getString(3) ?: "",
                metricKind = MetricKind.from(c.getString(4)),
                optionLabel = c.getString(5) ?: "",
                optionIndex = (c.getLong(6) ?: 0L).toInt(),
                optionCount = (c.getLong(7) ?: 0L).toInt(),
            )
        }.groupBy({ it.first }, { it.second })
        return submissions.map { it.copy(entries = entries[it.id].orEmpty()) }
    }

    private suspend fun requireUserId(): String =
        sessionRepository.userIdForLocalWrites() ?: error("Not authenticated")
}
