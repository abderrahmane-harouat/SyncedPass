import AppKit
import SwiftUI

/// The square icon for a login: the bundled service logo when the login
/// matches a known service, otherwise its first letter.
struct ServiceIcon: View {
    let title: String
    let service: KnownService?
    var size: CGFloat = 30

    init(item: LoginItem, size: CGFloat = 30) {
        self.init(title: item.title, service: KnownService.matching(item), size: size)
    }

    init(title: String, service: KnownService?, size: CGFloat = 30) {
        self.title = title
        self.service = service
        self.size = size
    }

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: size * 0.24, style: .continuous)
        ZStack {
            shape.fill(background)
            switch (service?.logo, service?.logoAssetName) {
            case (.monochrome, let name?):
                Image(name)
                    .resizable()
                    .scaledToFit()
                    .foregroundStyle(foreground)
                    .padding(size * 0.2)
            case (.color, let name?):
                Image(name)
                    .resizable()
                    .scaledToFit()
                    .padding(size * 0.18)
            default:
                Text(initial)
                    .font(.system(size: size * 0.46, weight: .semibold, design: .rounded))
                    .foregroundStyle(foreground)
            }
        }
        .frame(width: size, height: size)
        .overlay(shape.strokeBorder(.separator, lineWidth: 0.5))
        .accessibilityHidden(true)
    }

    private var initial: String {
        (service?.name ?? title).trimmed.first.map { String($0).uppercased() } ?? "?"
    }

    private var rgb: (red: Double, green: Double, blue: Double) {
        let hex = service?.color ?? Self.fallbackColor(for: title)
        return (Double((hex >> 16) & 0xFF) / 255, Double((hex >> 8) & 0xFF) / 255, Double(hex & 0xFF) / 255)
    }

    private var background: Color {
        if service?.logo == .color { return .white }
        return Color(red: rgb.red, green: rgb.green, blue: rgb.blue)
    }

    /// Black on light brand colors (e.g. Snapchat yellow), white otherwise.
    private var foreground: Color {
        let luminance = 0.299 * rgb.red + 0.587 * rgb.green + 0.114 * rgb.blue
        return luminance > 0.7 ? .black : .white
    }

    /// A stable color per title, so unknown logins keep the same color
    /// between launches (`hashValue` is randomized per launch, so it isn't used).
    private static func fallbackColor(for title: String) -> UInt32 {
        let palette: [UInt32] = [0x5B6CFF, 0x00A884, 0xE5484D, 0xF5A524, 0x8E4EC6, 0x0091FF, 0xD6409F, 0x12A594]
        let sum = title.unicodeScalars.reduce(0) { $0 &+ Int($1.value) }
        return palette[sum % palette.count]
    }
}

/// A button showing the matched service, opening a searchable list of
/// popular services to pick from.
struct ServicePickerButton: View {
    let selected: KnownService?
    let onPick: (KnownService) -> Void

    @State private var isPresented = false
    @State private var query = ""

    var body: some View {
        Button {
            isPresented = true
        } label: {
            HStack(spacing: 6) {
                if let selected {
                    ServiceIcon(title: selected.name, service: selected, size: 18)
                    Text(selected.name)
                } else {
                    Text("Choose…")
                }
            }
        }
        .popover(isPresented: $isPresented, arrowEdge: .trailing) {
            VStack(spacing: 0) {
                TextField("Search services", text: $query)
                    .textFieldStyle(.roundedBorder)
                    .padding(10)
                List(KnownService.search(query)) { service in
                    Button {
                        onPick(service)
                        isPresented = false
                        query = ""
                    } label: {
                        HStack(spacing: 10) {
                            ServiceIcon(title: service.name, service: service, size: 24)
                            VStack(alignment: .leading, spacing: 1) {
                                Text(service.name)
                                Text(service.domains[0])
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                        }
                        .contentShape(.rect)
                    }
                    .buttonStyle(.plain)
                }
                .overlay {
                    if KnownService.search(query).isEmpty {
                        ContentUnavailableView.search(text: query)
                    }
                }
            }
            .frame(width: 280, height: 380)
        }
    }
}

/// The sign-in method's service logo as a small tile, or a key symbol for
/// plain email/username & password.
struct SignInMethodIcon: View {
    let method: SignInMethod
    var size: CGFloat = 18

    var body: some View {
        if let service = method.service {
            ServiceIcon(title: service.name, service: service, size: size)
        } else if method == .standard {
            Image(systemName: "person.badge.key.fill")
                .foregroundStyle(.secondary)
                .frame(width: size, height: size)
        }
    }
}

extension SignInMethod {
    /// Icon for menu items. Menus ignore SwiftUI sizing modifiers, so the
    /// image is sized before it's handed over (the full-color logos would
    /// otherwise show at their 256-point design size).
    var menuIcon: Image? {
        if let name = service?.logoAssetName, let logo = NSImage(named: name)?.copy() as? NSImage {
            let side: CGFloat = 16
            let scale = side / max(logo.size.width, logo.size.height)
            logo.size = NSSize(width: logo.size.width * scale, height: logo.size.height * scale)
            return Image(nsImage: logo)
        }
        return self == .standard ? Image(systemName: "person.badge.key") : nil
    }
}
