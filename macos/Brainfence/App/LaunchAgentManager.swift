import AppKit
import OSLog
import ServiceManagement

/// Keeps Brainfence running: a launchd agent (bundled in
/// Contents/Library/LaunchAgents) starts it at login and relaunches it if it
/// is force-quit or crashes. A normal quit (exit 0) is left alone.
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

    func openLoginItemsSettings() {
        SMAppService.openSystemSettingsLoginItems()
    }

    var statusDescription: String {
        if !canRegister && status != .enabled {
            return "Install Brainfence in /Applications to start it at login"
        }
        return switch status {
        case .enabled: "Starts at login and relaunches if quit unexpectedly"
        case .requiresApproval: "Needs approval in System Settings → Login Items"
        case .notRegistered: "Not registered"
        case .notFound: "Agent not found in app bundle"
        @unknown default: "Unknown"
        }
    }
}
