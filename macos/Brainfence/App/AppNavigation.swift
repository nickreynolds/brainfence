import Foundation

/// Where the main window is: the sidebar section and each section's stack.
/// Lives on the model so notifications and the shield can navigate.
@MainActor
@Observable
final class AppNavigation {
    /// The same four sections as Android's bottom bar.
    enum Section: String, CaseIterable, Identifiable {
        case tasks = "Tasks"
        case rules = "Blocking Rules"
        case review = "Review"
        case settings = "Settings"

        var id: String { rawValue }
        var icon: String {
            switch self {
            case .tasks: "checklist"
            case .rules: "lock.shield"
            case .review: "book"
            case .settings: "gearshape"
            }
        }
    }

    /// Review's tabs. Journal comes first; more kinds of entries will join it.
    enum ReviewTab: String, CaseIterable, Identifiable {
        case journal = "Journal"
        case reports = "Reports"

        var id: String { rawValue }
    }

    /// Settings' own pages.
    enum SettingsRoute: Hashable {
        case contemplations
        case deck(String)
        case debug
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
    var reviewTab: ReviewTab = .journal
    /// Report and metric editors, for the Review stack.
    var reportPath: [ReportRoute] = []
    var settingsPath: [SettingsRoute] = []
    var editor: EditorTarget?

    func showTask(_ id: String) {
        section = .tasks
        taskPath = [id]
    }

    func showContemplations() {
        section = .settings
        settingsPath = [.contemplations]
    }

    func showJournal() {
        section = .review
        reviewTab = .journal
        reportPath = []
    }
}
