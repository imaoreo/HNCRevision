import RevisionCore
import SwiftUI

/// The "References" block under an answer. Links open in an in-app browser.
struct ReferencesView: View {
    let references: [Reference]
    @Environment(\.openURL) private var openURL

    var body: some View {
        if !references.isEmpty {
            VStack(alignment: .leading, spacing: 6) {
                Text("References")
                    .appFont(.subheadline, weight: .semibold)
                    .foregroundStyle(Palette.ink)
                    .accessibilityAddTraits(.isHeader)
                ForEach(references, id: \.url) { reference in
                    if let url = URL(string: reference.url) {
                        Button {
                            openURL(url)
                        } label: {
                            Text("\(reference.label) ↗")
                                .appFont(.subheadline)
                                .underline()
                                .foregroundStyle(Palette.link)
                                .multilineTextAlignment(.leading)
                                .frame(maxWidth: .infinity, minHeight: 32, alignment: .leading)
                                .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(reference.label)
                        .accessibilityHint("Opens the source in a browser")
                        .accessibilityAddTraits(.isLink)
                    }
                }
            }
        }
    }
}

/// The collapsible "Flashcards · x seen · y unseen this round" tracker.
struct RoundTracker: View {
    let kind: String
    let unseen: [String]
    let seen: [String]
    @State private var expanded = false

    var body: some View {
        DisclosureGroup(isExpanded: $expanded) {
            VStack(alignment: .leading, spacing: 12) {
                group("Unseen", unseen)
                group("Seen", seen)
            }
            .padding(.top, 8)
        } label: {
            Text("\(kind) · \(seen.count) seen · \(unseen.count) unseen this round")
                .appFont(.subheadline, weight: .medium)
                .foregroundStyle(Palette.ink)
                .frame(minHeight: 32, alignment: .leading)
        }
        .tint(Palette.ink)
        .cardStyle(radius: 12, padding: 12)
    }

    private func group(_ title: String, _ items: [String]) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("\(title) (\(items.count))")
                .appFont(.subheadline, weight: .semibold)
                .accessibilityAddTraits(.isHeader)
            if items.isEmpty {
                Text("None").appFont(.subheadline).foregroundStyle(Palette.muted)
            } else {
                ForEach(Array(items.enumerated()), id: \.offset) { _, text in
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Text("•").accessibilityHidden(true)
                        Text(text)
                    }
                    .appFont(.subheadline)
                    .foregroundStyle(Palette.ink)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// A centred empty state with an optional action.
struct EmptyStateCard: View {
    var eyebrow: String?
    let title: String
    let message: String
    var actionTitle: String?
    var action: (() -> Void)?

    var body: some View {
        VStack(spacing: 12) {
            if let eyebrow { Eyebrow(eyebrow) }
            Text(title)
                .appFont(.title2, weight: .semibold)
                .multilineTextAlignment(.center)
                .accessibilityAddTraits(.isHeader)
            Text(message)
                .appFont(.body)
                .foregroundStyle(Palette.muted)
                .multilineTextAlignment(.center)
            if let actionTitle, let action {
                Button(actionTitle, action: action)
                    .buttonStyle(.appPrimary)
                    .padding(.top, 4)
            }
        }
        .foregroundStyle(Palette.ink)
        .frame(maxWidth: .infinity)
        .padding(.vertical, 24)
        .cardStyle()
    }
}

/// Wraps buttons onto new lines when they don't fit (for large text sizes).
struct FlowButtons<Content: View>: View {
    @ViewBuilder let content: Content
    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 10) { content }
            VStack(spacing: 10) { content }
        }
    }
}
