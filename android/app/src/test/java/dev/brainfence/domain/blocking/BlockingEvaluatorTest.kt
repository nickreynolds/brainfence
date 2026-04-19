package dev.brainfence.domain.blocking

import dev.brainfence.domain.model.BlockingRule
import dev.brainfence.domain.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class BlockingEvaluatorTest {

    private val zone = ZoneId.of("America/New_York")

    private fun instant(hour: Int, minute: Int = 0) =
        LocalDate.parse("2026-04-16").atTime(hour, minute).atZone(zone).toInstant()

    private fun timeGateTask(
        id: String = "task-1",
        completedToday: Boolean = false,
        startTime: String = "07:00",
        endTime: String = "10:00",
    ) = Task(
        id = id,
        userId = "user-1",
        title = "Morning routine",
        description = null,
        taskType = "habit",
        status = "active",
        recurrenceType = "daily",
        recurrenceConfig = "{}",
        verificationType = "time_gate",
        verificationConfig = """{"start_time": "$startTime", "end_time": "$endTime", "timezone": "America/New_York"}""",
        tags = "[]",
        groupId = null,
        sortOrder = 0,
        isBlockingCondition = true,
        blockingRuleIds = "[]",
        createdAt = "2026-04-01T00:00:00Z",
        updatedAt = "2026-04-01T00:00:00Z",
        completedToday = completedToday,
    )

    private fun manualTask(
        id: String = "task-2",
        completedToday: Boolean = false,
    ) = Task(
        id = id,
        userId = "user-1",
        title = "Manual task",
        description = null,
        taskType = "habit",
        status = "active",
        recurrenceType = "daily",
        recurrenceConfig = "{}",
        verificationType = "manual",
        verificationConfig = "{}",
        tags = "[]",
        groupId = null,
        sortOrder = 0,
        isBlockingCondition = true,
        blockingRuleIds = "[]",
        createdAt = "2026-04-01T00:00:00Z",
        updatedAt = "2026-04-01T00:00:00Z",
        completedToday = completedToday,
    )

    private fun rule(
        conditionTaskIds: List<String> = listOf("task-1"),
        conditionLogic: String = "all",
    ) = BlockingRule(
        id = "rule-1",
        userId = "user-1",
        name = "Block social media",
        blockedApps = listOf("com.twitter.android"),
        blockedDomains = emptyList(),
        conditionTaskIds = conditionTaskIds,
        conditionLogic = conditionLogic,
        activeSchedule = "{}",
        configLockHours = 24,
        pendingChanges = null,
        changesApplyAt = null,
        isActive = true,
    )

    // -----------------------------------------------------------------------
    // Time gate + blocking evaluator
    // -----------------------------------------------------------------------

    @Test
    fun `time gate task before start_time does not cause blocking`() {
        val task = timeGateTask(completedToday = false)
        val result = evaluateBlocking(
            rules = listOf(rule()),
            tasks = listOf(task),
            currentTime = instant(6, 0), // before 07:00 start
            timeZone = zone,
        )
        assertTrue("Apps should not be blocked before start_time", result.blockedApps.isEmpty())
    }

    @Test
    fun `time gate task during active window does not cause blocking`() {
        val task = timeGateTask(completedToday = false)
        val result = evaluateBlocking(
            rules = listOf(rule()),
            tasks = listOf(task),
            currentTime = instant(8, 0), // between 07:00 and 10:00
            timeZone = zone,
        )
        assertTrue("Apps should not be blocked during active window", result.blockedApps.isEmpty())
    }

    @Test
    fun `time gate task after end_time and not completed causes blocking`() {
        val task = timeGateTask(completedToday = false)
        val result = evaluateBlocking(
            rules = listOf(rule()),
            tasks = listOf(task),
            currentTime = instant(11, 0), // after 10:00 end
            timeZone = zone,
        )
        assertEquals(setOf("com.twitter.android"), result.blockedApps)
    }

    @Test
    fun `time gate task after end_time but completed does not cause blocking`() {
        val task = timeGateTask(completedToday = true)
        val result = evaluateBlocking(
            rules = listOf(rule()),
            tasks = listOf(task),
            currentTime = instant(11, 0),
            timeZone = zone,
        )
        assertTrue("Apps should not be blocked after completion", result.blockedApps.isEmpty())
    }

    @Test
    fun `time gate completed during active window does not cause blocking later`() {
        val task = timeGateTask(completedToday = true)
        val result = evaluateBlocking(
            rules = listOf(rule()),
            tasks = listOf(task),
            currentTime = instant(15, 0), // well past end
            timeZone = zone,
        )
        assertTrue(result.blockedApps.isEmpty())
    }

    // -----------------------------------------------------------------------
    // Mixed conditions: time_gate + manual with "all" logic
    // -----------------------------------------------------------------------

    @Test
    fun `all logic - time gate before end and manual incomplete - no blocking`() {
        // Before the time gate's end_time, the time_gate condition is treated as met.
        // The manual task is NOT completed, so "all" is not met → blocks? No.
        // Wait: time_gate returns true (met), manual returns false (not met) → all = false → block.
        // But the key point: the time_gate NOT blocking shouldn't override the manual.
        val tgTask = timeGateTask(id = "task-1", completedToday = false)
        val mTask = manualTask(id = "task-2", completedToday = false)
        val r = rule(conditionTaskIds = listOf("task-1", "task-2"), conditionLogic = "all")
        val result = evaluateBlocking(
            rules = listOf(r),
            tasks = listOf(tgTask, mTask),
            currentTime = instant(8, 0), // time gate active window
            timeZone = zone,
        )
        // Manual task is incomplete → blocks
        assertEquals(setOf("com.twitter.android"), result.blockedApps)
    }

    @Test
    fun `any logic - time gate before end and manual incomplete - no blocking`() {
        // With "any" logic: time_gate returns true (treated as met before end_time) → any is satisfied
        val tgTask = timeGateTask(id = "task-1", completedToday = false)
        val mTask = manualTask(id = "task-2", completedToday = false)
        val r = rule(conditionTaskIds = listOf("task-1", "task-2"), conditionLogic = "any")
        val result = evaluateBlocking(
            rules = listOf(r),
            tasks = listOf(tgTask, mTask),
            currentTime = instant(8, 0),
            timeZone = zone,
        )
        assertTrue("Any logic: time_gate treated as met before end_time", result.blockedApps.isEmpty())
    }

    @Test
    fun `all logic - both past end and incomplete - blocks`() {
        val tgTask = timeGateTask(id = "task-1", completedToday = false)
        val mTask = manualTask(id = "task-2", completedToday = false)
        val r = rule(conditionTaskIds = listOf("task-1", "task-2"), conditionLogic = "all")
        val result = evaluateBlocking(
            rules = listOf(r),
            tasks = listOf(tgTask, mTask),
            currentTime = instant(11, 0), // past end_time
            timeZone = zone,
        )
        assertEquals(setOf("com.twitter.android"), result.blockedApps)
    }
}
