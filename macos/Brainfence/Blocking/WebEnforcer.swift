import AppKit
import BrainfenceCore
import CoreServices
import OSLog

/// Enforces `blocked_domains` by watching the active tab of the frontmost
/// browser via Apple Events and navigating it away from blocked sites.
///
/// Fails closed: while any domain is blocked, a browser we can't inspect —
/// one that doesn't support AppleScript (Firefox), or that Brainfence hasn't
/// been allowed to automate — is blocked as an app instead. Otherwise denying
/// the Automation permission would be a one-click bypass.
@MainActor
final class WebEnforcer: BlockingEnforcer {
    enum Family { case safari, chromium }

    /// Browsers we know about. `nil` family = cannot report its tab URL.
    static let browsers: [String: Family?] = [
        "com.apple.Safari": .safari,
        "com.apple.SafariTechnologyPreview": .safari,
        "com.google.Chrome": .chromium,
        "com.google.Chrome.beta": .chromium,
        "com.google.Chrome.dev": .chromium,
        "com.google.Chrome.canary": .chromium,
        "org.chromium.Chromium": .chromium,
        "com.brave.Browser": .chromium,
        "com.brave.Browser.beta": .chromium,
        "com.brave.Browser.nightly": .chromium,
        "com.microsoft.edgemac": .chromium,
        "com.microsoft.edgemac.Beta": .chromium,
        "com.microsoft.edgemac.Dev": .chromium,
        "company.thebrowser.Browser": .chromium, // Arc
        "com.vivaldi.Vivaldi": .chromium,
        "com.operasoftware.Opera": .chromium,
        "org.mozilla.firefox": nil,
        "org.mozilla.firefoxdeveloperedition": nil,
        "org.mozilla.nightly": nil,
        "app.zen-browser.zen": nil,
        "io.gitlab.librewolf-community": nil,
        "org.torproject.torbrowser": nil,
    ]

    enum Access: Equatable { case granted, denied, undetermined, notRunning }

    static let blockUnsupportedBrowsersKey = "blockUnsupportedBrowsers"

    private let appEnforcer: AppEnforcer
    private let shield: ShieldPresenter
    private var state: BlockingState = .none
    private var timer: Timer?
    private var access: [String: (value: Access, checkedAt: Date)] = [:]
    private var consentRequested: Set<String> = []
    private var scripts: [String: NSAppleScript] = [:]
    private let logger = Logger(subsystem: "dev.brainfence.macos", category: "web")

    init(appEnforcer: AppEnforcer, shield: ShieldPresenter) {
        self.appEnforcer = appEnforcer
        self.shield = shield
        UserDefaults.standard.register(defaults: [Self.blockUnsupportedBrowsersKey: true])
    }

    func apply(_ state: BlockingState) {
        self.state = state
        if state.blockedDomains.isEmpty {
            timer?.invalidate()
            timer = nil
            appEnforcer.setExtraBlocked([:])
            return
        }
        if timer == nil {
            let t = Timer(timeInterval: 1, repeats: true) { [weak self] _ in
                MainActor.assumeIsolated { self?.tick() }
            }
            t.tolerance = 0.3
            RunLoop.main.add(t, forMode: .common)
            timer = t
        }
        tick()
    }

    private func tick() {
        guard !state.blockedDomains.isEmpty else { return }
        let blockUnsupported = UserDefaults.standard.bool(forKey: Self.blockUnsupportedBrowsersKey)

        // 1. Browsers we can't see into are blocked outright.
        var uninspectable: [String: ShieldPresenter.Subject] = [:]
        for app in NSWorkspace.shared.runningApplications {
            guard let id = app.bundleIdentifier, let family = Self.browsers[id] else { continue }
            let name = app.localizedName ?? id
            if family == nil {
                if blockUnsupported { uninspectable[id] = .unsupportedBrowser(name: name, blocks: state.activeBlocks) }
                continue
            }
            let a = accessStatus(for: id)
            if a == .undetermined { requestConsent(for: id) }
            if a == .denied || a == .undetermined {
                uninspectable[id] = .browserNeedsAccess(name: name, bundleID: id, blocks: state.activeBlocks)
            }
        }
        appEnforcer.setExtraBlocked(uninspectable)

        // 2. Inspect the frontmost browser's active tab.
        guard let front = NSWorkspace.shared.frontmostApplication,
              let id = front.bundleIdentifier,
              let family = Self.browsers[id] ?? nil,
              access[id]?.value == .granted,
              let url = run(.read, family: family, bundleID: id),
              let host = DomainMatcher.host(ofURL: url),
              state.blockedDomain(forHost: host) != nil
        else { return }

        logger.info("blocked \(host) in \(id)")
        if run(.leave, family: family, bundleID: id) == nil {
            front.hide()
        }
        shield.show(.website(host: host, browser: front.localizedName ?? id, blocks: state.blocks(forHost: host)))
    }

    // MARK: Automation permission

    func accessStatus(for bundleID: String) -> Access {
        if let cached = access[bundleID], Date().timeIntervalSince(cached.checkedAt) < (cached.value == .granted ? 30 : 2) {
            return cached.value
        }
        let value = Self.determineAccess(bundleID: bundleID, askUser: false)
        access[bundleID] = (value, Date())
        return value
    }

    /// Shows the system "Brainfence wants to control …" prompt. Blocks until
    /// the user answers, so it runs off the main thread.
    func requestConsent(for bundleID: String) {
        guard consentRequested.insert(bundleID).inserted else { return }
        Task.detached {
            let result = Self.determineAccess(bundleID: bundleID, askUser: true)
            await MainActor.run { [weak self] in
                self?.access[bundleID] = (result, Date())
                self?.tick()
            }
        }
    }

    nonisolated static func determineAccess(bundleID: String, askUser: Bool) -> Access {
        let target = NSAppleEventDescriptor(bundleIdentifier: bundleID)
        guard let desc = target.aeDesc else { return .undetermined }
        let status = AEDeterminePermissionToAutomateTarget(desc, AEEventClass(typeWildCard), AEEventID(typeWildCard), askUser)
        switch Int(status) {
        case Int(noErr): return .granted
        case Int(errAEEventNotPermitted): return .denied
        case Int(errAEEventWouldRequireUserConsent): return .undetermined
        case Int(procNotFound): return .notRunning
        default: return .undetermined
        }
    }

    // MARK: AppleScript

    private enum Action { case read, leave }

    /// Runs a tab script. Returns the result string (the URL for `.read`), or
    /// nil on failure. A 2 s AppleScript timeout keeps a hung browser from
    /// stalling the main thread.
    private func run(_ action: Action, family: Family, bundleID: String) -> String? {
        let key = "\(bundleID)|\(action)"
        let script: NSAppleScript
        if let cached = scripts[key] {
            script = cached
        } else {
            let tab = family == .safari ? "current tab of front window" : "active tab of front window"
            let body = switch action {
            case .read: "return URL of \(tab)"
            case .leave: "set URL of \(tab) to \"about:blank\"\nreturn \"ok\""
            }
            let source = """
                with timeout of 2 seconds
                    tell application id "\(bundleID)"
                        if (count of windows) is 0 then return ""
                        \(body)
                    end tell
                end timeout
                """
            guard let compiled = NSAppleScript(source: source) else { return nil }
            scripts[key] = compiled
            script = compiled
        }

        var error: NSDictionary?
        let result = script.executeAndReturnError(&error)
        if let error {
            let code = error[NSAppleScript.errorNumber] as? Int ?? 0
            if code == Int(errAEEventNotPermitted) {
                access[bundleID] = (.denied, Date())
            }
            logger.debug("script \(key) failed: \(code)")
            return nil
        }
        return result.stringValue
    }
}
