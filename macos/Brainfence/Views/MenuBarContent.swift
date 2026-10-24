import AppKit
import SwiftUI

/// The menu bar popover: blocking status at a glance, with quick completion.
struct MenuBarContent: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Image(systemName: model.engine.state.isBlocking ? "lock.fill" : "lock.open.fill")
                    .foregroundStyle(model.engine.state.isBlocking ? .orange : .green)
                Text(model.engine.state.isBlocking ? "Blocking" : "Nothing blocked")
                    .font(.headline)
                Spacer()
                SyncStatusBadge().fixedSize()
            }

            if case .signedOut = model.session.state, model.engine.rules.isEmpty {
                Text("Sign in to sync your tasks and rules.")
                    .font(.callout)
                    .foregroundStyle(.secondary)
            }

            ForEach(model.engine.state.activeBlocks) { block in
                ActiveBlockRow(block: block)
                Divider()
            }

            HStack {
                Button("Open Brainfence") { model.openMainWindow() }
                Spacer()
                Button("Quit") { NSApp.terminate(nil) }
            }
        }
        .padding(14)
        .frame(width: 340)
    }
}
