import AppKit
import BrainfenceCore
import Foundation

/// Delivers contemplations as notifications at the day's slots. The slots
/// derive deterministically from a per-install seed, so rescheduling is
/// stateless and safe at any time (launch, settings change, delivery, wake).
/// Port of Android's ContemplationScheduler / ContemplationNotifier. The
/// schedule is per device; decks and delivery history sync.
@MainActor
@Observable
final class ContemplationScheduler {
    struct Settings: Codable, Equatable, Sendable {
        var enabled = false
        var perDay = 5
        var windowStart = "08:00"
        var windowEnd = "21:00"
    }

    static let maxPerDay = 24

    private(set) var settings: Settings
    private(set) var nextDelivery: Date?

    @ObservationIgnored private let repository: ContemplationRepository
    @ObservationIgnored private let notifications: NotificationService
    @ObservationIgnored private let log: DebugLog
    @ObservationIgnored var userId: @MainActor () -> String? = { nil }
    @ObservationIgnored private var timer: Timer?
    @ObservationIgnored private var wakeObserver: NSObjectProtocol?

    private static let settingsKey = "contemplationSettings"
    private static let seedKey = "contemplationSeed"
    private static let lastSlotKey = "lastDeliveredContemplationSlot"

    init(repository: ContemplationRepository, notifications: NotificationService, log: DebugLog) {
        self.repository = repository
        self.notifications = notifications
        self.log = log
        if let data = UserDefaults.standard.data(forKey: Self.settingsKey),
           let saved = try? JSONDecoder().decode(Settings.self, from: data) {
            settings = saved
        } else {
            settings = Settings()
        }
    }

    func start() {
        wakeObserver = NSWorkspace.shared.notificationCenter.addObserver(
            forName: NSWorkspace.didWakeNotification, object: nil, queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated { self?.reschedule() }
        }
        reschedule()
    }

    func update(_ settings: Settings) {
        self.settings = settings
        UserDefaults.standard.set(try? JSONEncoder().encode(settings), forKey: Self.settingsKey)
        reschedule()
    }

    private var seed: Int {
        let defaults = UserDefaults.standard
        if let seed = defaults.object(forKey: Self.seedKey) as? Int { return seed }
        let seed = Int.random(in: 0..<Int(Int32.max))
        defaults.set(seed, forKey: Self.seedKey)
        return seed
    }

    private var config: ContemplationScheduleConfig? {
        guard let start = TimeOfDay(settings.windowStart), let end = TimeOfDay(settings.windowEnd) else { return nil }
        return ContemplationScheduleConfig(perDay: settings.perDay, windowStart: start.seconds, windowEnd: end.seconds)
    }

    func reschedule(after: Date = Date()) {
        timer?.invalidate()
        timer = nil
        guard settings.enabled, let config,
              let next = nextContemplationSlot(after: after, config: config, seed: seed) else {
            nextDelivery = nil
            log.log("contemplation", "No contemplation scheduled (enabled=\(settings.enabled))")
            return
        }
        nextDelivery = next
        // Timers drift across sleep: wait at most 15 minutes, then re-check.
        let wait = min(max(0, next.timeIntervalSinceNow), 15 * 60)
        let t = Timer(timeInterval: wait, repeats: false) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self else { return }
                if Date().addingTimeInterval(1) < next {
                    self.reschedule(after: after)
                } else {
                    self.onSlot(next)
                }
            }
        }
        RunLoop.main.add(t, forMode: .common)
        timer = t
        log.log("contemplation", "Next contemplation scheduled for \(next.formatted(date: .abbreviated, time: .shortened))")
    }

    private func onSlot(_ slot: Date) {
        // Schedule the next slot first so the chain survives a failed delivery.
        reschedule(after: max(Date(), slot))
        guard settings.enabled else { return }
        let defaults = UserDefaults.standard
        guard slot.timeIntervalSince1970 > defaults.double(forKey: Self.lastSlotKey) else { return }
        defaults.set(slot.timeIntervalSince1970, forKey: Self.lastSlotKey)
        Task { _ = await deliverNow() }
    }

    /// Delivers one now. False (and logged) when nothing could be shown.
    @discardableResult
    func deliverNow() async -> Bool {
        guard let userId = userId() else {
            log.log("contemplation", "Skipped delivery: no one is signed in")
            return false
        }
        guard await notifications.isAuthorized() else {
            log.log("contemplation", "Skipped delivery: notifications aren't allowed")
            return false
        }
        do {
            guard let choice = try await repository.pickNext() else {
                log.log("contemplation", "Skipped delivery: no enabled deck with items")
                return false
            }
            let deliveryId = try await repository.recordDelivery(choice, userId: userId)
            let item = choice.item
            let body = item.attribution.map { "\(item.body)\n— \($0)" } ?? item.body
            notifications.postContemplation(
                title: item.title ?? choice.deck.name,
                subtitle: item.title == nil ? nil : choice.deck.name,
                body: body,
                deliveryId: deliveryId,
                affirmLabel: choice.deck.affirmLabel
            )
            log.log("contemplation", "Delivered '\(item.title ?? String(item.body.prefix(40)))' from '\(choice.deck.name)'")
            return true
        } catch {
            log.log("contemplation", "Delivery failed: \(error.localizedDescription)")
            return false
        }
    }

    func respond(deliveryId: String) {
        Task {
            do {
                try await repository.recordResponse(deliveryId: deliveryId, response: contemplationAffirmed)
                log.log("contemplation", "Affirmed delivery \(deliveryId)")
            } catch {
                log.log("contemplation", "Couldn't record a response: \(error.localizedDescription)")
            }
        }
    }
}
