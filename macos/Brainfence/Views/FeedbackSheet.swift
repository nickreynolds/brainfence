import BrainfenceCore
import SwiftUI

/// The short feedback form, from Settings and the setup guide's last page.
/// shared/CONTRACTS.md (Feedback).
struct FeedbackSheet: View {
    let source: Feedback.Source
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var message = ""
    @State private var sending = false
    @State private var sent = false
    @State private var error: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if sent {
                Text("Thanks!").font(.title2.weight(.semibold))
                Text("Your feedback is on its way. If you're offline, it's sent when you're back online.")
                    .foregroundStyle(.secondary)
                HStack {
                    Spacer()
                    Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
                }
            } else {
                form
            }
        }
        .padding(20)
        .frame(width: 440)
    }

    @ViewBuilder private var form: some View {
        let length = Feedback.length(message.trimmingCharacters(in: .whitespacesAndNewlines))
        Text("Send Feedback").font(.title2.weight(.semibold))
        Text("Something confusing, broken or missing? Or something you like? It comes straight to us.")
            .foregroundStyle(.secondary)
            .fixedSize(horizontal: false, vertical: true)
        TextEditor(text: $message)
            .font(.body)
            .frame(height: 140)
            .scrollContentBackground(.hidden)
            .padding(6)
            .background(RoundedRectangle(cornerRadius: 6).fill(Palette.surfaceContainerHighest))
            .disabled(sending)
        if length > Feedback.maxLength - 500 {
            Text("\(length) / \(Feedback.maxLength)")
                .font(.caption)
                .foregroundStyle(length > Feedback.maxLength ? Palette.error : .secondary)
        }
        Text("Saved with your account, along with the platform and app version.")
            .font(.caption)
            .foregroundStyle(.secondary)
        if let error {
            Text("Couldn't send it: \(error)").font(.caption).foregroundStyle(Palette.error)
        }
        HStack {
            Spacer()
            Button("Cancel") { dismiss() }
                .keyboardShortcut(.cancelAction)
                .disabled(sending)
            Button(sending ? "Sending…" : "Send") { send() }
                .keyboardShortcut(.defaultAction)
                .disabled(sending || Feedback.normalize(message) == nil)
        }
    }

    private func send() {
        guard let userId = model.session.userIdForLocalWrites else {
            error = "Sign in to send feedback."
            return
        }
        sending = true
        error = nil
        Task {
            do {
                try await model.feedbackRepository.send(message, source: source, userId: userId)
                sent = true
            } catch {
                self.error = error.localizedDescription
            }
            sending = false
        }
    }
}
