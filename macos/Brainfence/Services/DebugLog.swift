import Foundation
import OSLog

/// A local, unsynced log of what the background services did, shown on the
/// Debug screen. Kept for 48 hours, like Android's DebugLogRepository.
@MainActor
@Observable
final class DebugLog {
    struct Entry: Codable, Identifiable, Sendable {
        let id: UUID
        let at: Date
        let category: String
        let message: String
        let data: String?
    }

    /// Newest first.
    private(set) var entries: [Entry] = []

    @ObservationIgnored private let url: URL
    @ObservationIgnored private let logger = Logger(subsystem: "dev.brainfence.macos", category: "debug")

    private static let retention: TimeInterval = 48 * 3600
    private static let maxEntries = 2000

    init() {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let dir = base.appendingPathComponent(Bundle.main.bundleIdentifier ?? "dev.brainfence.macos", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        url = dir.appendingPathComponent("debug-log.json")
        if let data = try? Data(contentsOf: url), let saved = try? JSONDecoder().decode([Entry].self, from: data) {
            entries = saved
        }
        prune()
    }

    var categories: [String] { Array(Set(entries.map(\.category))).sorted() }

    func log(_ category: String, _ message: String, data: String? = nil) {
        logger.info("[\(category, privacy: .public)] \(message, privacy: .public)")
        entries.insert(Entry(id: UUID(), at: Date(), category: category, message: message, data: data), at: 0)
        prune()
        save()
    }

    func clear() {
        entries = []
        save()
    }

    private func prune() {
        let cutoff = Date().addingTimeInterval(-Self.retention)
        entries.removeAll { $0.at < cutoff }
        if entries.count > Self.maxEntries { entries.removeLast(entries.count - Self.maxEntries) }
    }

    private func save() {
        try? JSONEncoder().encode(entries).write(to: url, options: .atomic)
    }
}
