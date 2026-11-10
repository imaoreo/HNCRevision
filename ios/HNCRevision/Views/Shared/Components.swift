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

/// ☆ / ★ toggle that bookmarks a flashcard.
struct StarButton: View {
    let cardId: Int
    /// Adds "Star flashcard <id>" wording where the star is for a related card (quiz feedback).
    var related = false
    @Environment(RevisionStore.self) private var store

    var body: some View {
        let starred = store.isStarred(cardId)
        Button {
            store.toggleStar(cardId)
        } label: {
            if related {
                Label(starred ? "Starred flashcard \(cardId)" : "Star flashcard \(cardId)",
                      systemImage: starred ? "star.fill" : "star")
                    .appFont(.subheadline, weight: .semibold)
                    .foregroundStyle(Palette.ink)
                    .frame(minHeight: 44)
            } else {
                Image(systemName: starred ? "star.fill" : "star")
                    .font(.title3)
                    .foregroundStyle(starred ? Palette.note : Palette.muted)
                    .frame(minWidth: 44, minHeight: 44)
            }
        }
        .buttonStyle(.plain)
        .contentShape(Rectangle())
        .accessibilityLabel(starred ? "Unstar card" : "Star card")
        .accessibilityValue(starred ? "Starred" : "")
        .accessibilityHint(related ? "Saves flashcard \(cardId) to Starred" : "")
        .sensoryFeedback(.selection, trigger: starred)
    }
}

/// "My note" under a revealed answer: shows the note or an "Add a note" button, editing in a sheet.
struct NoteSection: View {
    let cardId: Int
    @Environment(RevisionStore.self) private var store
    @State private var editing = false

    var body: some View {
        let note = store.note(for: cardId)
        VStack(alignment: .leading, spacing: 6) {
            if let note {
                Text("My note")
                    .appFont(.subheadline, weight: .semibold)
                    .foregroundStyle(Palette.ink)
                    .accessibilityAddTraits(.isHeader)
                Button {
                    editing = true
                } label: {
                    Text(note)
                        .appFont(.body)
                        .foregroundStyle(Palette.ink)
                        .multilineTextAlignment(.leading)
                        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                        .padding(12)
                        .background(Palette.tagFill, in: RoundedRectangle(cornerRadius: 10))
                }
                .buttonStyle(.plain)
                .accessibilityLabel("My note: \(note)")
                .accessibilityHint("Edit your note")
            } else {
                Button {
                    editing = true
                } label: {
                    Label("Add a note", systemImage: "square.and.pencil")
                }
                .buttonStyle(.appPlain)
                .accessibilityHint("Write a note for this card, saved on this device")
            }
        }
        .sheet(isPresented: $editing) {
            NoteEditor(cardId: cardId)
        }
    }
}

struct NoteEditor: View {
    let cardId: Int
    @Environment(RevisionStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @Environment(\.dyslexiaMode) private var dyslexia
    @State private var text = ""
    @FocusState private var focused: Bool

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 10) {
                if let card = store.data.cardsById[cardId] {
                    Text(card.q)
                        .appFont(.subheadline, weight: .semibold)
                        .foregroundStyle(Palette.muted)
                        .lineLimit(3)
                }
                TextEditor(text: $text)
                    .appFont(.body)
                    .focused($focused)
                    .scrollContentBackground(.hidden)
                    .padding(8)
                    .background(ThemeColors(dyslexia: dyslexia).surface, in: RoundedRectangle(cornerRadius: 10))
                    .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(Palette.line))
                    .accessibilityLabel("Note")
                    .onChange(of: text) { _, value in
                        if value.count > RevisionStore.noteLimit { text = String(value.prefix(RevisionStore.noteLimit)) }
                    }
                Text("\(text.count) / \(RevisionStore.noteLimit)")
                    .appFont(.caption)
                    .foregroundStyle(Palette.muted)
                    .frame(maxWidth: .infinity, alignment: .trailing)
                    .accessibilityLabel("\(text.count) of \(RevisionStore.noteLimit) characters")
                if store.note(for: cardId) != nil {
                    Button("Delete note", role: .destructive) {
                        store.setNote("", for: cardId)
                        dismiss()
                    }
                    .buttonStyle(.app(.plain, expand: true))
                }
            }
            .foregroundStyle(Palette.ink)
            .padding(16)
            .background(ThemeColors(dyslexia: dyslexia).paper)
            .navigationTitle("My note")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        store.setNote(text, for: cardId)
                        dismiss()
                    }
                    .fontWeight(.semibold)
                }
            }
        }
        .tint(Palette.link)
        .environment(\.dyslexiaMode, store.dyslexiaMode)
        .presentationDetents([.medium, .large])
        .onAppear {
            text = store.note(for: cardId) ?? ""
            focused = true
        }
    }
}

/// "12 Nov" for dates in lists.
func shortDate(_ date: Date) -> String {
    date.formatted(.dateTime.day().month(.abbreviated))
}

/// "12 Nov" for a local day.
func shortDate(_ day: Day) -> String {
    shortDate(day.startDate(in: .current))
}
