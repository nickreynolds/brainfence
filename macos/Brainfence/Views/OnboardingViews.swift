import AppKit
import BrainfenceCore
import SwiftUI

/// The first-time setup guide: what Brainfence does, then a starter set of
/// tasks and a blocking rule (shared/CONTRACTS.md, Onboarding). The Mac has no
/// location, so Morning Walk and Groceries are left for the phone to finish.
struct GuideView: View {
    @Environment(AppModel.self) private var model

    private enum Step: Hashable { case welcome, starters, meditation, blocking, review, done }

    @State private var step: Step = .welcome
    @State private var starters: [StarterKey: StarterChoice] = Dictionary(uniqueKeysWithValues: StarterKey.allCases.map { ($0, StarterChoice()) })
    @State private var companionApps: [String] = []    // bundle IDs
    @State private var blockedApps: [String] = []
    @State private var domains: [String] = []
    @State private var newDomain = ""
    @State private var picking: PickerTarget?
    @State private var isSaving = false
    @State private var error: String?

    private enum PickerTarget: String, Identifiable { case companions, blocked; var id: String { rawValue } }

    private var steps: [Step] {
        [.welcome, .starters] + (starters[.meditation] != nil ? [.meditation] : []) + [.blocking, .review, .done]
    }

    private var plan: OnboardingPlan {
        OnboardingPlan(OnboardingChoices(
            starters: starters,
            companionApps: companionApps.map(Self.appEntry),
            blockedApps: blockedApps.map(Self.appEntry),
            blockedDomains: domains
        ))
    }

    /// A macOS entry with a display name, for the other platforms' editors.
    static func appEntry(_ bundleID: String) -> BlockedApp {
        BlockedApp(platform: localPlatform, identifier: bundleID, raw: .object([
            "platform": .string(localPlatform),
            "bundle_id": .string(bundleID),
            "name": .string(InstalledApps.displayName(forBundleID: bundleID)),
        ]))
    }

    var body: some View {
        VStack(spacing: 0) {
            if let index = progressIndex {
                ProgressView(value: Double(index + 1), total: Double(steps.count - 2))
                    .padding(.horizontal, 32)
                    .padding(.top, 20)
            }
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    content
                }
                .frame(maxWidth: 600, alignment: .leading)
                .padding(32)
                .frame(maxWidth: .infinity)
            }
            Divider()
            footer
                .padding(.horizontal, 32)
                .padding(.vertical, 14)
        }
        .frame(minWidth: 560, minHeight: 520)
        .sheet(item: $picking) { target in
            switch target {
            case .companions:
                AppPickerView(selected: Set(companionApps)) { ids in companionApps += ids.filter { !companionApps.contains($0) } }
            case .blocked:
                AppPickerView(selected: Set(blockedApps)) { ids in blockedApps += ids.filter { !blockedApps.contains($0) } }
            }
        }
    }

    private var progressIndex: Int? {
        guard step != .welcome && step != .done, let i = steps.firstIndex(of: step) else { return nil }
        return i - 1
    }

    @ViewBuilder private var content: some View {
        switch step {
        case .welcome:
            Text("Welcome to Brainfence").font(.largeTitle.weight(.semibold))
            Text("Brainfence keeps distracting apps and websites locked until you've done the things that matter to you today.")
                .foregroundStyle(.secondary)
            Point(icon: "checklist", title: "Tasks with a time window",
                  text: "Each task has a window: it can be done from its start time, and it's due by its end time. Repeating tasks come back every day.")
            Point(icon: "lock.shield", title: "Blocking rules",
                  text: "A rule lists apps and sites to block, and the tasks that unlock them. Once a task is past due and not done, the rule's apps and sites stay blocked until you finish it.")
            Point(icon: "arrow.triangle.2.circlepath", title: "On all your devices",
                  text: "Tasks and rules sync to your phone and computers. Each device blocks its own apps.")
            Text("The next few steps set up a small starter routine. You can change any of it later.")
                .font(.callout).foregroundStyle(.secondary)

        case .starters:
            Text("Pick your starter tasks").font(.title.weight(.semibold))
            Text("Turn on the ones you want. Tasks marked \"Unlocks apps\" will be part of your blocking rule.")
                .foregroundStyle(.secondary)
            ForEach(StarterDef.all, id: \.key) { def in
                StarterCard(def: def, choice: binding(for: def.key))
            }

        case .meditation:
            Text("Meditation apps").font(.title.weight(.semibold))
            Text("Meditation can use the built-in timer. If you use an app on this Mac instead, add it: time spent in it counts toward your ten minutes.")
                .foregroundStyle(.secondary)
            AppList(bundleIDs: $companionApps) { picking = .companions }

        case .blocking:
            let conditions = StarterDef.all.filter { $0.blocks && starters[$0.key] != nil }
            let now = conditions.filter { $0.needs == nil }
            Text("What should be blocked?").font(.title.weight(.semibold))
            if now.isEmpty {
                Text(conditions.isEmpty
                     ? "None of your starters unlock apps, so no rule will be made. Go back and turn on one marked \"Unlocks apps\" to block something."
                     : "Morning Walk is finished on your phone, so the rule waits until then. Turn on Morning Routine or Meditation to start blocking now.")
                    .foregroundStyle(.secondary)
            } else {
                Text("Until \(conditions.map(\.title).joined(separator: ", ")) \(conditions.count > 1 ? "are" : "is") done, these will be blocked once each task is past due.")
                    .foregroundStyle(.secondary)
            }
            GroupBox("Apps on this Mac") {
                AppList(bundleIDs: $blockedApps) { picking = .blocked }
                    .padding(6)
            }
            GroupBox("Websites") {
                VStack(alignment: .leading, spacing: 10) {
                    FlowChips(items: Array(NSOrderedSet(array: Onboarding.suggestedDomains + domains)) as? [String] ?? [], selected: Set(domains)) { d in
                        if domains.contains(d) { domains.removeAll { $0 == d } } else { domains.append(d) }
                    }
                    HStack {
                        TextField("Add a site, e.g. news.ycombinator.com", text: $newDomain).onSubmit(addDomain)
                        Button("Add", action: addDomain).disabled(DomainMatcher.normalize(newDomain) == nil)
                    }
                    Text("Blocked in Safari and Chromium browsers (Chrome, Arc, Brave, Edge…).")
                        .font(.caption).foregroundStyle(.secondary)
                }
                .padding(6)
            }
            Point(icon: "clock", title: "Loosening takes a day",
                  text: "Adding to a rule applies at once. Removing an app, a site or a task waits 24 hours, so blocking can't be undone on a whim.")

        case .review:
            let plan = plan
            Text("Ready to go").font(.title.weight(.semibold))
            ForEach(plan.tasks, id: \.key) { t in
                Label {
                    VStack(alignment: .leading) {
                        Text(t.title).font(.headline)
                        Text(t.availableFrom.map { "Daily, \($0)–\(t.dueAt ?? "")" } ?? "Shopping list")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                } icon: { Image(systemName: StarterCard.icon(t.key)) }
            }
            if plan.addRemembrances {
                Label("The Five Remembrances, delivered on this Mac", systemImage: StarterCard.icon(.fiveRemembrances))
            }
            ForEach(plan.deferredSteps, id: \.key) { d in
                Label {
                    VStack(alignment: .leading) {
                        Text(StarterDef.of(d.key).title).font(.headline)
                        Text("Finished on your phone, which knows where you are").font(.caption).foregroundStyle(.secondary)
                    }
                } icon: { Image(systemName: "iphone") }
            }
            if let rule = plan.rule {
                GroupBox {
                    Label {
                        VStack(alignment: .leading) {
                            Text("Rule \"\(rule.name)\"").font(.headline)
                            Text(([rule.blockedApps.isEmpty ? nil : plural(rule.blockedApps.count, "app")] + [rule.blockedDomains.isEmpty ? nil : rule.blockedDomains.joined(separator: ", ")]).compactMap { $0 }.joined(separator: " · "))
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    } icon: { Image(systemName: "lock.shield") }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
            } else {
                Text("No blocking rule: pick something to block and a task that unlocks it. You can add rules later under Blocking Rules.")
                    .font(.callout).foregroundStyle(.secondary)
            }
            ErrorText(error: error)

        case .done:
            let plan = plan
            Text("You're set up").font(.largeTitle.weight(.semibold))
            Point(icon: "play.circle", title: "Doing a task",
                  text: "Open a task under Tasks to start it. Routines walk you through each step, and meditation runs a timer.")
            Point(icon: "hand.raised", title: "When something is blocked",
                  text: "Brainfence covers the app and lists what's left to do. Finish the task and it unlocks straight away.")
            if !plan.deferredSteps.isEmpty {
                Point(icon: "iphone", title: "Finish on your phone",
                      text: "Open Brainfence on your phone to set up \(plan.deferredSteps.map { StarterDef.of($0.key).title }.joined(separator: " and ")) with your home and store.")
            }
            if plan.rule.map({ !$0.blockedDomains.isEmpty }) == true {
                Point(icon: "safari", title: "Website blocking",
                      text: "macOS asks once per browser to let Brainfence check its tabs. Allow it, or that browser is blocked while sites are.")
            }
            if plan.addRemembrances {
                Point(icon: "quote.bubble", title: "Contemplations",
                      text: "A remembrance arrives as a notification a few times a day. Change how often under Contemplations.")
            }
            Point(icon: "gearshape", title: "Run this again", text: "Settings → Run Setup Guide.")
        }
    }

    @ViewBuilder private var footer: some View {
        HStack {
            switch step {
            case .welcome:
                Button("Skip Setup") { run { try await model.skipGuide() } }
                Spacer()
                Button("Get Started") { advance() }.keyboardShortcut(.defaultAction)
            case .review:
                Button("Back") { retreat() }
                Spacer()
                Button(isSaving ? "Creating…" : "Create") {
                    run {
                        try await model.applyGuide(plan)
                        step = .done
                    }
                }
                .keyboardShortcut(.defaultAction)
                .disabled(isSaving)
            case .done:
                Spacer()
                Button("Go to My Tasks") { model.showOnboarding(nil) }.keyboardShortcut(.defaultAction)
            default:
                Button("Back") { retreat() }
                Spacer()
                Button("Next") { advance() }
                    .keyboardShortcut(.defaultAction)
                    .disabled(step == .starters && !startersValid)
            }
        }
    }

    private var startersValid: Bool {
        !starters.isEmpty && starters.values.allSatisfy { c in
            [c.availableFrom, c.dueAt].allSatisfy { $0 == nil || TimeOfDay($0!) != nil }
        }
    }

    private func binding(for key: StarterKey) -> Binding<StarterChoice?> {
        Binding(get: { starters[key] }, set: { starters[key] = $0 })
    }

    private func advance() {
        if let i = steps.firstIndex(of: step), i + 1 < steps.count { step = steps[i + 1] }
    }

    private func retreat() {
        if let i = steps.firstIndex(of: step), i > 0 { step = steps[i - 1] }
    }

    private func addDomain() {
        guard let d = DomainMatcher.normalize(newDomain) else { return }
        if !domains.contains(d) { domains.append(d) }
        newDomain = ""
    }

    private func run(_ action: @escaping @MainActor () async throws -> Void) {
        isSaving = true
        error = nil
        Task {
            do { try await action() } catch { self.error = error.localizedDescription }
            isSaving = false
        }
    }
}

/// A starter with its switch and, when on, its time window.
private struct StarterCard: View {
    let def: StarterDef
    @Binding var choice: StarterChoice?

    static func icon(_ key: StarterKey) -> String {
        switch key {
        case .morningRoutine: "sunrise"
        case .morningWalk: "figure.walk"
        case .meditation: "brain.head.profile"
        case .journaling: "book"
        case .groceries: "cart"
        case .fiveRemembrances: "quote.bubble"
        }
    }

    var body: some View {
        GroupBox {
            VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .top, spacing: 12) {
                    Image(systemName: Self.icon(def.key))
                        .font(.title2)
                        .foregroundStyle(.tint)
                        .frame(width: 28)
                    VStack(alignment: .leading, spacing: 3) {
                        HStack(spacing: 6) {
                            Text(def.title).font(.headline)
                            if def.blocks { Text("Unlocks apps").font(.caption).foregroundStyle(.tint) }
                        }
                        Text(def.summary).font(.callout).foregroundStyle(.secondary)
                        if def.needs != nil {
                            Label("Needs your location: finished on your phone", systemImage: "iphone")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    Spacer()
                    Toggle(def.title, isOn: Binding(get: { choice != nil }, set: { choice = $0 ? StarterChoice() : nil }))
                        .toggleStyle(.switch)
                        .labelsHidden()
                }
                if let current = choice, def.hasWindow {
                    HStack(spacing: 16) {
                        TimeField(label: "Available from", text: Binding(
                            get: { current.availableFrom ?? def.availableFrom ?? "" },
                            set: { choice?.availableFrom = $0 }
                        ))
                        TimeField(label: "Due by", text: Binding(
                            get: { current.dueAt ?? def.dueAt ?? "" },
                            set: { choice?.dueAt = $0 }
                        ))
                    }
                    .padding(.leading, 40)
                }
            }
            .padding(6)
        }
    }
}

private struct Point: View {
    let icon: String
    let title: String
    let text: String

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: icon)
                .font(.title2)
                .foregroundStyle(.tint)
                .frame(width: 30)
            VStack(alignment: .leading, spacing: 3) {
                Text(title).font(.headline)
                Text(text).font(.callout).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}

/// Picked apps with remove buttons, and an "Add Apps…" button.
private struct AppList: View {
    @Binding var bundleIDs: [String]
    let onAdd: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ForEach(bundleIDs, id: \.self) { id in
                HStack {
                    if let icon = InstalledApps.icon(forBundleID: id) {
                        Image(nsImage: icon).resizable().frame(width: 18, height: 18)
                    }
                    Text(InstalledApps.displayName(forBundleID: id))
                    Spacer()
                    Button(role: .destructive) { bundleIDs.removeAll { $0 == id } } label: { Image(systemName: "minus.circle") }
                        .buttonStyle(.borderless)
                }
            }
            Button("Add Apps…", action: onAdd)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Toggleable chips that wrap.
private struct FlowChips: View {
    let items: [String]
    let selected: Set<String>
    let onToggle: (String) -> Void

    var body: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 120), alignment: .leading)], alignment: .leading, spacing: 8) {
            ForEach(items, id: \.self) { item in
                Toggle(item, isOn: Binding(get: { selected.contains(item) }, set: { _ in onToggle(item) }))
                    .toggleStyle(.button)
            }
        }
    }
}

/// A Mac joining an account that finished the guide elsewhere: the parts that
/// are per device. Deferred location starters are left for the phone.
struct DeviceSetupView: View {
    @Environment(AppModel.self) private var model

    @State private var loaded = false
    @State private var rule: BlockingRule?
    @State private var hasMeditation = false
    @State private var blockedApps: [String] = []
    @State private var companionApps: [String] = []
    @State private var deferred: [DeferredStep] = []
    @State private var askContemplations = false
    @State private var deliver = true
    @State private var picking: Picker?
    @State private var isSaving = false
    @State private var error: String?

    private enum Picker: String, Identifiable { case companions, blocked; var id: String { rawValue } }

    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    Text("Set up this Mac").font(.largeTitle.weight(.semibold))
                    Text("Your tasks and rules are already here from your other devices. A few things are specific to this Mac.")
                        .foregroundStyle(.secondary)
                    if let rule {
                        GroupBox("Block on this Mac with \"\(rule.name)\"") {
                            VStack(alignment: .leading, spacing: 6) {
                                if !rule.macOSBundleIDs.isEmpty {
                                    Text("Already blocked here: \(rule.macOSBundleIDs.map(InstalledApps.displayName(forBundleID:)).joined(separator: ", "))")
                                        .font(.caption).foregroundStyle(.secondary)
                                }
                                AppList(bundleIDs: $blockedApps) { picking = .blocked }
                            }
                            .padding(6)
                        }
                    }
                    if hasMeditation {
                        GroupBox("Meditation apps on this Mac") {
                            VStack(alignment: .leading, spacing: 6) {
                                Text("Time spent in these counts toward your Meditation task.").font(.caption).foregroundStyle(.secondary)
                                AppList(bundleIDs: $companionApps) { picking = .companions }
                            }
                            .padding(6)
                        }
                    }
                    if !deferred.isEmpty {
                        Label("\(deferred.map { StarterDef.of($0.key).title }.joined(separator: " and ")) will be set up on your phone, which knows where you are.", systemImage: "iphone")
                            .foregroundStyle(.secondary)
                    }
                    if askContemplations {
                        Toggle("Deliver contemplations on this Mac", isOn: $deliver)
                    }
                    ErrorText(error: error)
                }
                .frame(maxWidth: 600, alignment: .leading)
                .padding(32)
                .frame(maxWidth: .infinity)
            }
            Divider()
            HStack {
                Button("Skip") { run { try await model.skipDeviceSetup() } }
                Spacer()
                Button(isSaving ? "Saving…" : "Done") {
                    run {
                        try await model.finishDeviceSetup(
                            blockedApps: blockedApps.map(GuideView.appEntry),
                            companionApps: hasMeditation ? companionApps.map(GuideView.appEntry) : nil,
                            deliverContemplations: askContemplations && deliver
                        )
                        model.showOnboarding(nil)
                    }
                }
                .keyboardShortcut(.defaultAction)
                .disabled(isSaving || !loaded)
            }
            .padding(.horizontal, 32)
            .padding(.vertical, 14)
        }
        .frame(minWidth: 560, minHeight: 420)
        .sheet(item: $picking) { target in
            switch target {
            case .companions:
                AppPickerView(selected: Set(companionApps)) { ids in companionApps += ids.filter { !companionApps.contains($0) } }
            case .blocked:
                AppPickerView(selected: Set(blockedApps + (rule?.macOSBundleIDs ?? []))) { ids in blockedApps += ids.filter { !blockedApps.contains($0) } }
            }
        }
        .task { await load() }
    }

    private func load() async {
        do {
            let row = try await model.onboardingRepository.row()
            if let id = row?.blockingRuleId { rule = try await model.blockingRepository.rule(id: id) }
            if let id = row?.meditationTaskId, let task = try await model.taskRepository.taskRow(id: id) {
                hasMeditation = true
                companionApps = (JSONValue.parse(task.verificationConfig)?["companion_apps"]?.arrayValue ?? [])
                    .filter { $0["platform"]?.stringValue == localPlatform }
                    .compactMap { $0["bundle_id"]?.stringValue }
            }
            deferred = row?.deferredSteps ?? []
            let decks = try await model.contemplationRepository.decks()
            askContemplations = decks.contains(where: \.isEnabled) && !model.contemplationScheduler.settings.enabled
            loaded = true
        } catch {
            self.error = error.localizedDescription
        }
    }

    private func run(_ action: @escaping @MainActor () async throws -> Void) {
        isSaving = true
        error = nil
        Task {
            do { try await action() } catch { self.error = error.localizedDescription }
            isSaving = false
        }
    }
}
