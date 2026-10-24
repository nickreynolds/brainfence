import AppKit
import PowerSync
import SwiftUI

struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @AppStorage(WebEnforcer.blockUnsupportedBrowsersKey) private var blockUnsupportedBrowsers = true

    var body: some View {
        Form {
            Section("Account") {
                if case .signedIn(_, let email) = model.session.state {
                    LabeledContent("Signed in as", value: email ?? "—")
                }
                Button("Sign Out") { Task { await model.session.signOut() } }
                Text("Signing out stops syncing. Rules already on this Mac keep blocking.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Section("Sync") {
                SyncDetails()
            }

            Section("Keep Brainfence running") {
                LabeledContent("Login agent", value: model.launchAgent.statusDescription)
                HStack {
                    Button("Register Again") { model.launchAgent.registerIfNeeded() }
                        .disabled(!model.launchAgent.canRegister)
                    Button("Open Login Items Settings") { model.launchAgent.openLoginItemsSettings() }
                }
                if let error = model.launchAgent.lastError {
                    Text(error).font(.caption).foregroundStyle(.secondary)
                }
            }
            .onAppear { model.launchAgent.refresh() }

            Section("Website blocking") {
                BrowserAccessList()
                Toggle("Block browsers that can't be checked (Firefox, Zen…) while websites are blocked", isOn: $blockUnsupportedBrowsers)
                Button("Open Automation Settings") {
                    NSWorkspace.shared.open(URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Automation")!)
                }
            }

            if !model.uploadLog.items.isEmpty {
                Section("Rejected uploads") {
                    ForEach(model.uploadLog.items) { item in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(item.summary).font(.caption.monospaced())
                            Text(item.error).font(.caption2).foregroundStyle(.secondary).lineLimit(3)
                        }
                    }
                }
            }
        }
        .formStyle(.grouped)
        .navigationTitle("Settings")
    }
}

private struct SyncDetails: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let status = model.db.currentStatus.observable
        LabeledContent("Status", value: status.connected ? "Connected" : status.connecting ? "Connecting…" : "Disconnected")
        LabeledContent("Last synced", value: status.lastSyncedAt?.formatted(date: .abbreviated, time: .standard) ?? "Never")
        if let error = status.anyError {
            LabeledContent("Error") {
                Text(String(describing: error)).font(.caption).foregroundStyle(Palette.error).lineLimit(4)
            }
        }
        LabeledContent("Tasks / rules on this Mac", value: "\(model.engine.tasks.count) / \(model.engine.rules.count)")
        if let refreshed = model.engine.lastRefresh {
            LabeledContent("Last evaluated", value: refreshed.formatted(date: .omitted, time: .standard))
        }
        if let error = model.engine.lastError {
            Text(error).font(.caption).foregroundStyle(Palette.error)
        }
    }
}

/// Automation permission per running browser.
private struct BrowserAccessList: View {
    @Environment(AppModel.self) private var model
    @State private var rows: [(id: String, name: String, status: String)] = []

    var body: some View {
        Group {
            if rows.isEmpty {
                Text("Open a browser to check its permission.").foregroundStyle(.secondary)
            }
            ForEach(rows, id: \.id) { row in
                LabeledContent(row.name, value: row.status)
            }
        }
        .task {
            while !Task.isCancelled {
                refresh()
                try? await Task.sleep(for: .seconds(2))
            }
        }
    }

    private func refresh() {
        rows = NSWorkspace.shared.runningApplications.compactMap { app in
            guard let id = app.bundleIdentifier, let family = WebEnforcer.browsers[id] else { return nil }
            let name = app.localizedName ?? id
            guard family != nil else { return (id, name, "Can't be checked") }
            let status = switch model.webEnforcer.accessStatus(for: id) {
            case .granted: "Allowed"
            case .denied: "Denied — blocked while websites are blocked"
            case .undetermined: "Not yet allowed"
            case .notRunning: "Not running"
            }
            return (id, name, status)
        }
    }
}
