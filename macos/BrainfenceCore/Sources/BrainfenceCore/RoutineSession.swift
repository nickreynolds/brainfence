import Foundation

// Routine / workout sessions: per-step set entries, superset rounds,
// pre-filling from the last completion, in-progress persistence and the
// step_completions data written on finish. Port of Android's
// RoutineTaskViewModel (via desktop-app's routineSession.ts).

public struct RoutineStep: Sendable, Hashable, Identifiable {
    public let id: String
    public let taskId: String
    public var title: String
    public var stepOrder: Int
    /// checkbox | weight_reps | just_reps | timed | timed_sets | report
    public var stepType: String
    public var config: String
    public var supersetGroup: String?
    public var description: String?

    public init(
        id: String, taskId: String, title: String, stepOrder: Int, stepType: String,
        config: String = "{}", supersetGroup: String? = nil, description: String? = nil
    ) {
        self.id = id
        self.taskId = taskId
        self.title = title
        self.stepOrder = stepOrder
        self.stepType = stepType
        self.config = config
        self.supersetGroup = supersetGroup
        self.description = description
    }

    var configJSON: JSONValue { JSONValue.parse(config) ?? .object([:]) }

    /// Target of a timed step, in seconds.
    public var durationSeconds: Int { Int(configJSON.first("duration_seconds")?.doubleValue ?? 60) }

    /// Automatic (interval) routine parameters for a timed_sets step.
    public var reps: Int { Int(configJSON.first("reps")?.doubleValue ?? 1) }
    public var workSeconds: Int { Int(configJSON.first("work_seconds")?.doubleValue ?? 10) }
    public var restSeconds: Int { Int(configJSON.first("rest_seconds")?.doubleValue ?? 20) }

    /// Number of sets a fresh session starts with.
    public var defaultSetCount: Int {
        switch stepType {
        case "weight_reps", "just_reps":
            let n = Int(configJSON.first("default_sets")?.doubleValue ?? 3)
            return n > 0 ? n : 3
        case "timed_sets":
            return reps > 0 ? reps : 1
        default:
            return 1
        }
    }
}

public struct StepCompletion: Sendable, Hashable {
    public let routineStepId: String
    public let setNumber: Int
    public let data: String

    public init(routineStepId: String, setNumber: Int, data: String) {
        self.routineStepId = routineStepId
        self.setNumber = setNumber
        self.data = data
    }
}

public struct SetEntry: Sendable, Equatable {
    public var weight: Double?
    public var reps: Int?
    public var completed = false
    public var timerElapsed = 0
    public var timerRunning = false

    public init(weight: Double? = nil, reps: Int? = nil, completed: Bool = false, timerElapsed: Int = 0, timerRunning: Bool = false) {
        self.weight = weight
        self.reps = reps
        self.completed = completed
        self.timerElapsed = timerElapsed
        self.timerRunning = timerRunning
    }
}

public struct StepState: Sendable, Equatable {
    public var step: RoutineStep
    public var sets: [SetEntry]
    public var activeSetIndex: Int

    public var isDone: Bool { sets.allSatisfy(\.completed) }

    static func fresh(_ step: RoutineStep) -> StepState {
        StepState(step: step, sets: Array(repeating: SetEntry(), count: step.defaultSetCount), activeSetIndex: 0)
    }
}

public struct SupersetRound: Sendable, Equatable {
    public let groupId: String
    public var stepIds: [String]
    public var totalRounds: Int
    public var currentRound: Int
}

/// Progress of an automatic (interval) routine.
public struct AutoProgress: Sendable, Equatable {
    public enum Phase: Sendable, Equatable { case idle, countdown, work, rest, paused, completed }

    public var phase: Phase
    public var exerciseIndex: Int
    public var rep: Int
    public var secondsRemaining: Int

    public init(phase: Phase = .idle, exerciseIndex: Int = 0, rep: Int = 0, secondsRemaining: Int = 0) {
        self.phase = phase
        self.exerciseIndex = exerciseIndex
        self.rep = rep
        self.secondsRemaining = secondsRemaining
    }
}

public struct RoutineSession: Sendable, Equatable {
    public private(set) var steps: [RoutineStep]
    public private(set) var states: [String: StepState]
    public private(set) var rounds: [String: SupersetRound]

    /// A fresh session, pre-filled with weights and reps from [last] (the
    /// step completions of the most recent completion).
    public init(steps: [RoutineStep], last: [StepCompletion]) {
        self.steps = steps
        let byStep = Dictionary(grouping: last, by: \.routineStepId)
        var states: [String: StepState] = [:]
        for step in steps {
            let count = step.defaultSetCount
            let previous = byStep[step.id] ?? []
            // In a superset, sets are rounds: the count comes from default_sets.
            let n = step.supersetGroup != nil ? count : max(previous.count, count)
            let sets = (0..<n).map { i -> SetEntry in
                let data = JSONValue.parse(previous.first { $0.setNumber == i + 1 }?.data) ?? .object([:])
                let reps = data["reps"]?.doubleValue.map { Int($0) }
                return SetEntry(weight: data["weight"]?.doubleValue, reps: (reps ?? 0) > 0 ? reps : nil)
            }
            states[step.id] = StepState(step: step, sets: sets, activeSetIndex: 0)
        }
        self.states = states
        self.rounds = Self.initialRounds(steps)
    }

    /// Restores an in-progress session saved by [serialized(auto:)]. Nil when
    /// nothing matches the current steps (stale progress).
    public init?(steps: [RoutineStep], saved: JSONValue?) {
        guard let savedSteps = saved?["steps"]?.objectValue,
              steps.contains(where: { savedSteps[$0.id] != nil }) else { return nil }
        self.steps = steps
        var states: [String: StepState] = [:]
        for step in steps {
            guard let s = savedSteps[step.id] else {
                states[step.id] = .fresh(step)
                continue
            }
            var sets = (s["sets"]?.arrayValue ?? []).map { x -> SetEntry in
                let reps = x["reps"]?.doubleValue.map { Int($0) }
                return SetEntry(
                    weight: x["weight"]?.doubleValue,
                    reps: (reps ?? 0) > 0 ? reps : nil,
                    completed: x["completed"]?.boolValue == true,
                    timerElapsed: x["timerElapsed"]?.intValue ?? 0
                )
            }
            if sets.isEmpty { sets = StepState.fresh(step).sets }
            let active = s["activeSetIndex"]?.intValue ?? 0
            states[step.id] = StepState(step: step, sets: sets, activeSetIndex: min(max(0, active), sets.count - 1))
        }
        self.states = states
        if let savedRounds = saved?["supersetRounds"]?.objectValue {
            var rounds: [String: SupersetRound] = [:]
            for (group, r) in savedRounds {
                rounds[group] = SupersetRound(
                    groupId: group,
                    stepIds: (r["stepIds"]?.arrayValue ?? []).compactMap(\.stringValue),
                    totalRounds: r["totalRounds"]?.intValue ?? 1,
                    currentRound: r["currentRound"]?.intValue ?? 1
                )
            }
            self.rounds = rounds
        } else {
            self.rounds = Self.initialRounds(steps)
        }
    }

    /// The automatic-routine position saved alongside progress, if one was running.
    public static func savedAutoProgress(_ saved: JSONValue?) -> AutoProgress? {
        guard let auto = saved?["autoRoutine"], auto["active"]?.boolValue == true else { return nil }
        return AutoProgress(phase: .paused, exerciseIndex: auto["exerciseIndex"]?.intValue ?? 0, rep: auto["rep"]?.intValue ?? 0)
    }

    /// In-progress state to persist, in the Windows client's shape.
    public func serialized(auto: AutoProgress) -> JSONValue {
        var stepsOut: [String: JSONValue] = [:]
        for (id, s) in states {
            let sets = s.sets.map { e -> JSONValue in
                var o: [String: JSONValue] = ["completed": .bool(e.completed), "timerElapsed": .int(e.timerElapsed)]
                if let w = e.weight { o["weight"] = .double(w) }
                if let r = e.reps { o["reps"] = .int(r) }
                return JSONValue.object(o)
            }
            stepsOut[id] = .object(["activeSetIndex": .int(s.activeSetIndex), "sets": .array(sets)])
        }
        var out: [String: JSONValue] = ["steps": .object(stepsOut)]
        if !rounds.isEmpty {
            out["supersetRounds"] = .object(rounds.mapValues { r -> JSONValue in
                JSONValue.object([
                    "currentRound": .int(r.currentRound),
                    "totalRounds": .int(r.totalRounds),
                    "stepIds": .array(r.stepIds.map(JSONValue.string)),
                ])
            })
        }
        if auto.phase != .idle && auto.phase != .completed {
            out["autoRoutine"] = .object(["active": .bool(true), "exerciseIndex": .int(auto.exerciseIndex), "rep": .int(auto.rep)])
        }
        return .object(out)
    }

    private static func initialRounds(_ steps: [RoutineStep]) -> [String: SupersetRound] {
        var rounds: [String: SupersetRound] = [:]
        for step in steps {
            guard let group = step.supersetGroup else { continue }
            var r = rounds[group] ?? SupersetRound(groupId: group, stepIds: [], totalRounds: 1, currentRound: 1)
            r.stepIds.append(step.id)
            r.totalRounds = max(r.totalRounds, step.defaultSetCount)
            rounds[group] = r
        }
        return rounds
    }

    // MARK: Queries

    public var allStepsCompleted: Bool { !states.isEmpty && states.values.allSatisfy(\.isDone) }

    public func state(_ stepId: String) -> StepState? { states[stepId] }

    /// Where an automatic routine resumes: the first incomplete set, in step
    /// order. Nil when everything is done.
    public var autoStartPosition: (exercise: Int, rep: Int)? {
        for (i, step) in steps.enumerated() {
            if let rep = states[step.id]?.sets.firstIndex(where: { !$0.completed }) { return (i, rep) }
        }
        return nil
    }

    /// step id → per-set JSON for step_completions.data. [submissionIds]
    /// maps report steps to today's submission.
    public func stepData(submissionIds: [String: String] = [:]) -> [String: [String]] {
        var out: [String: [String]] = [:]
        for (stepId, s) in states {
            let step = s.step
            out[stepId] = s.sets.map { e in
                var json: [String: JSONValue] = [:]
                switch step.stepType {
                case "checkbox":
                    json["completed"] = .bool(e.completed)
                case "weight_reps":
                    if let w = e.weight { json["weight"] = .double(w) }
                    if let r = e.reps { json["reps"] = .int(r) }
                case "just_reps":
                    if let r = e.reps { json["reps"] = .int(r) }
                case "timed":
                    json["completed"] = .bool(e.completed)
                    json["actual_seconds"] = .int(e.timerElapsed)
                case "timed_sets":
                    json["completed"] = .bool(e.completed)
                    json["work_seconds"] = .int(Int(step.configJSON.first("work_seconds")?.doubleValue ?? 0))
                case "report":
                    json["completed"] = .bool(e.completed)
                    if let id = submissionIds[stepId] { json["submission_id"] = .string(id) }
                default:
                    break
                }
                return JSONValue.object(json).jsonString
            }
        }
        return out
    }

    // MARK: Changes

    /// Steps added or removed mid-session (here or synced from another device).
    public mutating func sync(steps newSteps: [RoutineStep]) {
        var next: [String: StepState] = [:]
        for step in newSteps {
            if var existing = states[step.id] {
                existing.step = step
                next[step.id] = existing
            } else {
                next[step.id] = .fresh(step)
            }
        }
        steps = newSteps
        states = next
    }

    /// A report step is complete exactly when today's submission for it exists.
    public mutating func markReportSteps(submitted: Set<String>) {
        for (id, s) in states where s.step.stepType == "report" {
            let done = submitted.contains(id)
            if (s.sets.first?.completed ?? false) != done {
                update(id) { state in
                    for i in state.sets.indices { state.sets[i].completed = done }
                }
            }
        }
    }

    private mutating func update(_ stepId: String, _ change: (inout StepState) -> Void) {
        guard var s = states[stepId] else { return }
        change(&s)
        states[stepId] = s
    }

    public mutating func updateSet(_ stepId: String, index: Int, _ entry: SetEntry) {
        update(stepId) { s in if s.sets.indices.contains(index) { s.sets[index] = entry } }
    }

    public mutating func toggleCheckbox(_ stepId: String) {
        update(stepId) { s in if !s.sets.isEmpty { s.sets[0].completed.toggle() } }
    }

    public mutating func completeCurrentSet(_ stepId: String) {
        update(stepId) { s in
            let i = s.activeSetIndex
            guard s.sets.indices.contains(i) else { return }
            s.sets[i].completed = true
            if i + 1 < s.sets.count { s.activeSetIndex = i + 1 }
        }
    }

    public mutating func goToSet(_ stepId: String, index: Int) {
        update(stepId) { s in if s.sets.indices.contains(index) { s.activeSetIndex = index } }
    }

    public mutating func addSet(_ stepId: String) {
        update(stepId) { s in
            let allDone = s.sets.allSatisfy(\.completed)
            var last = s.sets.last ?? SetEntry()
            last.completed = false
            last.timerRunning = false
            last.timerElapsed = 0
            s.sets.append(last)
            // If all previous sets were completed, move to the new one.
            if allDone { s.activeSetIndex = s.sets.count - 1 }
        }
    }

    public mutating func removeSet(_ stepId: String) {
        update(stepId) { s in
            guard s.sets.count > 1 else { return }
            s.sets.removeLast()
            s.activeSetIndex = min(s.activeSetIndex, s.sets.count - 1)
        }
    }

    public mutating func startTimer(_ stepId: String, index: Int) {
        update(stepId) { s in
            guard s.sets.indices.contains(index) else { return }
            s.sets[index].timerRunning = true
            s.sets[index].timerElapsed = 0
        }
    }

    public mutating func stopTimer(_ stepId: String, index: Int) {
        update(stepId) { s in if s.sets.indices.contains(index) { s.sets[index].timerRunning = false } }
    }

    public var anyTimerRunning: Bool { states.values.contains { $0.sets.contains(where: \.timerRunning) } }

    /// Advances running step timers by one second; a timer reaching its
    /// target stops and completes its set.
    public mutating func tickTimers() {
        for (id, s) in states where s.sets.contains(where: \.timerRunning) {
            let target = s.step.durationSeconds
            update(id) { s in
                for i in s.sets.indices where s.sets[i].timerRunning {
                    s.sets[i].timerElapsed += 1
                    if s.sets[i].timerElapsed >= target {
                        s.sets[i].timerRunning = false
                        s.sets[i].completed = true
                    }
                }
            }
        }
    }

    /// Completes the current round's sets in a superset and moves to the next.
    public mutating func advanceRound(_ groupId: String) {
        guard var r = rounds[groupId], r.currentRound < r.totalRounds else { return }
        let setIndex = r.currentRound - 1
        for stepId in r.stepIds {
            update(stepId) { s in if s.sets.indices.contains(setIndex) { s.sets[setIndex].completed = true } }
        }
        r.currentRound += 1
        rounds[groupId] = r
    }

    public mutating func goToRound(_ groupId: String, round: Int) {
        guard var r = rounds[groupId], (1...r.totalRounds).contains(round) else { return }
        r.currentRound = round
        rounds[groupId] = r
    }

    /// Marks a set done by the automatic runner.
    public mutating func completeAutoRep(_ stepId: String, rep: Int, workSeconds: Int) {
        update(stepId) { s in
            guard s.sets.indices.contains(rep) else { return }
            s.sets[rep].completed = true
            s.sets[rep].timerElapsed = workSeconds
            s.activeSetIndex = min(rep + 1, s.sets.count - 1)
        }
    }
}
