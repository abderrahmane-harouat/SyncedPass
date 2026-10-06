import AppKit
import SwiftUI

/// A secret text field with a button to show or hide its contents.
///
/// Showing and hiding swaps a secure field for a plain one; focus is carried
/// over so the cursor stays in the field. Pass `focus` to control focus from
/// outside (e.g. to focus the field when a screen appears).
struct RevealableField: View {
    let title: String
    @Binding var text: String
    /// A format hint shown while empty; not the label.
    var prompt: String = ""
    var focus: FocusState<Bool>.Binding?
    @State private var isRevealed = false
    @FocusState private var ownFocus: Bool

    init(_ title: String, text: Binding<String>, prompt: String = "", focus: FocusState<Bool>.Binding? = nil) {
        self.title = title
        self._text = text
        self.prompt = prompt
        self.focus = focus
    }

    private var focusBinding: FocusState<Bool>.Binding { focus ?? $ownFocus }

    var body: some View {
        HStack {
            Group {
                if isRevealed {
                    TextField(title, text: $text, prompt: Text(prompt))
                } else {
                    SecureField(title, text: $text, prompt: Text(prompt))
                }
            }
            .focused(focusBinding)
            .fontDesign(isRevealed ? .monospaced : nil)
            Button(isRevealed ? "Hide \(title)" : "Show \(title)", systemImage: isRevealed ? "eye.slash" : "eye") {
                let wasFocused = focusBinding.wrappedValue
                isRevealed.toggle()
                guard wasFocused else { return }
                // The field is replaced: focus the new one on the next turn,
                // then move the cursor to the end. macOS selects all text in a
                // newly focused field, so the next keystroke would otherwise
                // replace what was already typed.
                DispatchQueue.main.async {
                    focusBinding.wrappedValue = true
                    DispatchQueue.main.async {
                        NSApp.sendAction(#selector(NSResponder.moveToEndOfDocument(_:)), to: nil, from: nil)
                    }
                }
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

/// A text input with its label above it and a full-width bordered box below.
///
/// Labels above fields are what usability research recommends (NN/g; labels
/// inside the field, as placeholders or floating labels, cause more errors),
/// and the whole box is the click target. Placeholders are only for format
/// hints ("name@example.com"), never the label. An error shows right under
/// the field it's about.
struct LabeledField<Label: View, Field: View>: View {
    private let label: Label
    private let field: Field
    private let error: String?

    init(error: String? = nil, @ViewBuilder label: () -> Label, @ViewBuilder field: () -> Field) {
        self.label = label()
        self.field = field()
        self.error = error
    }

    init(_ title: String, error: String? = nil, @ViewBuilder field: () -> Field) where Label == Text {
        self.init(error: error, label: { Text(title) }, field: field)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            label
                .font(.callout.weight(.medium))
            field
                .labelsHidden()
                .textFieldStyle(.roundedBorder)
                .controlSize(.large)
            FieldError(error)
        }
        .padding(.vertical, 4)
    }
}

/// The app's own icon (the padlock from AppIcon.icon), used wherever the
/// interface shows the app's identity, so it always matches the Dock.
struct AppIconImage: View {
    var size: CGFloat = 96

    var body: some View {
        Image(nsImage: NSApp.applicationIconImage)
            .resizable()
            .interpolation(.high)
            .scaledToFit()
            .frame(width: size, height: size)
            .accessibilityHidden(true)
    }
}
