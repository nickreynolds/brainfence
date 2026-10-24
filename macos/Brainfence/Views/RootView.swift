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
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var navigation = model.navigation
        NavigationSplitView {
            List(AppNavigation.Section.allCases, selection: Binding<AppNavigation.Section?>(
                get: { navigation.section },
                set: { if let s = $0 { navigation.section = s } }
            )) { s in
                Label(s.rawValue, systemImage: s.icon).tag(s)
            }
            .navigationSplitViewColumnWidth(min: 180, ideal: 200)
            .safeAreaInset(edge: .bottom) {
                SyncStatusBadge()
                    .padding(12)
            }
        } detail: {
            switch navigation.section {
            case .tasks: TasksView()
            case .journal: NavigationStack { JournalHistoryView() }
            case .reports: ReportsView()
            case .contemplations: ContemplationsView()
            case .rules: RulesView()
            case .debug: NavigationStack { DebugView() }
            case .settings: SettingsView()
            }
        }
        .sheet(item: $navigation.editor) { target in
            TaskEditorView(taskId: target.taskId)
        }
    }
}

struct SignInView: View {
    @Environment(AppModel.self) private var model
    @State private var email = ""
    @State private var password = ""
    @State private var isSigningUp = false
    @State private var isWorking = false
    @State private var error: String?
    @State private var notice: String?

    var body: some View {
        VStack(spacing: 20) {
            Image(systemName: "lock.shield")
                .font(.system(size: 48))
                .foregroundStyle(.tint)
            Text(isSigningUp ? "Create a Brainfence account" : "Sign in to Brainfence")
                .font(.title2.weight(.semibold))
            Text(isSigningUp
                ? "Use this account on all your devices. Your tasks and blocking rules sync automatically."
                : "Use the same account as on your phone. Your tasks and blocking rules sync automatically.")
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
                .frame(maxWidth: 320)

            Form {
                TextField("Email", text: $email)
                    .textContentType(.username)
                SecureField("Password", text: $password)
                    .textContentType(.password)
                    .onSubmit(submit)
            }
            .formStyle(.columns)
            .frame(width: 300)

            if let error {
                Text(error)
                    .foregroundStyle(Palette.error)
                    .font(.callout)
                    .frame(maxWidth: 320)
            }
            if let notice {
                Text(notice)
                    .font(.callout)
                    .frame(maxWidth: 320)
            }

            Button(action: submit) {
                if isWorking { ProgressView().controlSize(.small) } else { Text(isSigningUp ? "Create Account" : "Sign In") }
            }
            .keyboardShortcut(.defaultAction)
            .disabled(isWorking || email.isEmpty || password.isEmpty)

            Button(isSigningUp ? "Already have an account? Sign in" : "No account yet? Create one") {
                isSigningUp.toggle()
                error = nil
                notice = nil
            }
            .buttonStyle(.link)
        }
        .padding(40)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func submit() {
        guard !email.isEmpty, !password.isEmpty else { return }
        isWorking = true
        error = nil
        notice = nil
        let email = email.trimmingCharacters(in: .whitespaces)
        Task {
            do {
                if isSigningUp {
                    let signedIn = try await model.session.signUp(email: email, password: password)
                    if !signedIn {
                        notice = "Check your email to confirm your account, then sign in."
                        isSigningUp = false
                    }
                } else {
                    try await model.session.signIn(email: email, password: password)
                }
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
