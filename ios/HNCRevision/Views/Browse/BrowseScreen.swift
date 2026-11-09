import RevisionCore
import SwiftUI

/// Searchable list of every card in the current filter.
struct BrowseScreen: View {
    @Environment(RevisionStore.self) private var store
    @Environment(Speaker.self) private var speaker
    @State private var query = ""

    var body: some View {
        let rows = matchingCards
        ScrollView {
            FilterBar()
                .padding([.horizontal, .top], 16)
                .readableWidth()
            LazyVStack(alignment: .leading, spacing: 12) {
                Text(rows.count == 1 ? "1 card" : "\(rows.count) cards")
                    .appFont(.footnote)
                    .foregroundStyle(Palette.muted)
                    .padding(.horizontal, 4)
                if rows.isEmpty {
                    Text("No matching cards. Try another filter or search.")
                        .appFont(.body)
                        .foregroundStyle(Palette.muted)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 30)
                } else {
                    ForEach(rows) { card in
                        BrowseRow(card: card)
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .readableWidth()
        }
        .refreshable { await store.refresh() }
        .background(PaperBackground())
        .navigationTitle("Browse")
        .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always),
                    prompt: "Search questions or answers…")
        .onChange(of: store.filter) { query = "" }
        .onAppear { store.deactivateDecks() }
        .onDisappear { speaker.stop() }
    }

    private var matchingCards: [Card] {
        let q = query.trimmingCharacters(in: .whitespaces).lowercased()
        let cards = store.selectedCards
        guard !q.isEmpty else { return cards }
        return cards.filter { "\($0.q) \($0.a) \($0.topic) \($0.unit)".lowercased().contains(q) }
    }
}

struct BrowseRow: View {
    let card: Card
    @Environment(RevisionStore.self) private var store
    @Environment(Speaker.self) private var speaker
    @State private var expanded = false
    @State private var showHint = false

    var body: some View {
        DisclosureGroup(isExpanded: $expanded) {
            VStack(alignment: .leading, spacing: 12) {
                Text(card.a)
                    .appFont(.body)
                    .textSelection(.enabled)
                    .fixedSize(horizontal: false, vertical: true)
                ReferencesView(references: store.data.references(for: card))

                if !card.hint.isEmpty {
                    DisclosureGroup(isExpanded: $showHint) {
                        VStack(alignment: .leading, spacing: 8) {
                            Text(card.hint)
                                .appFont(.body)
                                .frame(maxWidth: .infinity, alignment: .leading)
                            if store.speechEnabled {
                                Button {
                                    speaker.resume(card.hint)
                                } label: {
                                    Label("Read hint", systemImage: "speaker.wave.2")
                                }
                                .buttonStyle(.appPlain)
                            }
                        }
                        .padding(.top, 6)
                    } label: {
                        Text(showHint ? "Hide hint" : "Show hint")
                            .appFont(.subheadline, weight: .semibold)
                            .frame(minHeight: 32)
                    }
                }

                if store.speechEnabled {
                    FlowButtons {
                        Button {
                            speaker.resume(card.q)
                        } label: {
                            Label("Read question", systemImage: "speaker.wave.2")
                        }
                        .buttonStyle(.app(.plain, expand: true))
                        Button {
                            speaker.resume(card.a)
                        } label: {
                            Label("Read answer", systemImage: "speaker.wave.2")
                        }
                        .buttonStyle(.app(.plain, expand: true))
                        Button {
                            speaker.stopReading()
                        } label: {
                            Label("Stop reading", systemImage: "stop.circle")
                        }
                        .buttonStyle(.app(.plain, expand: true))
                    }
                }

                Text("\(card.unit) · \(card.topic) · \(outcomeLabel(unit: card.unit, outcomes: card.outcomes)) · Card \(card.id)")
                    .appFont(.caption)
                    .foregroundStyle(Palette.muted)
            }
            .foregroundStyle(Palette.ink)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, 10)
        } label: {
            Text(card.q)
                .appFont(.body, weight: .semibold)
                .foregroundStyle(Palette.ink)
                .multilineTextAlignment(.leading)
                .frame(maxWidth: .infinity, minHeight: 32, alignment: .leading)
        }
        .tint(Palette.ink)
        .cardStyle(radius: 12, padding: 16)
    }
}
