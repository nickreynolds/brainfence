import PowerSync
import SwiftUI

struct RootView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        switch model.session.state {
        case .loading:
            ProgressView()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        case .signedOut:
            SignInView()
        case .signedIn:
            MainView()
        }
    }
}

struct MainView: View {
    enum Section: String, CaseIterable, Identifiable {
        case today = "Today"
        case rules = "Blocking Rules"
        case settings = "Settings"

        var id: String { rawValue }
        var icon: String {
            switch self {
            case .today: "checklist"
            case .rules: "lock.shield"
            case .settings: "gearshape"
            }
        }
    }

    @State private var section: Section = .today
    @Environment(AppModel.self) private var model

    var body: some View {
        NavigationSplitView {
            List(Section.allCases, selection: $section) { s in
                Label(s.rawValue, systemImage: s.icon).tag(s)
            }
            .navigationSplitViewColumnWidth(min: 180, ideal: 200)
            .safeAreaInset(edge: .bottom) {
                SyncStatusBadge()
                    .padding(12)
            }
        } detail: {
            switch section {
            case .today: TodayView()
            case .rules: RulesView()
            case .settings: SettingsView()
            }
        }
    }
}

struct SignInView: View {
    @Environment(AppModel.self) private var model
    @State private var email = ""
    @State private var password = ""
    @State private var isWorking = false
    @State private var error: String?

    var body: some View {
        VStack(spacing: 20) {
            Image(systemName: "lock.shield")
                .font(.system(size: 48))
                .foregroundStyle(.tint)
            Text("Sign in to Brainfence")
                .font(.title2.weight(.semibold))
            Text("Use the same account as on your phone. Your tasks and blocking rules sync automatically.")
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
                .frame(maxWidth: 320)

            Form {
                TextField("Email", text: $email)
                    .textContentType(.username)
                SecureField("Password", text: $password)
                    .textContentType(.password)
                    .onSubmit(signIn)
            }
            .formStyle(.columns)
            .frame(width: 300)

            if let error {
                Text(error)
                    .foregroundStyle(.red)
                    .font(.callout)
                    .frame(maxWidth: 320)
            }

            Button(action: signIn) {
                if isWorking { ProgressView().controlSize(.small) } else { Text("Sign In") }
            }
            .keyboardShortcut(.defaultAction)
            .disabled(isWorking || email.isEmpty || password.isEmpty)
        }
        .padding(40)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func signIn() {
        guard !email.isEmpty, !password.isEmpty else { return }
        isWorking = true
        error = nil
        Task {
            do {
                try await model.session.signIn(email: email.trimmingCharacters(in: .whitespaces), password: password)
            } catch {
                self.error = error.localizedDescription
            }
            isWorking = false
        }
    }
}

/// Compact sync indicator for the sidebar.
struct SyncStatusBadge: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let status = model.db.currentStatus.observable
        HStack(spacing: 6) {
            Circle()
                .fill(status.anyError != nil ? .red : status.connected ? .green : .orange)
                .frame(width: 8, height: 8)
            Text(label(status))
                .font(.caption)
                .foregroundStyle(.secondary)
            Spacer()
        }
    }

    private func label(_ status: ObservableSyncStatus) -> String {
        if status.anyError != nil { return "Sync error" }
        if status.connecting { return "Connecting…" }
        if !status.connected { return "Offline" }
        if status.uploading || status.downloading { return "Syncing…" }
        if let last = status.lastSyncedAt {
            return "Synced \(last.formatted(.relative(presentation: .named)))"
        }
        return "Connected"
    }
}
