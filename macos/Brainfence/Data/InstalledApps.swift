import AppKit

/// Discovers installed applications for the rule editor's app picker.
struct InstalledApp: Identifiable, Hashable, Sendable {
    let bundleID: String
    let name: String
    let url: URL
    var id: String { bundleID }
}

enum InstalledApps {
    private static let searchRoots: [URL] = [
        URL(fileURLWithPath: "/Applications"),
        URL(fileURLWithPath: "/Applications/Utilities"),
        URL(fileURLWithPath: "/System/Applications"),
        URL(fileURLWithPath: "/System/Applications/Utilities"),
        FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Applications"),
    ]

    /// All .app bundles in the standard locations (one level deep, plus one
    /// level of sub-folders such as "/Applications/Setapp"), sorted by name.
    static func scan() async -> [InstalledApp] {
        await Task.detached(priority: .userInitiated) {
            let fm = FileManager.default
            var found: [String: InstalledApp] = [:]
            func consider(_ url: URL) {
                guard url.pathExtension == "app", let bundle = Bundle(url: url), let id = bundle.bundleIdentifier,
                      id != Bundle.main.bundleIdentifier else { return }
                let name = fm.displayName(atPath: url.path).replacingOccurrences(of: ".app", with: "")
                if found[id] == nil { found[id] = InstalledApp(bundleID: id, name: name, url: url) }
            }
            for root in searchRoots {
                guard let items = try? fm.contentsOfDirectory(at: root, includingPropertiesForKeys: [.isDirectoryKey], options: [.skipsHiddenFiles]) else { continue }
                for item in items {
                    if item.pathExtension == "app" {
                        consider(item)
                    } else if (try? item.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) == true,
                              let nested = try? fm.contentsOfDirectory(at: item, includingPropertiesForKeys: nil, options: [.skipsHiddenFiles]) {
                        nested.forEach(consider)
                    }
                }
            }
            return found.values.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
        }.value
    }

    static func app(at url: URL) -> InstalledApp? {
        guard let bundle = Bundle(url: url), let id = bundle.bundleIdentifier else { return nil }
        let name = FileManager.default.displayName(atPath: url.path).replacingOccurrences(of: ".app", with: "")
        return InstalledApp(bundleID: id, name: name, url: url)
    }

    @MainActor
    static func displayName(forBundleID id: String) -> String {
        if let url = NSWorkspace.shared.urlForApplication(withBundleIdentifier: id) {
            return FileManager.default.displayName(atPath: url.path).replacingOccurrences(of: ".app", with: "")
        }
        return id
    }

    @MainActor
    static func icon(forBundleID id: String) -> NSImage? {
        guard let url = NSWorkspace.shared.urlForApplication(withBundleIdentifier: id) else { return nil }
        return NSWorkspace.shared.icon(forFile: url.path)
    }
}
