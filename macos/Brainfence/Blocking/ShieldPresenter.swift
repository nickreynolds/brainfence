import AppKit
import BrainfenceCore
import SwiftUI

/// The blocking overlay: a floating panel explaining what was blocked, by
/// which rule, and which tasks will lift it.
@MainActor
@Observable
final class ShieldPresenter {
    enum Subject: Equatable {
        case app(bundleID: String, name: String, blocks: [RuleBlock])
        case website(host: String, browser: String, blocks: [RuleBlock])
        case unsupportedBrowser(name: String, blocks: [RuleBlock])
        case browserNeedsAccess(name: String, bundleID: String, blocks: [RuleBlock])

        var title: String {
            switch self {
            case .app(_, let name, _): "\(name) is blocked"
            case .website(let host, _, _): "\(host) is blocked"
            case .unsupportedBrowser(let name, _): "\(name) is blocked while websites are blocked"
            case .browserNeedsAccess(let name, _, _): "\(name) needs permission"
            }
        }
    }

    private(set) var subject: Subject?
    @ObservationIgnored weak var model: AppModel?
    @ObservationIgnored private var panel: NSPanel?

    func show(_ subject: Subject) {
        self.subject = subject
        let panel = self.panel ?? makePanel()
        self.panel = panel
        if let screen = NSScreen.screens.first(where: { NSMouseInRect(NSEvent.mouseLocation, $0.frame, false) }) ?? NSScreen.main {
            let size = panel.frame.size
            let f = screen.visibleFrame
            panel.setFrameOrigin(NSPoint(x: f.midX - size.width / 2, y: f.midY - size.height / 2 + f.height * 0.1))
        }
        NSApp.activate(ignoringOtherApps: true)
        // Activation of a menu-bar app can be declined by the system; make
        // sure the floating panel is visible regardless.
        panel.orderFrontRegardless()
        panel.makeKey()
    }

    func dismiss() {
        panel?.orderOut(nil)
        subject = nil
    }

    private func makePanel() -> NSPanel {
        let panel = NSPanel(
            contentRect: NSRect(x: 0, y: 0, width: 460, height: 380),
            styleMask: [.titled, .closable, .fullSizeContentView],
            backing: .buffered, defer: false
        )
        panel.titleVisibility = .hidden
        panel.titlebarAppearsTransparent = true
        panel.isMovableByWindowBackground = true
        panel.level = .floating
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
        panel.isReleasedWhenClosed = false
        panel.hidesOnDeactivate = false
        let host = NSHostingView(rootView: ShieldView(presenter: self).tint(Palette.primary))
        host.sizingOptions = [.preferredContentSize]
        panel.contentView = host
        return panel
    }
}

private struct ShieldView: View {
    let presenter: ShieldPresenter

    var body: some View {
        if let model = presenter.model, let subject = presenter.subject {
            ShieldContent(subject: subject, presenter: presenter)
                .environment(model)
        }
    }
}

private struct ShieldContent: View {
    let subject: ShieldPresenter.Subject
    let presenter: ShieldPresenter
    @Environment(AppModel.self) private var model

    /// The rules blocking the subject *now* (live, not the snapshot taken
    /// when the shield appeared), so the panel notices the block lifting.
    private var liveBlocks: [RuleBlock] {
        let state = model.engine.state
        return switch subject {
        case .app(let id, _, _): state.blocks(forBundleID: id)
        case .website(let host, _, _): state.blocks(forHost: host)
        case .unsupportedBrowser, .browserNeedsAccess: state.blockedDomains.isEmpty ? [] : state.activeBlocks
        }
    }

    private var unmetTasks: [(id: String, task: BrainTask?)] {
        let ids = liveBlocks.flatMap { $0.rule.conditionTaskIds.filter($0.unmetTaskIds.contains) }
        var seen = Set<String>()
        return ids.filter { seen.insert($0).inserted }.map { ($0, model.engine.task(id: $0)) }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(spacing: 12) {
                Image(systemName: liveBlocks.isEmpty ? "lock.open.fill" : "lock.fill")
                    .font(.system(size: 28))
                    .foregroundStyle(liveBlocks.isEmpty ? .green : .orange)
                VStack(alignment: .leading, spacing: 2) {
                    Text(liveBlocks.isEmpty ? "Unblocked" : subject.title)
                        .font(.title3.weight(.semibold))
                    Text(subtitle)
                        .font(.callout)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }

            if case .browserNeedsAccess(let name, _, _) = subject, !liveBlocks.isEmpty {
                Text("While websites are blocked, Brainfence needs to see which page \(name) is showing. Allow it under System Settings → Privacy & Security → Automation → Brainfence → \(name).")
                    .font(.callout)
                    .fixedSize(horizontal: false, vertical: true)
                Button("Open Automation Settings") {
                    NSWorkspace.shared.open(URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Automation")!)
                }
            }

            if !liveBlocks.isEmpty && !unmetTasks.isEmpty {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Complete to unblock")
                        .font(.headline)
                    ForEach(unmetTasks, id: \.id) { item in
                        BlockingTaskRow(taskId: item.id, task: item.task)
                    }
                }
            } else if !liveBlocks.isEmpty {
                Text("This rule has no tasks that can lift it — it blocks whenever it's active.")
                    .font(.callout)
                    .foregroundStyle(.secondary)
            }

            HStack {
                Button("Open Brainfence") {
                    presenter.dismiss()
                    model.openMainWindow()
                }
                Spacer()
                if liveBlocks.isEmpty, case .app(let id, let name, _) = subject {
                    Button("Open \(name)") {
                        presenter.dismiss()
                        NSRunningApplication.runningApplications(withBundleIdentifier: id).first?.unhide()
                        NSRunningApplication.runningApplications(withBundleIdentifier: id).first?.activate()
                    }
                    .keyboardShortcut(.defaultAction)
                } else {
                    Button("Close") { presenter.dismiss() }
                        .keyboardShortcut(.cancelAction)
                }
            }
        }
        .padding(24)
        .frame(width: 460)
    }

    private var subtitle: String {
        if liveBlocks.isEmpty { return "The tasks are done — this is no longer blocked." }
        let names = liveBlocks.map { "“\($0.rule.name)”" }.joined(separator: ", ")
        return "Blocked by \(names)."
    }
}

/// One unmet condition task, with a Complete button when it can be done here.
struct BlockingTaskRow: View {
    let taskId: String
    let task: BrainTask?
    @Environment(AppModel.self) private var model
    @State private var isCompleting = false
    @State private var error: String?

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            Image(systemName: "circle")
                .foregroundStyle(.orange)
            VStack(alignment: .leading, spacing: 2) {
                Text(task?.title ?? "Unknown task")
                if let hint = task?.verificationHint ?? (task == nil ? "Not synced to this Mac yet" : nil) {
                    Text(hint).font(.caption).foregroundStyle(.secondary)
                }
                if let error {
                    Text(error).font(.caption).foregroundStyle(Palette.error)
                }
            }
            Spacer()
            if let task, task.isQuickCompletable {
                Button(isCompleting ? "Completing…" : "Complete") {
                    isCompleting = true
                    Task {
                        do {
                            try await model.complete(task)
                        } catch {
                            self.error = error.localizedDescription
                        }
                        isCompleting = false
                    }
                }
                .disabled(isCompleting || isBeforeStart(task))
                .help(isBeforeStart(task) ? "Available from \(task.availableFrom ?? "")" : "Mark as done")
            }
        }
    }

    private func isBeforeStart(_ task: BrainTask) -> Bool {
        computeTaskPhase(availableFrom: task.availableFrom, dueAt: task.dueAt, now: Date()) == .beforeStart
    }
}
