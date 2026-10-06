import SwiftUI

/// A secret text field with a button to show or hide its contents.
struct RevealableField: View {
    let title: String
    @Binding var text: String
    var prompt: String = "Optional"
    @State private var isRevealed = false

    init(_ title: String, text: Binding<String>, prompt: String = "Optional") {
        self.title = title
        self._text = text
        self.prompt = prompt
    }

    var body: some View {
        HStack {
            Group {
                if isRevealed {
                    TextField(title, text: $text, prompt: Text(prompt))
                } else {
                    SecureField(title, text: $text, prompt: Text(prompt))
                }
            }
            .fontDesign(isRevealed ? .monospaced : nil)
            Button(isRevealed ? "Hide" : "Show", systemImage: isRevealed ? "eye.slash" : "eye") {
                isRevealed.toggle()
            }
            .labelStyle(.iconOnly)
            .buttonStyle(.borderless)
            .foregroundStyle(.secondary)
            .help(isRevealed ? "Hide \(title)" : "Show \(title)")
        }
    }
}

/// A red message under a field; renders nothing when `message` is nil.
struct FieldError: View {
    let message: String?

    init(_ message: String?) {
        self.message = message
    }

    var body: some View {
        if let message {
            Label(message, systemImage: "exclamationmark.circle.fill")
                .font(.caption)
                .foregroundStyle(.red)
        }
    }
}
