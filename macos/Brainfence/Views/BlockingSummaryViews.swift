import BrainfenceCore
import SwiftUI

/// A rule that is blocking now: what it blocks on this Mac and what lifts it.
struct ActiveBlockRow: View {
    let block: RuleBlock
    @Environment(AppModel.self) private var model

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Image(systemName: "lock.fill").foregroundStyle(.orange)
                Text(block.rule.name).font(.headline)
            }
            Text(BlockedTargetsSummary(rule: block.rule).text)
                .font(.caption)
                .foregroundStyle(.secondary)
            let unmet = block.rule.conditionTaskIds.filter(block.unmetTaskIds.contains)
            ForEach(unmet, id: \.self) { id in
                BlockingTaskRow(taskId: id, task: model.engine.task(id: id))
            }
            if block.rule.conditionTaskIds.isEmpty {
                Text("No condition tasks — blocks whenever active.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 4)
    }
}

/// "Blocks Discord, Slack · reddit.com, x.com · 2 apps on other devices"
@MainActor
struct BlockedTargetsSummary {
    let rule: BlockingRule

    var text: String {
        var parts: [String] = []
        let apps = rule.macOSBundleIDs.map(InstalledApps.displayName(forBundleID:))
        if !apps.isEmpty { parts.append(apps.joined(separator: ", ")) }
        if !rule.blockedDomains.isEmpty { parts.append(rule.blockedDomains.joined(separator: ", ")) }
        let other = rule.otherPlatformApps.count
        if other > 0 { parts.append("\(plural(other, "app")) on other devices") }
        return parts.isEmpty ? "Blocks nothing yet" : "Blocks " + parts.joined(separator: " · ")
    }
}
