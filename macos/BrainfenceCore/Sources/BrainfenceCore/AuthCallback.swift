import Foundation

/// An email link that Supabase Auth redirects to brainfence://auth-callback.
/// Shared semantics: shared/CONTRACTS.md (Email links); cases:
/// shared/fixtures/auth-callback.json, which Android and Windows run too.
public enum AuthCallback: Equatable, Sendable {
    case code(String)
    case tokens(accessToken: String, refreshToken: String)
    case error(String)
    case none

    public static let url = URL(string: "brainfence://auth-callback")!
    public static let confirmedNotice = "Your email is confirmed. Sign in to continue."
    public static func errorNotice(_ message: String) -> String {
        "That link didn't work: \(message). Sign up again with the same email to get a new one."
    }

    /// Classifies `url`, or nil when it isn't an auth callback.
    public static func parse(_ url: URL) -> AuthCallback? {
        guard url.scheme?.lowercased() == "brainfence", url.host == "auth-callback" else { return nil }
        let query = formValues(URLComponents(url: url, resolvingAgainstBaseURL: false)?.percentEncodedQuery ?? "")
        let fragment = formValues(url.fragment ?? "")
        func param(_ key: String) -> String? {
            [query[key], fragment[key]].compactMap { $0 }.first { !$0.isEmpty }
        }

        if let message = param("error_description") ?? param("error") { return .error(message) }
        if let code = query["code"], !code.isEmpty { return .code(code) }
        if let access = fragment["access_token"], let refresh = fragment["refresh_token"], !access.isEmpty, !refresh.isEmpty {
            return .tokens(accessToken: access, refreshToken: refresh)
        }
        return AuthCallback.none
    }

    /// `a=1&b=x+y` with form decoding ('+' is a space), first value wins.
    private static func formValues(_ text: String) -> [String: String] {
        var out: [String: String] = [:]
        for pair in text.split(separator: "&") {
            let parts = pair.split(separator: "=", maxSplits: 1).map(String.init)
            let decode = { (s: String) in s.replacingOccurrences(of: "+", with: " ").removingPercentEncoding ?? s }
            let key = decode(parts[0])
            if out[key] == nil { out[key] = parts.count > 1 ? decode(parts[1]) : "" }
        }
        return out
    }
}
