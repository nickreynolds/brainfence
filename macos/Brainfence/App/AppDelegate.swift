import AppKit
import OSLog
import SwiftUI

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {
    let model = AppModel()
    private var mainWindow: NSWindow?
    /// Set when the system is logging out / shutting down, or when this
    /// instance hands over to the launchd-managed one.
    private var allowTermination = false
    private let logger = Logger(subsystem: "dev.brainfence.macos", category: "app")

    private static let yieldNotification = Notification.Name("dev.brainfence.macos.yield")
    private static let showNotification = Notification.Name("dev.brainfence.macos.show")

    func applicationDidFinishLaunching(_ notification: Notification) {
        guard ensureSingleInstance() else { return }

        model.openMainWindow = { [weak self] in self?.showMainWindow() }
        model.start()

        NSWorkspace.shared.notificationCenter.addObserver(
            forName: NSWorkspace.willPowerOffNotification, object: nil, queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated { self?.allowTermination = true }
        }
        let distributed = DistributedNotificationCenter.default()
        distributed.addObserver(forName: Self.showNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.showMainWindow() }
        }
        distributed.addObserver(forName: Self.yieldNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.yieldToManagedInstance() }
        }

        if case .signedOut = model.session.state { showMainWindow() }
        // The session state resolves asynchronously; show sign-in once known.
        Task { @MainActor in
            try? await Task.sleep(for: .seconds(1.5))
            if case .signedOut = model.session.state { showMainWindow() }
        }
    }

    /// One Brainfence per user. The launchd-managed instance (which is
    /// relaunched if killed) always wins; a manually started one hands over.
    private func ensureSingleInstance() -> Bool {
        let others = NSRunningApplication
            .runningApplications(withBundleIdentifier: Bundle.main.bundleIdentifier ?? "")
            .filter { $0 != NSRunningApplication.current }
        guard !others.isEmpty else { return true }

        let distributed = DistributedNotificationCenter.default()
        if LaunchAgentManager.isLaunchdManaged {
            distributed.postNotificationName(Self.yieldNotification, object: nil, userInfo: nil, deliverImmediately: true)
            return true
        }
        distributed.postNotificationName(Self.showNotification, object: nil, userInfo: nil, deliverImmediately: true)
        allowTermination = true
        exit(0)
    }

    private func yieldToManagedInstance() {
        guard !LaunchAgentManager.isLaunchdManaged else { return }
        logger.info("launchd-managed instance started; handing over")
        allowTermination = true
        exit(0)
    }

    // MARK: Quit protection

    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        if allowTermination { return .terminateNow }
        guard model.engine.state.isBlocking else { return .terminateNow }

        NSApp.activate(ignoringOtherApps: true)
        let alert = NSAlert()
        alert.messageText = "Brainfence is blocking right now"
        let rules = model.engine.state.activeBlocks.map(\.rule.name).joined(separator: ", ")
        alert.informativeText = "It can't be quit while “\(rules)” is in effect. Complete the required tasks first."
        alert.addButton(withTitle: "OK")
        alert.addButton(withTitle: "Show Tasks")
        if alert.runModal() == .alertSecondButtonReturn { showMainWindow() }
        return .terminateCancel
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { false }

    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        showMainWindow()
        return true
    }

    // MARK: Main window

    func showMainWindow() {
        if mainWindow == nil {
            let window = NSWindow(
                contentRect: NSRect(x: 0, y: 0, width: 880, height: 600),
                styleMask: [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView],
                backing: .buffered, defer: false
            )
            window.title = "Brainfence"
            window.isReleasedWhenClosed = false
            window.setFrameAutosaveName("BrainfenceMain")
            window.contentView = NSHostingView(rootView: RootView().environment(model))
            window.center()
            NotificationCenter.default.addObserver(forName: NSWindow.willCloseNotification, object: window, queue: .main) { _ in
                // Back to a menu-bar-only app once the window closes.
                MainActor.assumeIsolated { _ = NSApp.setActivationPolicy(.accessory) }
            }
            mainWindow = window
        }
        NSApp.setActivationPolicy(.regular)
        NSApp.activate(ignoringOtherApps: true)
        mainWindow?.makeKeyAndOrderFront(nil)
    }
}
