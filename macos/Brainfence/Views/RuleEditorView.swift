import AppKit
import BrainfenceCore
import SwiftUI

/// Create or edit a blocking rule. Only macOS apps are edited here; other
/// platforms' entries in `blocked_apps` are carried through unchanged.
/// Edits that could weaken an existing rule are time-locked.
struct RuleEditorView: View {
    let rule: BlockingRule?

    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss

    @State private var name = ""
    @State private var isActive = true
    @State private var macApps: [String] = []          // bundle IDs
    @State private var domains: [String] = []
    @State private var newDomain = ""
    @State private var conditionTaskIds: Set<String> = []
    @State private var conditionLogic = "all"
    @State private var showingAppPicker = false
    @State private var isSaving = false
    @State private var error: String?
    @State private var outcome: String?

    var body: some View {
        VStack(spacing: 0) {
            Form {
                Section {
                    TextField("Name", text: $name)
                    if rule != nil {
                        Toggle("Active", isOn: $isActive)
                    }
                }

                Section("Apps on this Mac") {
                    ForEach(macApps, id: \.self) { id in
                        HStack {
                            if let icon = InstalledApps.icon(forBundleID: id) {
                                Image(nsImage: icon).resizable().frame(width: 18, height: 18)
                            }
                            Text(InstalledApps.displayName(forBundleID: id))
                            Text(id).font(.caption).foregroundStyle(.secondary)
                            Spacer()
                            Button(role: .destructive) {
                                macApps.removeAll { $0 == id }
                            } label: { Image(systemName: "minus.circle") }
                                .buttonStyle(.borderless)
                        }
                    }
                    Button("Add Apps…") { showingAppPicker = true }
                    if let other = rule?.otherPlatformApps, !other.isEmpty {
                        Text("Also blocks \(other.count) phone app\(other.count == 1 ? "" : "s") — edit those on your phone.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }

                Section("Websites") {
                    ForEach(domains, id: \.self) { domain in
                        HStack {
                            Text(domain)
                            Spacer()
                            Button(role: .destructive) {
                                domains.removeAll { $0 == domain }
                            } label: { Image(systemName: "minus.circle") }
                                .buttonStyle(.borderless)
                        }
                    }
                    HStack {
                        TextField("example.com", text: $newDomain)
                            .onSubmit(addDomain)
                        Button("Add", action: addDomain)
                            .disabled(DomainMatcher.normalize(newDomain) == nil)
                    }
                    Text("Subdomains are included. Websites are blocked in Safari and Chromium browsers (Chrome, Arc, Brave, Edge…).")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                Section("Unblock when") {
                    Picker("Required", selection: $conditionLogic) {
                        Text("All of these are done").tag("all")
                        Text("Any of these is done").tag("any")
                    }
                    .pickerStyle(.radioGroup)
                    let tasks = model.engine.tasks.filter { $0.taskType != "shopping" }
                    if tasks.isEmpty {
                        Text("No tasks synced yet.").foregroundStyle(.secondary)
                    }
                    ForEach(tasks) { task in
                        Toggle(isOn: Binding(
                            get: { conditionTaskIds.contains(task.id) },
                            set: { on in if on { conditionTaskIds.insert(task.id) } else { conditionTaskIds.remove(task.id) } }
                        )) {
                            VStack(alignment: .leading) {
                                Text(task.title)
                                if let due = task.dueAt {
                                    Text("Blocks from \(due) if not done").font(.caption).foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                    // Condition tasks that aren't active any more still count (fail closed).
                    ForEach(Array(conditionTaskIds.filter { model.engine.task(id: $0) == nil }), id: \.self) { id in
                        Toggle("Unavailable task (\(id.prefix(8)))", isOn: Binding(
                            get: { true },
                            set: { if !$0 { conditionTaskIds.remove(id) } }
                        ))
                    }
                    if conditionTaskIds.isEmpty {
                        Text("With no tasks, the rule blocks whenever it's active.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }

                if let rule {
                    Section {
                        Text("Changes that only make this rule stricter apply immediately. Anything that could loosen it (removing apps or sites, changing tasks, deactivating) takes effect after \(rule.configLockHours) hours.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        if rule.hasPendingChanges {
                            PendingChangeBanner(rule: rule, now: Date())
                        }
                    }
                }
            }
            .formStyle(.grouped)

            Divider()
            HStack {
                if let error {
                    Text(error).foregroundStyle(.red).font(.callout)
                } else if let outcome {
                    Text(outcome).font(.callout)
                }
                Spacer()
                Button("Cancel") { dismiss() }
                    .keyboardShortcut(.cancelAction)
                Button(rule == nil ? "Create Rule" : "Save") { save() }
                    .keyboardShortcut(.defaultAction)
                    .disabled(isSaving || name.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            .padding()
        }
        .frame(width: 560, height: 640)
        .onAppear(perform: load)
        .sheet(isPresented: $showingAppPicker) {
            AppPickerView(selected: Set(macApps)) { picked in
                for id in picked where !macApps.contains(id) { macApps.append(id) }
            }
        }
    }

    private func load() {
        guard let rule else { return }
        name = rule.name
        isActive = rule.isActive
        macApps = rule.macOSBundleIDs
        domains = rule.blockedDomains
        conditionTaskIds = Set(rule.conditionTaskIds)
        conditionLogic = rule.conditionLogic
    }

    private func addDomain() {
        guard let domain = DomainMatcher.normalize(newDomain) else { return }
        if !domains.contains(domain) { domains.append(domain) }
        newDomain = ""
    }

    private func save() {
        isSaving = true
        error = nil
        let trimmedName = name.trimmingCharacters(in: .whitespaces)
        // Keep the conditions in a stable order: existing order first, then new ones.
        let existingOrder = rule?.conditionTaskIds ?? []
        let orderedIds = existingOrder.filter(conditionTaskIds.contains)
            + model.engine.tasks.map(\.id).filter { conditionTaskIds.contains($0) && !existingOrder.contains($0) }

        Task {
            do {
                if let rule {
                    // Preserve every other platform's entries verbatim, and existing
                    // macOS entries' raw JSON (which may carry extra fields).
                    let existingMac = Dictionary(
                        rule.blockedApps.filter { $0.platform == BlockedApp.macOS }.map { ($0.identifier, $0) },
                        uniquingKeysWith: { a, _ in a }
                    )
                    let apps = rule.otherPlatformApps + macApps.map { existingMac[$0] ?? .macOS(bundleID: $0) }
                    let change = RuleChange(
                        name: trimmedName, blockedApps: apps, blockedDomains: domains,
                        conditionTaskIds: orderedIds, conditionLogic: conditionLogic, isActive: isActive
                    )
                    switch try await model.submit(change, toRuleId: rule.id) {
                    case .noChange, .appliedNow:
                        dismiss()
                    case .scheduled(let at):
                        outcome = "This change loosens the rule, so it takes effect \(at.formatted(date: .abbreviated, time: .shortened))."
                    }
                } else {
                    try await model.createRule(
                        name: trimmedName, blockedApps: macApps.map { .macOS(bundleID: $0) }, blockedDomains: domains,
                        conditionTaskIds: orderedIds, conditionLogic: conditionLogic
                    )
                    dismiss()
                }
            } catch {
                self.error = error.localizedDescription
            }
            isSaving = false
        }
    }
}

/// Multi-select list of installed apps, with search and a Finder fallback.
private struct AppPickerView: View {
    let selected: Set<String>
    let onAdd: ([String]) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var apps: [InstalledApp] = []
    @State private var picked: Set<String> = []
    @State private var query = ""

    var body: some View {
        VStack(spacing: 0) {
            TextField("Search apps", text: $query)
                .textFieldStyle(.roundedBorder)
                .padding()
            List(filtered, selection: $picked) { app in
                HStack {
                    Image(nsImage: NSWorkspace.shared.icon(forFile: app.url.path))
                        .resizable().frame(width: 20, height: 20)
                    Text(app.name)
                    Spacer()
                    if selected.contains(app.bundleID) {
                        Text("Added").font(.caption).foregroundStyle(.secondary)
                    }
                }
                .tag(app.bundleID)
            }
            .overlay { if apps.isEmpty { ProgressView() } }
            Divider()
            HStack {
                Button("Choose in Finder…", action: chooseInFinder)
                Spacer()
                Button("Cancel") { dismiss() }
                    .keyboardShortcut(.cancelAction)
                Button("Add \(picked.count)") {
                    onAdd(Array(picked))
                    dismiss()
                }
                .keyboardShortcut(.defaultAction)
                .disabled(picked.isEmpty)
            }
            .padding()
        }
        .frame(width: 420, height: 520)
        .task { apps = await InstalledApps.scan() }
    }

    private var filtered: [InstalledApp] {
        query.isEmpty ? apps : apps.filter { $0.name.localizedCaseInsensitiveContains(query) || $0.bundleID.localizedCaseInsensitiveContains(query) }
    }

    private func chooseInFinder() {
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [.application]
        panel.allowsMultipleSelection = true
        panel.directoryURL = URL(fileURLWithPath: "/Applications")
        guard panel.runModal() == .OK else { return }
        let ids = panel.urls.compactMap { InstalledApps.app(at: $0)?.bundleID }
        if !ids.isEmpty {
            onAdd(ids)
            dismiss()
        }
    }
}
