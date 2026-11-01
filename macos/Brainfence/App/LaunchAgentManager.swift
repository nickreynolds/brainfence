import AppKit
import OSLog
import ServiceManagement

/// Keeps Brainfence running: a launchd agent (bundled in
/// Contents/Library/LaunchAgents) starts it at login and relaunches it if it
/// is force-quit or crashes. While the agent is on, Quit only closes the
/// window, so the app exits normally (exit 0, left alone) only once it's off.
@MainActor
@Observable
final class LaunchAgentManager {
    nonisolated static let label = "dev.brainfence.macos.agent"
    private nonisolated static let plistName = "\(label).plist"

    private(set) var status: SMAppService.Status = .notRegistered
    private(set) var lastError: String?

    @ObservationIgnored private let service = SMAppService.agent(plistName: plistName)
    @ObservationIgnored private let logger = Logger(subsystem: "dev.brainfence.macos", category: "launch")

    /// True when this process was started by our launchd agent (launchd sets
    /// XPC_SERVICE_NAME to the job label).
    nonisolated static var isLaunchdManaged: Bool {
        ProcessInfo.processInfo.environment["XPC_SERVICE_NAME"] == label
    }

    /// Only the release app installed in an Applications folder registers the
    /// agent; a debug build run from DerivedData must not become the copy
    /// launchd starts at login.
    var canRegister: Bool {
        let path = Bundle.main.bundlePath
        let inApplications = path.hasPrefix("/Applications/")
            || path.hasPrefix(FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Applications").path + "/")
        return Bundle.main.bundleIdentifier == "dev.brainfence.macos" && inApplications
    }

    func registerIfNeeded() {
        refresh()
        guard canRegister, status != .enabled else { return }
        do {
            try service.register()
            lastError = nil
        } catch {
            // .requiresApproval is reported as an error on first registration;
            // the user approves under System Settings → General → Login Items.
            lastError = error.localizedDescription
            logger.error("agent registration: \(error.localizedDescription)")
        }
        refresh()
    }

    func refresh() {
        status = service.status
    }

    /// True while the agent is on: Quit then only closes the window, since
    /// the copy launchd watches is the one doing the blocking. Turning the
    /// agent off in Login Items is how Brainfence is stopped for good.
    /// Reflects the last `refresh()`.
    var keepsRunning: Bool { canRegister && status == .enabled }

    /// Asks launchd to start its copy of the app, which then takes over from
    /// this one (a copy opened from Finder isn't relaunched if killed).
    /// False when the agent is off or launchd refused.
    func startManagedInstance() -> Bool {
        refresh()
        guard keepsRunning else { return false }
        let launchctl = Process()
        launchctl.executableURL = URL(fileURLWithPath: "/bin/launchctl")
        launchctl.arguments = ["kickstart", "gui/\(getuid())/\(Self.label)"]
        do {
            try launchctl.run()
            launchctl.waitUntilExit()
        } catch {
            logger.error("kickstart: \(error.localizedDescription)")
            return false
        }
        return launchctl.terminationStatus == 0
    }

    func openLoginItemsSettings() {
        SMAppService.openSystemSettingsLoginItems()
    }

    var statusDescription: String {
        if !canRegister && status != .enabled {
            return "Install Brainfence in /Applications to start it at login"
        }
        return switch status {
        case .enabled: "Starts at login and keeps running. Turn it off in Login Items to quit."
        case .requiresApproval: "Needs approval in System Settings → Login Items"
        case .notRegistered: "Not registered"
        case .notFound: "Agent not found in app bundle"
        @unknown default: "Unknown"
        }
    }
}
