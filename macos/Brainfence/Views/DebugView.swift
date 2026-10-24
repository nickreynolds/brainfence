import SwiftUI

/// What the background services did over the last 48 hours, by category,
/// plus uploads the server rejected. Port of Android's DebugScreen.
struct DebugView: View {
    @Environment(AppModel.self) private var model
    @State private var category: String?

    var body: some View {
        let entries = model.debugLog.entries.filter { category == nil || $0.category == category }
        List {
            Picker("Category", selection: $category) {
                Text("All").tag(String?.none)
                ForEach(model.debugLog.categories, id: \.self) { c in Text(c).tag(String?.some(c)) }
            }
            .frame(maxWidth: 280)

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

            Section("Log") {
                if entries.isEmpty {
                    Text("Nothing logged yet.").foregroundStyle(.secondary)
                }
                ForEach(entries) { e in
                    VStack(alignment: .leading, spacing: 2) {
                        HStack {
                            Text(e.category).font(.caption.weight(.semibold)).foregroundStyle(Palette.primary)
                            Text(e.at.formatted(date: .abbreviated, time: .standard)).font(.caption).foregroundStyle(.secondary)
                        }
                        Text(e.message).font(.callout)
                        if let data = e.data {
                            Text(data).font(.caption.monospaced()).foregroundStyle(.secondary).lineLimit(4).textSelection(.enabled)
                        }
                    }
                }
            }
        }
        .navigationTitle("Debug")
        .toolbar {
            Button("Clear") { model.debugLog.clear() }
        }
    }
}
