import AppKit
import SwiftUI

/// The square icon for a login: the bundled service logo when the login
/// matches a known service, otherwise its first letter. Logins signed into
/// through a provider ("Sign in with Google") carry that provider's logo as a
/// small badge in the top-left corner.
struct ServiceIcon: View {
    let title: String
    let service: KnownService?
    var size: CGFloat = 30
    /// The provider shown as a corner badge, if any.
    var badge: KnownService?

    init(item: LoginItem, size: CGFloat = 30) {
        let service = KnownService.matching(item)
        let provider = item.signIns.lazy.compactMap(\.method.service).first
        // No badge on the provider's own logins (a Google login that lists
        // "Sign in with Google").
        self.init(title: item.title, service: service, size: size, badge: provider == service ? nil : provider)
    }

    init(title: String, service: KnownService?, size: CGFloat = 30, badge: KnownService? = nil) {
        self.title = title
        self.service = service
        self.size = size
        self.badge = badge
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
            case (.appIcon, let name?):
                Image(name)
                    .resizable()
                    .scaledToFill()
                    .clipShape(shape)
            default:
                Text(initial)
                    .font(.system(size: size * 0.46, weight: .semibold, design: .rounded))
                    .foregroundStyle(foreground)
            }
        }
        .frame(width: size, height: size)
        .overlay(shape.strokeBorder(.separator, lineWidth: 0.5))
        .overlay(alignment: .topLeading) {
            if let badge {
                let badgeSize = max(size * 0.46, 12)
                let ring = max(size * 0.05, 1.5)
                ServiceIcon(title: badge.name, service: badge, size: badgeSize)
                    .padding(ring)
                    // A ring in the window color keeps the badge readable on
                    // any logo underneath.
                    .background(
                        RoundedRectangle(cornerRadius: (badgeSize + ring * 2) * 0.26, style: .continuous)
                            .fill(Color(nsColor: .windowBackgroundColor))
                    )
                    .offset(x: -size * 0.16, y: -size * 0.16)
                    .help("Sign in with \(badge.name)")
            }
        }
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
        if service?.logo == .color || service?.logo == .appIcon { return .white }
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
    /// Called with the picked service, or nil for "No Service".
    let onPick: (KnownService?) -> Void

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
                List {
                    if selected != nil, query.trimmed.isEmpty {
                        Button {
                            pick(nil)
                        } label: {
                            Label("No Service", systemImage: "xmark.circle")
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .contentShape(.rect)
                        }
                        .buttonStyle(.plain)
                    }
                    ForEach(KnownService.search(query)) { service in
                        Button {
                            pick(service)
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
                                if service == selected {
                                    Image(systemName: "checkmark")
                                        .foregroundStyle(.tint)
                                }
                            }
                            .contentShape(.rect)
                        }
                        .buttonStyle(.plain)
                    }
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

    private func pick(_ service: KnownService?) {
        onPick(service)
        isPresented = false
        query = ""
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
