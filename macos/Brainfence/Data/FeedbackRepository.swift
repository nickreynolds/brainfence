import BrainfenceCore
import Foundation
import PowerSync

/// Sends feedback through the insert-only `feedback` table: queued locally,
/// uploaded when online, never synced back. Port of Android's
/// FeedbackRepository. See shared/CONTRACTS.md (Feedback).
struct FeedbackRepository: Sendable {
    let db: PowerSyncDatabaseProtocol

    struct EmptyMessage: LocalizedError {
        var errorDescription: String? { "Write something first (up to \(Feedback.maxLength) characters)." }
    }

    func send(_ message: String, source: Feedback.Source, userId: String) async throws {
        guard let text = Feedback.normalize(message) else { throw EmptyMessage() }
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String
        try await db.execute(
            sql: """
                INSERT INTO feedback (id, user_id, message, platform, app_version, source, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
            parameters: [
                UUID().uuidString.lowercased(), userId, text, BlockedApp.macOS, version, source.rawValue,
                Timestamp.format(Date()),
            ]
        )
    }
}
