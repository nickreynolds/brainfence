import Foundation

// Feedback the user sends from the app. Shared semantics: shared/CONTRACTS.md
// (Feedback); cases: shared/fixtures/feedback.json, which Android and Windows run too.

public enum Feedback {
    /// In Unicode code points, as Postgres counts them.
    public static let maxLength = 4000

    /// Where it was sent from (the `source` column).
    public enum Source: String, Sendable {
        case settings
        case setupGuide = "setup_guide"
    }

    /// Length in Unicode code points (an emoji is one).
    public static func length(_ text: String) -> Int { text.unicodeScalars.count }

    /// The message as stored: trimmed, or nil when it can't be sent.
    public static func normalize(_ message: String) -> String? {
        let trimmed = message.trimmingCharacters(in: .whitespacesAndNewlines)
        return (1...maxLength).contains(length(trimmed)) ? trimmed : nil
    }
}
