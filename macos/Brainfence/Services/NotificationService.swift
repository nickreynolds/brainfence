import AppKit
import OSLog
import UserNotifications

/// Local notifications: task reminders and contemplations (with an "affirm"
/// action for decks that ask for one). Clicking a notification opens the
/// relevant screen.
@MainActor
final class NotificationService: NSObject, UNUserNotificationCenterDelegate {
    /// What a notification opens when clicked.
    enum Target: Sendable {
        case task(String)
        case contemplations
        case tasks
    }

    /// Called with the delivery id when a contemplation's affirm action is chosen.
    var onAffirm: @MainActor (String) -> Void = { _ in }
    var onOpen: @MainActor (Target) -> Void = { _ in }

    private let center = UNUserNotificationCenter.current()
    private var affirmCategories: [String: UNNotificationCategory] = [:]
    private let logger = Logger(subsystem: "dev.brainfence.macos", category: "notifications")

    private static let affirmAction = "affirm"

    func start() {
        center.delegate = self
        center.requestAuthorization(options: [.alert, .sound]) { [logger] granted, error in
            if let error { logger.error("notification authorization failed: \(error.localizedDescription)") }
            logger.info("notifications \(granted ? "allowed" : "not allowed")")
        }
    }

    /// Whether notifications are allowed, for settings and "send now" feedback.
    func isAuthorized() async -> Bool {
        let settings = await center.notificationSettings()
        return settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional
    }

    func post(title: String, body: String, target: Target = .tasks) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        content.userInfo = Self.userInfo(for: target)
        add(content)
    }

    /// A contemplation, with an affirm action labelled [affirmLabel] if given.
    func postContemplation(title: String, subtitle: String?, body: String, deliveryId: String, affirmLabel: String?) {
        let content = UNMutableNotificationContent()
        content.title = title
        if let subtitle { content.subtitle = subtitle }
        content.body = body
        var info = Self.userInfo(for: .contemplations)
        info["deliveryId"] = deliveryId
        content.userInfo = info
        if let affirmLabel { content.categoryIdentifier = affirmCategory(label: affirmLabel) }
        add(content)
    }

    private func add(_ content: UNMutableNotificationContent) {
        let request = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
        center.add(request) { [logger] error in
            if let error { logger.error("couldn't post notification: \(error.localizedDescription)") }
        }
    }

    /// Notification actions are fixed per category, and each deck can label
    /// its affirm button differently, so there's a category per label.
    private func affirmCategory(label: String) -> String {
        let id = "affirm.\(label)"
        if affirmCategories[id] == nil {
            let action = UNNotificationAction(identifier: Self.affirmAction, title: label, options: [])
            affirmCategories[id] = UNNotificationCategory(identifier: id, actions: [action], intentIdentifiers: [], options: [])
            center.setNotificationCategories(Set(affirmCategories.values))
        }
        return id
    }

    private static func userInfo(for target: Target) -> [String: String] {
        switch target {
        case .task(let id): ["open": "task", "taskId": id]
        case .contemplations: ["open": "contemplations"]
        case .tasks: ["open": "tasks"]
        }
    }

    // MARK: UNUserNotificationCenterDelegate

    /// Show notifications even while Brainfence is frontmost.
    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        [.banner, .sound, .list]
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        let action = response.actionIdentifier
        let info = response.notification.request.content.userInfo
        let open = info["open"] as? String
        let taskId = info["taskId"] as? String
        let deliveryId = info["deliveryId"] as? String
        await MainActor.run {
            if action == Self.affirmAction, let deliveryId {
                self.onAffirm(deliveryId)
                return
            }
            guard action == UNNotificationDefaultActionIdentifier else { return }
            switch open {
            case "task": self.onOpen(taskId.map(Target.task) ?? .tasks)
            case "contemplations": self.onOpen(.contemplations)
            default: self.onOpen(.tasks)
            }
        }
    }
}
