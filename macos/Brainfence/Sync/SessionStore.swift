import Foundation
import OSLog
import Supabase

/// Supabase auth state for the app. The session is persisted in the login
/// Keychain by supabase-swift, so it survives relaunches.
@MainActor
@Observable
final class SessionStore {
    enum State: Equatable {
        case loading
        case signedOut
        case signedIn(userId: String, email: String?)
    }

    private(set) var state: State = .loading

    @ObservationIgnored let client: SupabaseClient
    @ObservationIgnored private var observation: Task<Void, Never>?
    @ObservationIgnored private let logger = Logger(subsystem: "dev.brainfence.macos", category: "auth")

    init(client: SupabaseClient) {
        self.client = client
    }

    /// Starts following auth changes. `onChange` fires for every state change.
    func start(onChange: @escaping @MainActor (State) -> Void) {
        observation?.cancel()
        observation = Task { [weak self, client] in
            for await (event, session) in client.auth.authStateChanges {
                guard let self else { return }
                let next: State = if let session {
                    .signedIn(userId: session.user.id.uuidString.lowercased(), email: session.user.email)
                } else {
                    .signedOut
                }
                self.logger.info("auth event \(event.rawValue): \(String(describing: next))")
                if next != self.state {
                    self.state = next
                    onChange(next)
                }
            }
        }
    }

    /// The user id for local database writes. Uses the persisted session even
    /// when it has expired (e.g. offline): a local PowerSync write only needs
    /// the id, and it uploads once the session refreshes.
    var userIdForLocalWrites: String? {
        if case .signedIn(let id, _) = state { return id }
        return client.auth.currentSession?.user.id.uuidString.lowercased()
    }

    func signIn(email: String, password: String) async throws {
        try await client.auth.signIn(email: email, password: password)
    }

    func signOut() async {
        do {
            try await client.auth.signOut()
        } catch {
            logger.error("sign out failed: \(error.localizedDescription)")
        }
    }
}
