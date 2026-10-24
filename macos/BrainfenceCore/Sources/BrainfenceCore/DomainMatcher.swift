import Foundation

/// Matching of browser URLs against `blocked_domains` entries.
public enum DomainMatcher {
    /// Normalizes a user-entered domain ("https://www.Reddit.com/r/x" → "reddit.com").
    /// Returns nil when nothing usable remains.
    public static func normalize(_ raw: String) -> String? {
        var s = raw.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if let r = s.range(of: "://") { s = String(s[r.upperBound...]) }
        if let i = s.firstIndex(where: { "/?#".contains($0) }) { s = String(s[..<i]) }
        if let at = s.lastIndex(of: "@") { s = String(s[s.index(after: at)...]) }
        if let colon = s.lastIndex(of: ":") { s = String(s[..<colon]) }
        for prefix in ["*.", "www."] where s.hasPrefix(prefix) { s.removeFirst(prefix.count) }
        while s.hasSuffix(".") { s.removeLast() }
        return s.isEmpty || !s.contains(".") ? nil : s
    }

    /// The lowercase host of a URL string, or nil for non-web URLs.
    public static func host(ofURL urlString: String) -> String? {
        guard let url = URL(string: urlString),
              let scheme = url.scheme?.lowercased(), scheme == "http" || scheme == "https",
              let host = url.host?.lowercased(), !host.isEmpty
        else { return nil }
        return host
    }

    /// True if `host` is `domain` or one of its subdomains.
    public static func host(_ host: String, matches domain: String) -> Bool {
        let h = host.lowercased()
        return h == domain || h.hasSuffix("." + domain)
    }
}
