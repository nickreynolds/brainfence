import BrainfenceCore
import SwiftUI

/// Delivery schedule (per device), decks and recent deliveries. Port of
/// Android's ContemplationSettingsScreen and ContemplationDeckScreen.
/// Contemplation schedule and decks: a page of Settings, which shows each deck
/// in a DeckEditorView.
struct ContemplationsView: View {
    @Environment(AppModel.self) private var model
    @State private var form = ContemplationScheduler.Settings()
    @State private var decks: [ContemplationDeck] = []
    @State private var recent: [ContemplationDelivery] = []
    @State private var message: String?
    @State private var error: String?

    var body: some View {
        let added = Set(decks.compactMap(\.libraryKey))
        let todays = recent.filter { Timestamp.parse($0.deliveredAt).map(Calendar.current.isDateInToday) ?? false }
        Form {
            Text("“To be a happy person, one must contemplate death five times daily.” Short passages arrive as notifications at random times through your day.")
                .font(.caption).foregroundStyle(.secondary)

            Section {
                Toggle("Deliver on this Mac", isOn: Binding(get: { form.enabled }, set: { setEnabled($0) }))
                Stepper("Per day: \(form.perDay)", value: $form.perDay, in: 1...ContemplationScheduler.maxPerDay)
                TimeField(label: "From", text: $form.windowStart)
                TimeField(label: "Until", text: $form.windowEnd)
                ErrorText(error: error)
                if let message { Text(message).font(.callout).foregroundStyle(Palette.primary) }
                if let next = model.contemplationScheduler.nextDelivery {
                    Text("Next: \(next.formatted(date: .abbreviated, time: .shortened))").font(.caption).foregroundStyle(.secondary)
                }
                HStack {
                    Button("Save Schedule") { saveSchedule() }
                    Button("Send One Now") { sendNow() }
                }
            } header: {
                Text("Schedule")
            } footer: {
                Text("Each device keeps its own schedule; decks and history sync.")
            }

            Section("Decks") {
                if decks.count > 1 {
                    Text("When several decks are on, each delivery picks one in proportion to its frequency.")
                        .font(.caption).foregroundStyle(.secondary)
                }
                ForEach(decks) { deck in
                    HStack {
                        NavigationLink(value: deck.id) {
                            VStack(alignment: .leading) {
                                Text(deck.name).font(.headline)
                                Text(deckSummary(deck)).font(.caption).foregroundStyle(.secondary)
                            }
                        }
                        Toggle("", isOn: Binding(get: { deck.isEnabled }, set: { on in
                            act { try await model.contemplationRepository.setDeckEnabled(id: deck.id, enabled: on) }
                        }))
                        .labelsHidden()
                        .toggleStyle(.switch)
                    }
                }
                ForEach(contemplationLibrary.filter { !added.contains($0.key) }, id: \.key) { starter in
                    HStack {
                        VStack(alignment: .leading) {
                            Text("Starter: \(starter.name)")
                            Text(starter.description).font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                        Button("Add") { act { try await model.contemplationRepository.addFromLibrary(starter, userId: model.requireUserId()) } }
                    }
                }
                Button("New Deck") {
                    act {
                        let id = try await model.contemplationRepository.createDeck(userId: model.requireUserId(), name: "New deck")
                        model.navigation.settingsPath.append(.deck(id))
                    }
                }
            }

            if !recent.isEmpty {
                Section("Recent") {
                    Text("Today: \(todays.count) delivered · \(todays.filter { $0.response == contemplationAffirmed }.count) affirmed")
                        .font(.caption).foregroundStyle(.secondary)
                    ForEach(recent) { d in
                        DeliveryRow(delivery: d, deck: decks.first { $0.id == d.deckId })
                    }
                }
            }
        }
        .formStyle(.grouped)
        .navigationTitle("Contemplations")
        .onAppear { form = model.contemplationScheduler.settings }
        .reloading(on: ContemplationRepository.deckTables + ContemplationRepository.deliveryTables, db: model.db) {
            decks = (try? await model.contemplationRepository.decks()) ?? []
            recent = (try? await model.contemplationRepository.recentDeliveries()) ?? []
        }
    }

    private func deckSummary(_ deck: ContemplationDeck) -> String {
        var s = "\(plural(deck.itemCount, "item")) · \(deck.ordering == .sequential ? "in turn" : "random") · frequency \(deck.weight)"
        if let label = deck.affirmLabel { s += " · “\(label)”" }
        return s
    }

    private func setEnabled(_ enabled: Bool) {
        // Toggling takes effect immediately, using the last saved schedule.
        var saved = model.contemplationScheduler.settings
        saved.enabled = enabled
        model.contemplationScheduler.update(saved)
        form.enabled = enabled
        message = nil
    }

    private func saveSchedule() {
        guard (1...ContemplationScheduler.maxPerDay).contains(form.perDay) else {
            error = "Per day must be between 1 and \(ContemplationScheduler.maxPerDay)"
            return
        }
        guard let start = TimeOfDay(form.windowStart), let end = TimeOfDay(form.windowEnd) else {
            error = "Times must be HH:MM (e.g. 08:00)"
            return
        }
        guard end > start else {
            error = "The window must end after it starts"
            return
        }
        error = nil
        model.contemplationScheduler.update(form)
        message = "Schedule saved"
    }

    private func sendNow() {
        Task {
            if await model.contemplationScheduler.deliverNow() {
                message = "Sent"
                error = nil
            } else {
                error = "Nothing sent: needs an enabled deck with items, and notifications allowed for Brainfence"
            }
        }
    }

    private func act(_ work: @escaping @MainActor () async throws -> Void) {
        Task {
            do { try await work() } catch { self.error = error.localizedDescription }
        }
    }
}

private struct DeliveryRow: View {
    let delivery: ContemplationDelivery
    let deck: ContemplationDeck?
    @Environment(AppModel.self) private var model

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(delivery.title ?? deck?.name ?? "Contemplation").font(.headline)
                Spacer()
                Text(formatTimestamp(delivery.deliveredAt)).font(.caption).foregroundStyle(.secondary)
            }
            Text(delivery.body).font(.callout)
            if let attribution = delivery.attribution {
                Text("— \(attribution)").font(.caption).foregroundStyle(.secondary)
            }
            if delivery.response == contemplationAffirmed {
                Label("Affirmed \(formatTimestamp(delivery.respondedAt))", systemImage: "checkmark")
                    .font(.caption).foregroundStyle(Palette.primary)
            } else if delivery.response == nil, let label = deck?.affirmLabel {
                Button(label) { model.contemplationScheduler.respond(deliveryId: delivery.id) }
            }
        }
        .padding(.vertical, 4)
    }
}

struct DeckEditorView: View {
    let deckId: String
    @Environment(AppModel.self) private var model
    @State private var deck: ContemplationDeck?
    @State private var items: [Contemplation] = []
    @State private var loaded = false
    @State private var seeded = false
    @State private var name = ""
    @State private var description = ""
    @State private var ordering: DeckOrdering = .random
    @State private var responseType: ContemplationResponseType = .noResponse
    @State private var responseLabel = ""
    @State private var weight = 1
    @State private var editing: ItemDraft?
    @State private var confirmingDelete = false
    @State private var error: String?

    struct ItemDraft: Identifiable {
        let id: String?
        var title: String
        var body: String
        var attribution: String

        var key: String { id ?? "new" }
    }

    var body: some View {
        Group {
            if deck != nil {
                Form {
                    Section {
                        TextField("Name", text: $name)
                        TextField("Description (optional)", text: $description)
                        Picker("Order", selection: $ordering) {
                            Text("In turn").tag(DeckOrdering.sequential)
                            Text("Random").tag(DeckOrdering.random)
                        }
                        .pickerStyle(.segmented)
                        Picker("Response", selection: $responseType) {
                            Text("Just read").tag(ContemplationResponseType.noResponse)
                            Text("Affirm button").tag(ContemplationResponseType.affirm)
                        }
                        .pickerStyle(.segmented)
                        if responseType == .affirm {
                            TextField("Button label", text: $responseLabel, prompt: Text(defaultAffirmLabel))
                        }
                        Stepper("Frequency: \(weight)", value: $weight, in: 1...100)
                        Text("Relative to other enabled decks, e.g. 4 vs 1 ≈ 80% of deliveries.").font(.caption).foregroundStyle(.secondary)
                        ErrorText(error: error)
                        Button("Save Deck") { save() }.keyboardShortcut(.defaultAction)
                    }
                    Section {
                        ForEach(items) { item in
                            Button {
                                editing = ItemDraft(id: item.id, title: item.title ?? "", body: item.body, attribution: item.attribution ?? "")
                            } label: {
                                VStack(alignment: .leading, spacing: 2) {
                                    if let title = item.title { Text(title).font(.headline) }
                                    Text(item.body).font(.callout)
                                    if let attribution = item.attribution { Text("— \(attribution)").font(.caption).foregroundStyle(.secondary) }
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                        }
                        Button("Add Contemplation") { editing = ItemDraft(id: nil, title: "", body: "", attribution: "") }
                    } header: {
                        Text("Contemplations")
                    } footer: {
                        Text("Changes here save immediately.")
                    }
                }
                .formStyle(.grouped)
            } else if loaded {
                Text("This deck no longer exists.").foregroundStyle(.secondary)
            } else {
                ProgressView()
            }
        }
        .navigationTitle(deck?.name ?? "Deck")
        .toolbar {
            Button(role: .destructive) { confirmingDelete = true } label: { Label("Delete Deck", systemImage: "trash") }
                .disabled(deck == nil)
        }
        .confirmationDialog("Delete deck?", isPresented: $confirmingDelete) {
            Button("Delete", role: .destructive) {
                act {
                    try await model.contemplationRepository.deleteDeck(id: deckId)
                    _ = model.navigation.settingsPath.popLast()
                }
            }
            Button("Keep", role: .cancel) {}
        } message: {
            Text("Its contemplations are deleted too. Past deliveries stay in your history.")
        }
        .sheet(item: $editing) { draft in
            ItemEditorSheet(draft: draft) { result in
                act {
                    let title = result.title.trimmingCharacters(in: .whitespaces)
                    let attribution = result.attribution.trimmingCharacters(in: .whitespaces)
                    let body = result.body.trimmingCharacters(in: .whitespacesAndNewlines)
                    if let id = result.id {
                        try await model.contemplationRepository.updateItem(
                            id: id, title: title.isEmpty ? nil : title, body: body, attribution: attribution.isEmpty ? nil : attribution
                        )
                    } else {
                        try await model.contemplationRepository.addItem(
                            deckId: deckId, userId: model.requireUserId(), title: title.isEmpty ? nil : title,
                            body: body, attribution: attribution.isEmpty ? nil : attribution
                        )
                    }
                }
            } onDelete: { id in
                act { try await model.contemplationRepository.deleteItem(id: id) }
            }
        }
        .reloading(on: ContemplationRepository.deckTables, db: model.db, id: deckId) {
            deck = try? await model.contemplationRepository.deck(id: deckId)
            items = (try? await model.contemplationRepository.items(deckId: deckId)) ?? []
            loaded = true
            // Seed once so later item edits don't clobber unsaved fields.
            if let deck, !seeded {
                name = deck.name
                description = deck.description ?? ""
                ordering = deck.ordering
                responseType = deck.responseType
                responseLabel = deck.responseLabel ?? ""
                weight = deck.weight
                seeded = true
            }
        }
    }

    private func save() {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else {
            error = "Name is required"
            return
        }
        let desc = description.trimmingCharacters(in: .whitespaces)
        let label = responseLabel.trimmingCharacters(in: .whitespaces)
        act {
            try await model.contemplationRepository.updateDeck(
                id: deckId, name: trimmed, description: desc.isEmpty ? nil : desc, ordering: ordering,
                responseType: responseType, responseLabel: label.isEmpty ? nil : label, weight: weight
            )
            _ = model.navigation.settingsPath.popLast()
        }
    }

    private func act(_ work: @escaping @MainActor () async throws -> Void) {
        Task {
            do { try await work() } catch { self.error = error.localizedDescription }
        }
    }
}

private struct ItemEditorSheet: View {
    @State var draft: DeckEditorView.ItemDraft
    let onSave: (DeckEditorView.ItemDraft) -> Void
    let onDelete: (String) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(draft.id == nil ? "New contemplation" : "Edit contemplation").font(.headline)
            Form {
                TextField("Title (optional)", text: $draft.title)
                TextField("Text", text: $draft.body, axis: .vertical).lineLimit(3...8)
                TextField("Attribution (optional)", text: $draft.attribution)
            }
            HStack {
                if let id = draft.id {
                    Button("Delete", role: .destructive) {
                        onDelete(id)
                        dismiss()
                    }
                }
                Spacer()
                Button("Cancel") { dismiss() }.keyboardShortcut(.cancelAction)
                Button("Save") {
                    onSave(draft)
                    dismiss()
                }
                .keyboardShortcut(.defaultAction)
                .disabled(draft.body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
        .padding(20)
        .frame(width: 460)
    }
}
