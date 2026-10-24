import AppKit
import BrainfenceCore
import OSLog

/// Enforces app blocking with NSWorkspace — the macOS analogue of Android's
/// AccessibilityService. (Screen Time's ManagedSettings/FamilyControls APIs
/// are iOS-only; the macOS SDK marks them unavailable.)
///
/// Whenever a blocked app becomes active (launch, Cmd-Tab, Dock click) it is
/// hidden immediately and the shield explains why. Already-running blocked
/// apps are hidden as soon as a rule starts blocking.
@MainActor
final class AppEnforcer: BlockingEnforcer {
    /// Never hide these, whatever a rule says — doing so would lock the user
    /// out of the system, or out of Brainfence itself.
    private static let exempt: Set<String> = [
        Bundle.main.bundleIdentifier ?? "dev.brainfence.macos",
        "com.apple.finder",
        "com.apple.loginwindow",
        "com.apple.dock",
        "com.apple.systemuiserver",
        "com.apple.controlcenter",
        "com.apple.Spotlight",
    ]

    private let shield: ShieldPresenter
    private var state: BlockingState = .none
    /// Extra bundle IDs blocked by other enforcers (e.g. browsers that can't
    /// be inspected while websites are blocked), with the reason to show.
    private var extraBlocked: [String: ShieldPresenter.Subject] = [:]
    private var observers: [NSObjectProtocol] = []
    private var pollTimer: Timer?
    private let logger = Logger(subsystem: "dev.brainfence.macos", category: "apps")

    init(shield: ShieldPresenter) {
        self.shield = shield
        let center = NSWorkspace.shared.notificationCenter
        let onAppEvent: @Sendable (Notification) -> Void = { [weak self] note in
            guard let app = note.userInfo?[NSWorkspace.applicationUserInfoKey] as? NSRunningApplication else { return }
            MainActor.assumeIsolated { self?.check(app) }
        }
        observers = [
            center.addObserver(forName: NSWorkspace.didActivateApplicationNotification, object: nil, queue: .main, using: onAppEvent),
            center.addObserver(forName: NSWorkspace.didLaunchApplicationNotification, object: nil, queue: .main, using: onAppEvent),
            center.addObserver(forName: NSWorkspace.didUnhideApplicationNotification, object: nil, queue: .main, using: onAppEvent),
        ]
        // Backstop for anything the notifications miss (e.g. an app activated
        // while we were busy): check the frontmost app every couple of seconds.
        let timer = Timer(timeInterval: 2, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated {
                if let front = NSWorkspace.shared.frontmostApplication { self?.check(front) }
            }
        }
        timer.tolerance = 0.5
        RunLoop.main.add(timer, forMode: .common)
        pollTimer = timer
    }

    func apply(_ state: BlockingState) {
        self.state = state
        sweep()
    }

    /// Blocks additional apps on behalf of another enforcer.
    func setExtraBlocked(_ subjects: [String: ShieldPresenter.Subject]) {
        guard subjects.keys.sorted() != extraBlocked.keys.sorted() else {
            extraBlocked = subjects
            return
        }
        extraBlocked = subjects
        sweep()
    }

    func isBlocked(bundleID: String) -> Bool {
        !Self.exempt.contains(bundleID) && (state.blockedBundleIDs.contains(bundleID) || extraBlocked[bundleID] != nil)
    }

    /// Hides every running blocked app; only the one in use gets a shield.
    private func sweep() {
        for app in NSWorkspace.shared.runningApplications {
            guard let id = app.bundleIdentifier, isBlocked(bundleID: id) else { continue }
            if app.isActive {
                check(app)
            } else if !app.isHidden {
                app.hide()
            }
        }
    }

    /// Hides `app` if it's blocked and visible, and shows the shield.
    private func check(_ app: NSRunningApplication) {
        guard let id = app.bundleIdentifier, isBlocked(bundleID: id) else { return }
        guard app.isActive || !app.isHidden else { return }
        let wasInUse = app.isActive

        if !app.hide() {
            // A few apps refuse to hide (e.g. some full-screen windows). Ask
            // them to quit instead — a normal quit, so they can save state.
            logger.warning("\(id) refused to hide; asking it to quit")
            app.terminate()
        }

        // Background launches (login items etc.) are hidden quietly.
        guard wasInUse else { return }
        if let extra = extraBlocked[id], !state.blockedBundleIDs.contains(id) {
            shield.show(extra)
        } else {
            shield.show(.app(bundleID: id, name: app.localizedName ?? id, blocks: state.blocks(forBundleID: id)))
        }
    }
}
