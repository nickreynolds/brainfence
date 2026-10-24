import SwiftUI

@main
struct BrainfenceApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var delegate

    var body: some Scene {
        MenuBarExtra {
            MenuBarContent()
                .environment(delegate.model)
                .tint(Palette.primary)
        } label: {
            MenuBarIcon(engine: delegate.model.engine)
        }
        .menuBarExtraStyle(.window)
    }
}

private struct MenuBarIcon: View {
    let engine: BlockingEngine

    var body: some View {
        Image(systemName: engine.state.isBlocking ? "lock.fill" : "lock.open")
            .accessibilityLabel(engine.state.isBlocking ? "Brainfence: blocking" : "Brainfence: not blocking")
    }
}
