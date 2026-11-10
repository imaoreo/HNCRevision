import RevisionCore
import SwiftUI
import WidgetKit

@main
struct HNCRevisionWidgetBundle: WidgetBundle {
    var body: some Widget {
        RevisionWidget()
    }
}

struct RevisionWidget: Widget {
    let kind = "HNCRevisionWidget"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: RevisionProvider()) { entry in
            RevisionWidgetView(entry: entry)
        }
        .configurationDisplayName("HNC Revision")
        .description("Cards due, your streak and a card of the day.")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}

// MARK: - Timeline

struct RevisionEntry: TimelineEntry {
    let date: Date
    let dueCount: Int
    let streak: Int
    let card: WidgetSnapshot.CardOfDay?
    /// False before the app has written its first snapshot.
    let hasData: Bool

    static let placeholder = RevisionEntry(
        date: .now, dueCount: 12, streak: 3,
        card: .init(id: 1, unit: "J0H934", topic: "Breaches", q: "What is a data security breach?"),
        hasData: true
    )
}

struct RevisionProvider: TimelineProvider {
    func placeholder(in context: Context) -> RevisionEntry { .placeholder }

    func getSnapshot(in context: Context, completion: @escaping (RevisionEntry) -> Void) {
        completion(context.isPreview && loadSnapshot() == nil ? .placeholder : entries(now: .now).first ?? .placeholder)
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<RevisionEntry>) -> Void) {
        // One entry now and one at each local midnight the snapshot covers; then ask again.
        completion(Timeline(entries: entries(now: .now), policy: .atEnd))
    }

    private func loadSnapshot() -> WidgetSnapshot? {
        UserDefaults(suiteName: WidgetSnapshot.appGroup)?
            .data(forKey: WidgetSnapshot.storageKey)
            .flatMap(WidgetSnapshot.decode)
    }

    private func entries(now: Date) -> [RevisionEntry] {
        let calendar = Calendar.current
        let today = Day(now, calendar: calendar)
        guard let snapshot = loadSnapshot() else {
            let tomorrow = today.adding(days: 1).startDate(in: calendar)
            return [RevisionEntry(date: now, dueCount: 0, streak: 0, card: nil, hasData: false),
                    RevisionEntry(date: tomorrow, dueCount: 0, streak: 0, card: nil, hasData: false)]
        }
        var out: [RevisionEntry] = []
        for offset in 0..<8 {
            let day = today.adding(days: offset)
            let entry = snapshot.entry(for: day)
            out.append(RevisionEntry(date: offset == 0 ? now : day.startDate(in: calendar), dueCount: entry.dueCount,
                                     streak: entry.streak, card: entry.cardOfDay, hasData: true))
        }
        return out
    }
}

// MARK: - Views

/// The app's palette (see the app's Theme.swift).
private enum WidgetPalette {
    static let ink = Color(light: 0x173D37, dark: 0xEEF2EA)
    static let paper = Color(light: 0xF5F5EF, dark: 0x0F1F1C)
    static let muted = Color(light: 0x6D7C75, dark: 0xA3B3AB)
    static let tagFill = Color(light: 0xEDF3E6, dark: 0x24463F)
    static let tagText = Color(light: 0x486342, dark: 0xC9DCB4)
    static let accent = Color(light: 0x87A867, dark: 0x9CC27A)
}

private extension Color {
    init(light: UInt32, dark: UInt32) {
        self.init(uiColor: UIColor { traits in
            let hex = traits.userInterfaceStyle == .dark ? dark : light
            return UIColor(red: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255,
                           blue: CGFloat(hex & 0xFF) / 255, alpha: 1)
        })
    }
}

struct RevisionWidgetView: View {
    let entry: RevisionEntry
    @Environment(\.widgetFamily) private var family

    var body: some View {
        Group {
            switch family {
            case .systemMedium: medium
            default: small
            }
        }
        .foregroundStyle(WidgetPalette.ink)
        .containerBackground(for: .widget) { WidgetPalette.paper }
    }

    private var dueText: String { entry.dueCount == 1 ? "card due" : "cards due" }

    private var streakText: String {
        entry.streak >= 1 ? "🔥 \(entry.streak)-day streak" : "Start a streak today"
    }

    private var small: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack {
                appMark
                Spacer()
            }
            Spacer(minLength: 4)
            Text("\(entry.dueCount)")
                .font(.system(.largeTitle, design: .rounded).weight(.bold))
                .monospacedDigit()
                .minimumScaleFactor(0.6)
                .lineLimit(1)
            Text(dueText)
                .font(.subheadline.weight(.semibold))
            Text(streakText)
                .font(.caption)
                .foregroundStyle(WidgetPalette.muted)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("HNC Revision. \(entry.dueCount) \(dueText). \(entry.streak >= 1 ? "\(entry.streak)-day streak" : "No streak yet")")
    }

    private var medium: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                appMark
                Text("CARD OF THE DAY")
                    .font(.caption2.weight(.bold))
                    .tracking(1.1)
                    .foregroundStyle(WidgetPalette.muted)
                Spacer()
            }
            if let card = entry.card {
                Text("\(card.unit) · \(card.topic)".uppercased())
                    .font(.caption2.weight(.medium))
                    .foregroundStyle(WidgetPalette.tagText)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .background(WidgetPalette.tagFill, in: RoundedRectangle(cornerRadius: 4))
                    .lineLimit(1)
                Text(card.q)
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(3)
                    .frame(maxWidth: .infinity, alignment: .leading)
            } else {
                Text(entry.hasData ? "No cards yet." : "Open HNC Revision to get started.")
                    .font(.subheadline)
            }
            Spacer(minLength: 0)
            HStack {
                Text("\(entry.dueCount) due")
                    .font(.caption.weight(.semibold))
                Spacer()
                Text(streakText)
                    .font(.caption)
                    .foregroundStyle(WidgetPalette.muted)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .widgetURL(entry.card.map { DeepLink.cardURL($0.id) })
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(mediumLabel)
    }

    private var mediumLabel: String {
        var text = "Card of the day."
        if let card = entry.card { text += " \(card.unit), \(card.topic). \(card.q)" }
        text += " \(entry.dueCount) \(dueText)."
        if entry.streak >= 1 { text += " \(entry.streak)-day streak." }
        return text
    }

    private var appMark: some View {
        Image("AppMark")
            .resizable()
            .frame(width: 22, height: 22)
            .clipShape(RoundedRectangle(cornerRadius: 5, style: .continuous))
            .accessibilityHidden(true)
    }
}

#Preview(as: .systemSmall) {
    RevisionWidget()
} timeline: {
    RevisionEntry.placeholder
}

#Preview(as: .systemMedium) {
    RevisionWidget()
} timeline: {
    RevisionEntry.placeholder
}
