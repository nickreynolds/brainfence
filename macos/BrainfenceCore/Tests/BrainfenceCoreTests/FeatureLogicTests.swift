import XCTest
@testable import BrainfenceCore

private let zone = TimeZone(identifier: "America/New_York")!
private let clock = ZonedClock(timeZone: zone)

private func at(_ date: String, _ hour: Int = 12, _ minute: Int = 0) -> Date {
    clock.instant(CivilDate(date)!, hour: hour, minute: minute)
}

/// Ports of desktop-app/test/policies.test.ts (itself ported from Android's
/// ContemplationScheduleTest and ReportAnswersTest).
final class ContemplationScheduleTests: XCTestCase {
    private let config = ContemplationScheduleConfig(perDay: 5, windowStart: 8 * 3600, windowEnd: 21 * 3600)
    private let date = CivilDate(year: 2026, month: 4, day: 16)

    func testSlotsArePerDaySortedInsideTheWindowOnePerSegment() {
        let slots = contemplationSlots(for: date, config: config, seed: 42)
        XCTAssertEqual(slots.count, 5)
        XCTAssertEqual(slots, slots.sorted())
        let segment = Double(config.windowEnd - config.windowStart) / 5
        for (i, s) in slots.enumerated() {
            XCTAssertGreaterThanOrEqual(Double(s), Double(config.windowStart) + Double(i) * segment)
            XCTAssertLessThan(Double(s), Double(config.windowStart) + Double(i + 1) * segment)
        }
        for i in 1..<slots.count {
            XCTAssertGreaterThanOrEqual(Double(slots[i] - slots[i - 1]), 0.4 * segment - 1)
        }
    }

    func testSlotsAreDeterministicPerDateAndSeedAndEmptyWhenUnusable() {
        XCTAssertEqual(contemplationSlots(for: date, config: config, seed: 42), contemplationSlots(for: date, config: config, seed: 42))
        XCTAssertNotEqual(contemplationSlots(for: date, config: config, seed: 42), contemplationSlots(for: date.plusDays(1), config: config, seed: 42))
        XCTAssertEqual(contemplationSlots(for: date, config: ContemplationScheduleConfig(perDay: 0, windowStart: 0, windowEnd: 100), seed: 42), [])
        XCTAssertEqual(contemplationSlots(for: date, config: ContemplationScheduleConfig(perDay: 3, windowStart: 100, windowEnd: 100), seed: 42), [])
    }

    func testNextSlotIsFirstAfterNowElseTomorrow() {
        let slots = contemplationSlots(for: date, config: config, seed: 7)
        let now = clock.startOfDay(date).addingTimeInterval(TimeInterval(slots[1] + 1))
        let next = nextContemplationSlot(after: now, config: config, seed: 7, timeZone: zone)!
        XCTAssertEqual(clock.secondsIntoDay(of: next), slots[2])
        let late = nextContemplationSlot(after: at("2026-04-16", 22), config: config, seed: 7, timeZone: zone)!
        XCTAssertEqual(clock.date(of: late), date.plusDays(1))
        XCTAssertNil(nextContemplationSlot(after: at("2026-04-16", 9), config: ContemplationScheduleConfig(perDay: 0, windowStart: 0, windowEnd: 100), seed: 7, timeZone: zone))
    }

    func testItemPicking() {
        let ids = ["a", "b", "c"]
        let r = seededRandom(1)
        XCTAssertEqual(pickItemIndex(ordering: .sequential, itemIds: ids, lastDeliveredId: nil, random: r), 0)
        XCTAssertEqual(pickItemIndex(ordering: .sequential, itemIds: ids, lastDeliveredId: "a", random: r), 1)
        XCTAssertEqual(pickItemIndex(ordering: .sequential, itemIds: ids, lastDeliveredId: "c", random: r), 0)
        XCTAssertEqual(pickItemIndex(ordering: .sequential, itemIds: ids, lastDeliveredId: "deleted", random: r), 0)
        for _ in 0..<200 {
            XCTAssertNotEqual(pickItemIndex(ordering: .random, itemIds: ids, lastDeliveredId: "b", random: r), 1)
        }
        XCTAssertEqual(pickItemIndex(ordering: .random, itemIds: ["only"], lastDeliveredId: "only", random: r), 0)
    }

    func testWeightedPickRespectsProportions() {
        let r = seededRandom(99)
        var counts = [0, 0]
        for _ in 0..<10_000 { counts[pickWeightedIndex([4, 1], random: r)] += 1 }
        let share = Double(counts[0]) / 10_000
        XCTAssertTrue(share > 0.77 && share < 0.83, "share \(share)")
    }

    func testSeededRandomMatchesTheWindowsClient() {
        // mulberry32(1)'s first value, as computed by desktop-app's seededRandom.
        let r = seededRandom(1)
        XCTAssertEqual(r(), 0.6270739405881613, accuracy: 1e-15)
    }
}

final class ReportLogicTests: XCTestCase {
    private let report = Report(id: "r", name: "Mood", metrics: [
        Metric(id: "m1", name: "Anxiety", kind: .ordinal, options: [
            MetricOption(id: "o1", metricId: "m1", label: "1", sortOrder: 0),
            MetricOption(id: "o2", metricId: "m1", label: "2", sortOrder: 1),
        ]),
        Metric(id: "m2", name: "Nicotine", kind: .categorical, options: [
            MetricOption(id: "p1", metricId: "m2", label: "None", sortOrder: 0),
            MetricOption(id: "p2", metricId: "m2", label: "Vape", sortOrder: 1),
            MetricOption(id: "p3", metricId: "m2", label: "Patch", sortOrder: 2),
        ]),
    ])

    func testEntriesSnapshotMetricAndOption() throws {
        let entries = try buildMetricEntries(report, answers: ["m1": "o2", "m2": "p3"])
        XCTAssertEqual(entries.map(\.metricName), ["Anxiety", "Nicotine"])
        XCTAssertEqual(entries.map(\.metricKind), [.ordinal, .categorical])
        XCTAssertEqual(entries.map(\.optionLabel), ["2", "Patch"])
        XCTAssertEqual(entries.map(\.optionIndex), [1, 2])
        XCTAssertEqual(entries.map(\.optionCount), [2, 3])
        XCTAssertEqual(unansweredMetrics(report, answers: ["m1": "p1"]).map(\.id), ["m1", "m2"])
        XCTAssertThrowsError(try buildMetricEntries(report, answers: ["m1": "o1"]))
    }

    func testMetricDraftValidation() {
        XCTAssertEqual(validateMetricDraft(name: "", optionLabels: ["a", "b"]), "Name is required")
        XCTAssertEqual(validateMetricDraft(name: "x", optionLabels: ["a", " "]), "Options can't be blank")
        XCTAssertEqual(validateMetricDraft(name: "x", optionLabels: ["a"]), "Use 2–5 options")
        XCTAssertEqual(validateMetricDraft(name: "x", optionLabels: ["a", "b", "c", "d", "e", "f"]), "Use 2–5 options")
        XCTAssertEqual(validateMetricDraft(name: "x", optionLabels: ["A", "a "]), "Options must be different from each other")
        XCTAssertNil(validateMetricDraft(name: "x", optionLabels: ["a", "b"]))
    }
}

final class TaskListTests: XCTestCase {
    private func task(_ id: String, _ overrides: (inout BrainTask) -> Void = { _ in }) -> BrainTask {
        var t = BrainTask(id: id, title: id, availableFrom: nil, dueAt: nil)
        overrides(&t)
        return t
    }

    func testPartitionOrdersByUrgencyAndSplitsTabs() {
        let tasks = [
            task("plain"),
            task("due") { $0.dueAt = "18:00" },
            task("blocker-later") { $0.isBlockingCondition = true; $0.dueAt = "20:00" },
            task("blocker-overdue") { $0.isBlockingCondition = true; $0.dueAt = "09:00" },
            task("not-yet") { $0.availableFrom = "15:00" },
            task("done-daily") { $0.completedToday = true },
            task("done-once") { $0.completedToday = true; $0.recurrenceType = nil },
        ]
        let p = partitionTasks(tasks, now: at("2026-04-16", 12), timeZone: zone)
        XCTAssertEqual(p.active.map(\.id), ["blocker-overdue", "blocker-later", "due", "plain"])
        XCTAssertEqual(p.done.map(\.id), ["done-daily", "done-once"])
        XCTAssertEqual(p.upcoming.map(\.id), ["not-yet", "done-daily"])
    }

    func testTaskNotificationsFireOnceInTheirWindow() {
        let tasks = [
            task("a") { $0.title = "Stretch"; $0.availableFrom = "09:00" },
            task("b") { $0.title = "Journal"; $0.dueAt = "13:00" },
        ]
        var sent = Set<String>()
        let ready = dueTaskNotifications(tasks: tasks, enforcedTaskIds: ["b"], now: at("2026-04-16", 9, 1), sentKeys: &sent, timeZone: zone)
        XCTAssertEqual(ready, [TaskNotification(title: "Stretch is ready", body: "Available since 09:00")])
        XCTAssertEqual(dueTaskNotifications(tasks: tasks, enforcedTaskIds: ["b"], now: at("2026-04-16", 9, 1), sentKeys: &sent, timeZone: zone), [])

        let warning = dueTaskNotifications(tasks: tasks, enforcedTaskIds: ["b"], now: at("2026-04-16", 12, 30), sentKeys: &sent, timeZone: zone)
        XCTAssertEqual(warning.map(\.title), ["Journal will block in 1 hour"])
        // Not enforced by a rule: no warning.
        var fresh = Set<String>()
        XCTAssertEqual(dueTaskNotifications(tasks: tasks, enforcedTaskIds: [], now: at("2026-04-16", 12, 30), sentKeys: &fresh, timeZone: zone), [])
    }

    func testOnlyNonFlowTasksAreQuickCompletable() {
        XCTAssertTrue(task("a").isQuickCompletable)
        XCTAssertFalse(task("b") { $0.taskType = "journal" }.isQuickCompletable)
        XCTAssertFalse(task("c") { $0.taskType = "shopping" }.isQuickCompletable)
        XCTAssertFalse(task("d") { $0.verificationType = "meditation" }.isQuickCompletable)
        XCTAssertTrue(task("e") { $0.verificationType = "gps" }.isLocationTask)
    }
}

final class TaskEditorTests: XCTestCase {
    func testNewSimpleTaskWritesNullVerification() {
        var s = TaskEditorState()
        s.title = "  Stretch "
        s.recurrenceType = "daily"
        s.dueAt = "10:00"
        let w = s.write
        XCTAssertEqual(w.title, "Stretch")
        XCTAssertNil(w.verificationType)
        XCTAssertEqual(w.verificationConfig, "{}")
        XCTAssertEqual(w.recurrenceType, "daily")
        XCTAssertEqual(w.dueAt, "10:00")
        XCTAssertNil(w.routineSteps)
    }

    func testWeeklyDaysMapToStoredRecurrence() {
        var s = TaskEditorState()
        s.title = "t"
        s.recurrenceType = "weekly"
        s.weeklyDays = ["fri", "mon"]
        XCTAssertEqual(s.write.recurrenceType, "daily")
        XCTAssertEqual(s.write.recurrenceConfig, #"{"days":["mon","fri"]}"#)
        s.weeklyDays = ["wed"]
        XCTAssertEqual(s.write.recurrenceType, "weekly")
        XCTAssertEqual(s.write.recurrenceConfig, #"{"day":"wed"}"#)
        s.weeklyDays = []
        XCTAssertNil(s.write.recurrenceType)
    }

    func testLoadingMultiDayDailyShowsWeekly() {
        let row = TaskRow(title: "t", recurrenceType: "daily", recurrenceConfig: #"{"days":["mon","wed"]}"#)
        let s = TaskEditorState(editing: "id", row: row, steps: [])
        XCTAssertEqual(s.recurrenceType, "weekly")
        XCTAssertEqual(s.weeklyDays, ["mon", "wed"])
    }

    func testMeditationKeepsOtherPlatformsCompanionAppsAndUnknownKeys() {
        let config = #"{"duration_seconds":600,"bell_interval_seconds":300,"companion_apps":[{"platform":"android","package":"com.calm"},{"platform":"macos","bundle_id":"com.old"}]}"#
        let row = TaskRow(title: "Sit", verificationType: "meditation", verificationConfig: config)
        var s = TaskEditorState(editing: "id", row: row, steps: [])
        XCTAssertEqual(s.companionApps.count, 1)
        s.companionApps = [.object(["platform": .string("macos"), "bundle_id": .string("com.new")])]
        let out = JSONValue.parse(s.write.verificationConfig)!
        XCTAssertEqual(out["bell_interval_seconds"]?.intValue, 300)
        XCTAssertEqual(out["duration_seconds"]?.intValue, 600)
        let apps = out["companion_apps"]!.arrayValue!
        XCTAssertEqual(apps.compactMap { $0["package"]?.stringValue }, ["com.calm"])
        XCTAssertEqual(apps.compactMap { $0["bundle_id"]?.stringValue }, ["com.new"])
    }

    func testChangingTypeDropsCarriedKeys() {
        let row = TaskRow(title: "t", taskType: "timed", verificationType: "duration", verificationConfig: #"{"duration_seconds":60,"extra":1}"#)
        var s = TaskEditorState(editing: "id", row: row, steps: [])
        XCTAssertEqual(JSONValue.parse(s.write.verificationConfig)?["extra"]?.intValue, 1)
        s.setTaskType("journal")
        XCTAssertEqual(s.write.verificationType, "journal")
        XCTAssertNil(JSONValue.parse(s.write.verificationConfig)?["extra"])
    }

    func testValidation() {
        var s = TaskEditorState()
        XCTAssertEqual(s.validateForSave(reportIds: []), "Title is required")
        s.title = "t"
        s.setTaskType("report")
        XCTAssertEqual(s.validateForSave(reportIds: []), "Choose which report this task asks")
        s.reportId = "gone"
        XCTAssertEqual(s.validateForSave(reportIds: ["r"]), "That report no longer exists")
        s.setTaskType("routine")
        XCTAssertEqual(s.validateStep(1), "Add at least one step")
        s.routineSteps = [EditableStep(title: "Step")]
        s.dueAt = "25:00"
        XCTAssertEqual(s.validateForSave(reportIds: []), "Due at must be HH:mm")
        s.dueAt = "9:30"
        XCTAssertNil(s.validateForSave(reportIds: []))
        s.setTaskType("shopping")
        XCTAssertEqual(s.validateForSave(reportIds: []), "A reminder location (latitude/longitude) is required")
    }

    func testRoutineStepsRoundTrip() {
        let steps = [
            RoutineStep(id: "s1", taskId: "t", title: "Squat", stepOrder: 0, stepType: "weight_reps", config: #"{"default_sets":4}"#, supersetGroup: "g"),
            RoutineStep(id: "s2", taskId: "t", title: "Plank", stepOrder: 1, stepType: "timed", config: #"{"duration_seconds":45}"#),
        ]
        let s = TaskEditorState(editing: "t", row: TaskRow(title: "Legs", taskType: "workout"), steps: steps)
        XCTAssertEqual(s.routineSteps.map(\.defaultSets), [4, 3])
        let written = s.write.routineSteps!
        XCTAssertEqual(written.map(\.id), ["s1", "s2"])
        XCTAssertEqual(written.map(\.config), [#"{"default_sets":4}"#, #"{"duration_seconds":45}"#])
        XCTAssertEqual(written[0].supersetGroup, "g")
    }
}

final class RoutineSessionTests: XCTestCase {
    private let steps = [
        RoutineStep(id: "a", taskId: "t", title: "Bench", stepOrder: 0, stepType: "weight_reps", config: #"{"default_sets":2}"#),
        RoutineStep(id: "b", taskId: "t", title: "Stretch", stepOrder: 1, stepType: "checkbox"),
        RoutineStep(id: "c", taskId: "t", title: "Hold", stepOrder: 2, stepType: "timed", config: #"{"duration_seconds":2}"#),
    ]

    func testPrefillsFromLastCompletion() {
        let last = [
            StepCompletion(routineStepId: "a", setNumber: 1, data: #"{"weight":100,"reps":8}"#),
            StepCompletion(routineStepId: "a", setNumber: 2, data: #"{"weight":105,"reps":6}"#),
            StepCompletion(routineStepId: "a", setNumber: 3, data: #"{"weight":110,"reps":0}"#),
        ]
        let s = RoutineSession(steps: steps, last: last)
        let bench = s.state("a")!
        XCTAssertEqual(bench.sets.count, 3)
        XCTAssertEqual(bench.sets.map(\.weight), [100, 105, 110])
        XCTAssertEqual(bench.sets.map(\.reps), [8, 6, nil])
    }

    func testCompletingEverythingAndStepData() {
        var s = RoutineSession(steps: steps, last: [])
        s.updateSet("a", index: 0, SetEntry(weight: 50, reps: 10))
        s.completeCurrentSet("a")
        XCTAssertEqual(s.state("a")?.activeSetIndex, 1)
        s.completeCurrentSet("a")
        s.toggleCheckbox("b")
        XCTAssertFalse(s.allStepsCompleted)
        s.startTimer("c", index: 0)
        s.tickTimers()
        XCTAssertFalse(s.allStepsCompleted)
        s.tickTimers()
        XCTAssertTrue(s.allStepsCompleted)
        let data = s.stepData()
        XCTAssertEqual(data["a"]?.first, #"{"reps":10,"weight":50}"#)
        XCTAssertEqual(data["b"], [#"{"completed":true}"#])
        XCTAssertEqual(data["c"], [#"{"actual_seconds":2,"completed":true}"#])
    }

    func testSupersetRoundsAdvance() {
        let grouped = [
            RoutineStep(id: "x", taskId: "t", title: "Curl", stepOrder: 0, stepType: "just_reps", config: #"{"default_sets":3}"#, supersetGroup: "g"),
            RoutineStep(id: "y", taskId: "t", title: "Press", stepOrder: 1, stepType: "just_reps", config: #"{"default_sets":2}"#, supersetGroup: "g"),
        ]
        var s = RoutineSession(steps: grouped, last: [])
        XCTAssertEqual(s.rounds["g"]?.totalRounds, 3)
        XCTAssertEqual(s.rounds["g"]?.stepIds, ["x", "y"])
        s.advanceRound("g")
        XCTAssertEqual(s.rounds["g"]?.currentRound, 2)
        XCTAssertEqual(s.state("x")?.sets[0].completed, true)
        XCTAssertEqual(s.state("y")?.sets[0].completed, true)
    }

    func testSerializeAndRestore() {
        var s = RoutineSession(steps: steps, last: [])
        s.toggleCheckbox("b")
        s.updateSet("a", index: 1, SetEntry(weight: 60, reps: 5))
        let saved = s.serialized(auto: AutoProgress(phase: .work, exerciseIndex: 1, rep: 0))
        let restored = RoutineSession(steps: steps, saved: saved)!
        XCTAssertEqual(restored.state("b")?.sets[0].completed, true)
        XCTAssertEqual(restored.state("a")?.sets[1].weight, 60)
        XCTAssertEqual(RoutineSession.savedAutoProgress(saved)?.exerciseIndex, 1)
        XCTAssertEqual(RoutineSession.savedAutoProgress(saved)?.phase, .paused)
        // Progress for steps that no longer exist is stale.
        XCTAssertNil(RoutineSession(steps: [RoutineStep(id: "z", taskId: "t", title: "New", stepOrder: 0, stepType: "checkbox")], saved: saved))
    }

    func testReportStepsFollowSubmissions() {
        var s = RoutineSession(steps: [RoutineStep(id: "r", taskId: "t", title: "Mood", stepOrder: 0, stepType: "report")], last: [])
        s.markReportSteps(submitted: ["r"])
        XCTAssertTrue(s.allStepsCompleted)
        XCTAssertEqual(s.stepData(submissionIds: ["r": "sub"])["r"], [#"{"completed":true,"submission_id":"sub"}"#])
    }
}

final class TimerSessionTests: XCTestCase {
    private let t0 = Date(timeIntervalSince1970: 1_000_000)

    func testDurationAccumulatesAcrossPauses() {
        var d = DurationSession(taskId: "t", taskTitle: "Read", targetSeconds: 100, runningSince: t0, day: "2026-04-16")
        d.pause(at: t0.addingTimeInterval(30))
        XCTAssertEqual(d.elapsed(at: t0.addingTimeInterval(500)), 30)
        d.resume(at: t0.addingTimeInterval(600))
        XCTAssertEqual(d.elapsed(at: t0.addingTimeInterval(670)), 100)
        XCTAssertTrue(d.isComplete(at: t0.addingTimeInterval(670)))
    }

    func testMeditationBanksShortSitThenRingsAndCompletes() {
        var m = MeditationSession(timer: DurationSession(taskId: "t", taskTitle: "Sit", targetSeconds: 600, runningSince: t0, day: "d"), method: .inApp)
        m.shortSitSeconds = 120
        m.bellIntervalSeconds = 200
        XCTAssertEqual(m.tick(at: t0.addingTimeInterval(60)), [])
        XCTAssertEqual(m.tick(at: t0.addingTimeInterval(121)), [.bankShortSit(actualSeconds: 121, chime: false)])
        XCTAssertTrue(m.canFinishEarly(at: t0.addingTimeInterval(121)))
        XCTAssertEqual(m.tick(at: t0.addingTimeInterval(205)), [.bell])
        XCTAssertEqual(m.tick(at: t0.addingTimeInterval(206)), [])
        XCTAssertEqual(m.tick(at: t0.addingTimeInterval(600)), [.complete(actualSeconds: 600)])
        let data = JSONValue.parse(m.verificationData(actualSeconds: 600))!
        XCTAssertEqual(data["method"]?.stringValue, "in_app_timer")
        XCTAssertEqual(data["short_sit"]?.boolValue, false)
    }

    func testCompanionTrackingCountsOnlyWhileFrontmost() {
        var m = MeditationSession(timer: DurationSession(taskId: "t", taskTitle: "Sit", targetSeconds: 600, day: "d"), method: .companionApp)
        m.companionApps = ["com.calm"]
        XCTAssertTrue(m.foregroundChanged(to: "com.calm", at: t0))
        XCTAssertFalse(m.foregroundChanged(to: "com.calm", at: t0.addingTimeInterval(5)))
        XCTAssertTrue(m.foregroundChanged(to: "com.apple.Safari", at: t0.addingTimeInterval(90)))
        XCTAssertEqual(m.elapsed(at: t0.addingTimeInterval(1000)), 90)
        XCTAssertEqual(JSONValue.parse(m.verificationData(actualSeconds: 90))?["app"]?.stringValue, "com.calm")
    }

    func testConfigsParseThisPlatformsCompanionApps() {
        let c = parseMeditationConfig(#"{"duration_seconds":600,"short_sit_seconds":600,"companion_apps":[{"platform":"macos","bundle_id":"com.calm"},{"platform":"windows","exe":"x.exe"}]}"#)!
        XCTAssertEqual(c.companionApps, ["com.calm"])
        XCTAssertEqual(c.shortSitSeconds, 0, "a threshold at the full duration is disabled")
        XCTAssertEqual(parseDurationConfig(#"{"target_seconds":90}"#)?.targetSeconds, 90)
        XCTAssertNil(parseDurationConfig("{}"))
    }
}
