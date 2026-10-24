import BrainfenceCore
import PowerSync
import SwiftUI

extension View {
    /// Runs [load] now and again whenever any of [tables] changes, for as long
    /// as the view is visible (and again when [id] changes).
    func reloading<ID: Equatable>(
        on tables: [String],
        db: PowerSyncDatabaseProtocol,
        id: ID,
        perform load: @escaping @MainActor @Sendable () async -> Void
    ) -> some View {
        task(id: id) {
            while !Task.isCancelled {
                do {
                    for try await _ in try db.changes(of: tables) {
                        await load()
                    }
                } catch {
                    // The watch ended (e.g. a reconnect); resubscribe below.
                }
                try? await Task.sleep(for: .seconds(2))
            }
        }
    }

    func reloading(
        on tables: [String],
        db: PowerSyncDatabaseProtocol,
        perform load: @escaping @MainActor @Sendable () async -> Void
    ) -> some View {
        reloading(on: tables, db: db, id: 0, perform: load)
    }
}

/// "4:05" or "1:02:03".
func formatClock(_ seconds: Int) -> String {
    let s = max(0, seconds)
    return s >= 3600
        ? String(format: "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
        : String(format: "%d:%02d", s / 60, s % 60)
}

/// "12 min" / "1 h 5 min".
func formatMinutes(_ seconds: Int) -> String {
    let m = max(0, seconds) / 60
    return m >= 60 ? "\(m / 60) h \(m % 60) min" : "\(m) min"
}

/// A synced timestamp as local date and time ("Apr 16, 2:03 PM"), or the raw text.
func formatTimestamp(_ text: String?) -> String {
    guard let date = Timestamp.parse(text) else { return text ?? "" }
    return date.formatted(date: .abbreviated, time: .shortened)
}

func plural(_ n: Int, _ word: String) -> String { "\(n) \(word)\(n == 1 ? "" : "s")" }

/// Toggle buttons for the days of the week ("mon" … "sun").
struct DayPicker: View {
    @Binding var selection: [String]

    private static let days = [("mon", "Mon"), ("tue", "Tue"), ("wed", "Wed"), ("thu", "Thu"), ("fri", "Fri"), ("sat", "Sat"), ("sun", "Sun")]

    var body: some View {
        HStack(spacing: 4) {
            ForEach(Self.days, id: \.0) { item in
                let day = item.0
                let on = selection.contains(day)
                Button(item.1) {
                    if on { selection.removeAll { $0 == day } } else { selection.append(day) }
                }
                .buttonStyle(.bordered)
                .tint(on ? Palette.primary : nil)
                .background(on ? Palette.primaryContainer : .clear, in: RoundedRectangle(cornerRadius: 6))
            }
        }
    }
}

/// An optional "HH:mm" time as text, with a clear button.
struct TimeField: View {
    let label: String
    @Binding var text: String

    var body: some View {
        HStack {
            TextField(label, text: $text, prompt: Text("HH:mm"))
                .frame(maxWidth: 180)
            if !text.isEmpty {
                Button { text = "" } label: { Image(systemName: "xmark.circle.fill") }
                    .buttonStyle(.borderless)
                    .foregroundStyle(.secondary)
                    .help("Clear")
            }
        }
    }
}

struct ErrorText: View {
    let error: String?

    var body: some View {
        if let error {
            Text(error).font(.callout).foregroundStyle(Palette.error)
        }
    }
}

/// A centered status for a task done today.
struct CompletedToday: View {
    var detail: String?

    var body: some View {
        VStack(spacing: 8) {
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 44))
                .foregroundStyle(Palette.primary)
            Text("Completed today").font(.title2.weight(.semibold))
            if let detail { Text(detail).foregroundStyle(.secondary) }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 32)
    }
}

/// Before available_from a task can't be completed yet.
struct NotYetAvailable: View {
    let task: BrainTask
    let now: Date

    var body: some View {
        if isBeforeStart(task, now: now) {
            Label("Available at \(task.availableFrom ?? "")", systemImage: "lock")
                .padding(10)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Palette.secondaryContainer, in: RoundedRectangle(cornerRadius: 8))
        }
    }
}

func isBeforeStart(_ task: BrainTask, now: Date = Date()) -> Bool {
    computeTaskPhase(availableFrom: task.availableFrom, dueAt: task.dueAt, now: now) == .beforeStart
}

/// "Daily · Available 07:00–10:00"
func scheduleSummary(_ task: BrainTask) -> String {
    var parts = [task.recurrenceType.map { $0.prefix(1).uppercased() + $0.dropFirst() } ?? "One-time"]
    switch (task.availableFrom, task.dueAt) {
    case let (from?, due?): parts.append("Available \(from)–\(due)")
    case let (from?, nil): parts.append("Available from \(from)")
    case let (nil, due?): parts.append("Due by \(due)")
    default: break
    }
    return parts.joined(separator: " · ")
}

/// The last two weeks of completions of a task.
struct CompletionHistory: View {
    let taskId: String
    @Environment(AppModel.self) private var model
    @State private var records: [CompletionRecord] = []

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            if !records.isEmpty {
                Text("Recent completions").font(.headline)
                ForEach(records) { r in
                    Label(formatTimestamp(r.completedAt), systemImage: "checkmark")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .reloading(on: ["task_completions"], db: model.db, id: taskId) {
            records = (try? await model.completionRepository.history(taskId: taskId)) ?? []
        }
    }
}

/// A circular progress ring with a label in the middle.
struct ProgressRing<Content: View>: View {
    let progress: Double
    var color: Color = Palette.primary
    @ViewBuilder let label: Content

    var body: some View {
        ZStack {
            Circle().stroke(Palette.surfaceContainerHigh, lineWidth: 10)
            Circle()
                .trim(from: 0, to: min(1, max(0, progress)))
                .stroke(color, style: StrokeStyle(lineWidth: 10, lineCap: .round))
                .rotationEffect(.degrees(-90))
            label
        }
        .frame(width: 200, height: 200)
    }
}
