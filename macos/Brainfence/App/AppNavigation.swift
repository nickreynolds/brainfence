import Foundation

/// Where the main window is: the sidebar section and each section's stack.
/// Lives on the model so notifications and the shield can navigate.
@MainActor
@Observable
final class AppNavigation {
    enum Section: String, CaseIterable, Identifiable {
        case tasks = "Tasks"
        case journal = "Journal"
        case reports = "Reports"
        case contemplations = "Contemplations"
        case rules = "Blocking Rules"
        case debug = "Debug"
        case settings = "Settings"

        var id: String { rawValue }
        var icon: String {
            switch self {
            case .tasks: "checklist"
            case .journal: "book"
            case .reports: "chart.bar.doc.horizontal"
            case .contemplations: "quote.bubble"
            case .rules: "lock.shield"
            case .debug: "ladybug"
            case .settings: "gearshape"
            }
        }
    }

    enum ReportRoute: Hashable {
        case report(String)
        case metric(String)
    }

    /// The task editor sheet: a new task, or an existing one by id.
    struct EditorTarget: Identifiable, Hashable {
        let taskId: String?
        var id: String { taskId ?? "new" }
    }

    var section: Section = .tasks
    /// Task ids, for the Tasks stack.
    var taskPath: [String] = []
    var reportPath: [ReportRoute] = []
    /// Deck ids, for the Contemplations stack.
    var deckPath: [String] = []
    var editor: EditorTarget?

    func showTask(_ id: String) {
        section = .tasks
        taskPath = [id]
    }
}
