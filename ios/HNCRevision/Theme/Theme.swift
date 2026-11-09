import SwiftUI
import UIKit

extension UIColor {
    convenience init(hex: UInt32) {
        self.init(red: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255,
                  blue: CGFloat(hex & 0xFF) / 255, alpha: 1)
    }
}

extension Color {
    /// A colour that follows light / dark mode.
    init(light: UInt32, dark: UInt32) {
        self.init(uiColor: UIColor { traits in
            UIColor(hex: traits.userInterfaceStyle == .dark ? dark : light)
        })
    }
}

/// The site's palette, with matching dark tokens.
enum Palette {
    static let ink = Color(light: 0x173D37, dark: 0xEEF2EA)          // primary text
    static let paper = Color(light: 0xF5F5EF, dark: 0x0F1F1C)        // background
    static let surface = Color(light: 0xFFFFFF, dark: 0x173430)      // cards
    static let line = Color(light: 0xDCE2D9, dark: 0x2C4B44)
    static let muted = Color(light: 0x6D7C75, dark: 0xA3B3AB)
    static let accent = Color(light: 0xD5E9A7, dark: 0xD5E9A7)       // soft buttons, Confident
    static let onAccent = Color(light: 0x173D37, dark: 0x173D37)
    static let primaryFill = Color(light: 0x173D37, dark: 0xD5E9A7)  // primary buttons
    static let onPrimary = Color(light: 0xFFFFFF, dark: 0x0F1F1C)
    static let tagFill = Color(light: 0xEDF3E6, dark: 0x24463F)
    static let tagText = Color(light: 0x486342, dark: 0xC9DCB4)
    static let sidebar = Color(light: 0xECEFE6, dark: 0x132925)
    static let progressTrack = Color(light: 0xE0E5D9, dark: 0x2C4B44)
    static let progressFill = Color(light: 0x87A867, dark: 0x9CC27A)
    static let correctFill = Color(light: 0xEDF6E7, dark: 0x1F3D26)
    static let correctBorder = Color(light: 0x578344, dark: 0x86B86D)
    static let wrongFill = Color(light: 0xFFF0EB, dark: 0x45231F)
    static let wrongBorder = Color(light: 0xAA5143, dark: 0xE38D7E)
    static let selectedFill = Color(light: 0xF0F4E7, dark: 0x22403A)
    static let note = Color(light: 0x785139, dark: 0xE0B58F)
    static let link = Color(light: 0x173D37, dark: 0xD5E9A7)

    // Dyslexia reading mode: softer cream background and cards.
    static let creamPaper = Color(light: 0xF7F1E3, dark: 0x1B1F18)
    static let creamSurface = Color(light: 0xFFFAF0, dark: 0x22281F)
}

// MARK: - Reading mode

private struct DyslexiaModeKey: EnvironmentKey {
    static let defaultValue = false
}

extension EnvironmentValues {
    var dyslexiaMode: Bool {
        get { self[DyslexiaModeKey.self] }
        set { self[DyslexiaModeKey.self] = newValue }
    }
}

/// Background colours that follow the reading mode.
struct ThemeColors {
    let dyslexia: Bool
    var paper: Color { dyslexia ? Palette.creamPaper : Palette.paper }
    var surface: Color { dyslexia ? Palette.creamSurface : Palette.surface }
}

extension View {
    /// The app's text style: system sans normally, Verdana with extra spacing in dyslexia reading mode.
    func appFont(_ style: Font.TextStyle, weight: Font.Weight? = nil) -> some View {
        modifier(AppFontModifier(style: style, weight: weight))
    }
}

struct AppFontModifier: ViewModifier {
    @Environment(\.dyslexiaMode) private var dyslexia
    let style: Font.TextStyle
    let weight: Font.Weight?

    func body(content: Content) -> some View {
        content
            .font(font)
            .tracking(dyslexia ? Self.size(for: style) * 0.05 : 0)
            .lineSpacing(dyslexia ? Self.size(for: style) * 0.6 : (style == .body || style == .callout ? 3 : 0))
    }

    var font: Font {
        if dyslexia {
            let base = Font.custom("Verdana", size: Self.size(for: style) * 0.94, relativeTo: style)
            return weight.map { base.weight($0) } ?? base
        }
        let base = Font.system(style)
        return weight.map { base.weight($0) } ?? base
    }

    /// Default point sizes at the Large content size.
    static func size(for style: Font.TextStyle) -> CGFloat {
        switch style {
        case .largeTitle: return 34
        case .title: return 28
        case .title2: return 22
        case .title3: return 20
        case .headline: return 17
        case .body: return 17
        case .callout: return 16
        case .subheadline: return 15
        case .footnote: return 13
        case .caption: return 12
        case .caption2: return 11
        default: return 17
        }
    }
}

// MARK: - Surfaces

struct CardBackground: ViewModifier {
    @Environment(\.dyslexiaMode) private var dyslexia
    var radius: CGFloat = 20
    var padding: CGFloat = 20

    func body(content: Content) -> some View {
        content
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(ThemeColors(dyslexia: dyslexia).surface, in: RoundedRectangle(cornerRadius: radius, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: radius, style: .continuous).strokeBorder(Palette.line, lineWidth: 1))
    }
}

extension View {
    func cardStyle(radius: CGFloat = 20, padding: CGFloat = 20) -> some View {
        modifier(CardBackground(radius: radius, padding: padding))
    }

    /// Readable width on iPad, centred.
    func readableWidth(_ max: CGFloat = 760) -> some View {
        frame(maxWidth: max).frame(maxWidth: .infinity)
    }
}

/// The screen background.
struct PaperBackground: View {
    @Environment(\.dyslexiaMode) private var dyslexia
    var body: some View {
        ThemeColors(dyslexia: dyslexia).paper.ignoresSafeArea()
    }
}

// MARK: - Buttons

enum AppButtonKind {
    case primary, soft, plain
}

struct AppButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled
    @Environment(\.dyslexiaMode) private var dyslexia
    var kind: AppButtonKind = .plain
    var expand = false

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .appFont(.callout, weight: .semibold)
            .multilineTextAlignment(.center)
            .padding(.horizontal, 16)
            .padding(.vertical, 11)
            .frame(maxWidth: expand ? .infinity : nil, minHeight: 44)
            .foregroundStyle(foreground)
            .background(background, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous).strokeBorder(border, lineWidth: 1))
            .opacity(isEnabled ? (configuration.isPressed ? 0.75 : 1) : 0.45)
            .contentShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
    }

    private var foreground: Color {
        switch kind {
        case .primary: return Palette.onPrimary
        case .soft: return Palette.onAccent
        case .plain: return Palette.ink
        }
    }

    private var background: Color {
        switch kind {
        case .primary: return Palette.primaryFill
        case .soft: return Palette.accent
        case .plain: return ThemeColors(dyslexia: dyslexia).surface
        }
    }

    private var border: Color {
        switch kind {
        case .primary: return Palette.primaryFill
        case .soft: return Palette.accent
        case .plain: return Palette.line
        }
    }
}

extension ButtonStyle where Self == AppButtonStyle {
    static var appPrimary: AppButtonStyle { AppButtonStyle(kind: .primary) }
    static var appSoft: AppButtonStyle { AppButtonStyle(kind: .soft) }
    static var appPlain: AppButtonStyle { AppButtonStyle(kind: .plain) }
    static func app(_ kind: AppButtonKind, expand: Bool) -> AppButtonStyle { AppButtonStyle(kind: kind, expand: expand) }
}

// MARK: - Small pieces

/// Uppercase small label, like the site's `.eyebrow`.
struct Eyebrow: View {
    let text: String
    init(_ text: String) { self.text = text }
    var body: some View {
        Text(text.uppercased())
            .tracking(1.2)
            .appFont(.caption2, weight: .bold)
            .foregroundStyle(Palette.muted)
    }
}

/// The "<unit> · <topic>" tag.
struct Tag: View {
    let text: String
    var body: some View {
        Text(text.uppercased())
            .tracking(0.6)
            .appFont(.caption2, weight: .medium)
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .foregroundStyle(Palette.tagText)
            .background(Palette.tagFill, in: RoundedRectangle(cornerRadius: 5))
    }
}

struct ProgressBar: View {
    let value: Double
    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Capsule().fill(Palette.progressTrack)
                Capsule().fill(Palette.progressFill)
                    .frame(width: geo.size.width * min(max(value, 0), 1))
            }
        }
        .frame(height: 5)
        .animation(.easeOut(duration: 0.2), value: value)
    }
}
