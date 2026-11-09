import RevisionCore
import SwiftUI

/// Flashcards: Study (every card in the filter) and Rated cards (only rated ones).
struct StudyScreen: View {
    @Environment(RevisionStore.self) private var store
    @Environment(Speaker.self) private var speaker
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var direction: Int = 1

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Picker("Cards", selection: modeBinding) {
                    Text("Study").tag(CardMode.study)
                    Text("Rated cards").tag(CardMode.review)
                }
                .pickerStyle(.segmented)
                .accessibilityLabel("Card set")

                FilterBar()

                if store.cardMode == .review {
                    ratingFilterRow
                }

                tracker

                if let card = store.currentCard {
                    FlashcardView(card: card, onSwipe: go)
                        .id(card.id)
                        .transition(cardTransition)
                    navigationRow
                    progress
                } else {
                    EmptyStateCard(
                        eyebrow: "Your revision set",
                        title: store.cardMode == .review ? "No cards match this rating" : "No cards in this set",
                        message: "Choose another filter, or mark cards “Revisit” while studying.",
                        actionTitle: "Start studying",
                        action: { store.activateCards(.study) }
                    )
                    progress
                }
            }
            .padding(16)
            .readableWidth()
        }
        .refreshable { await store.refresh() }
        .background(PaperBackground())
        .navigationTitle(store.cardMode == .review ? "Rated cards" : "Study")
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button {
                    withAnimation(reduceMotion ? nil : .snappy) { store.shuffle() }
                } label: {
                    Label("Shuffle", systemImage: "shuffle")
                }
                .disabled(!store.canShuffle)
                .accessibilityHint("Deals the unseen cards in a random order")

                Button {
                    withAnimation(reduceMotion ? nil : .snappy) { store.restart() }
                } label: {
                    Label("Restart", systemImage: "arrow.counterclockwise")
                }
                .disabled(!store.canRestart)
                .accessibilityHint("Marks every card in this set unseen and starts again")
            }
        }
        .onAppear { store.activateCards(store.cardMode) }
        .onDisappear { speaker.stop() }
    }

    private var modeBinding: Binding<CardMode> {
        Binding(get: { store.cardMode }, set: { mode in
            speaker.stop()
            store.activateCards(mode)
        })
    }

    private var cardTransition: AnyTransition {
        if reduceMotion { return .opacity }
        return .asymmetric(
            insertion: .move(edge: direction > 0 ? .trailing : .leading).combined(with: .opacity),
            removal: .opacity
        )
    }

    private func go(_ step: Int) {
        direction = step
        withAnimation(reduceMotion ? nil : .snappy(duration: 0.25)) { store.move(step) }
    }

    private var ratingFilterRow: some View {
        HStack {
            Text("Flashcard rating")
                .appFont(.subheadline, weight: .medium)
            Spacer()
            Picker("Flashcard rating", selection: Binding(get: { store.ratingFilter },
                                                          set: { store.setRatingFilter($0) })) {
                ForEach(RatingFilter.allCases) { Text($0.label).tag($0) }
            }
            .pickerStyle(.menu)
            .tint(Palette.link)
        }
        .foregroundStyle(Palette.ink)
        .cardStyle(radius: 12, padding: 12)
    }

    private var tracker: some View {
        let cards = store.eligibleCards
        return RoundTracker(
            kind: "Flashcards",
            unseen: cards.filter { !store.cardSeen.contains($0.id) }.map(\.q),
            seen: cards.filter { store.cardSeen.contains($0.id) }.map(\.q)
        )
    }

    private var navigationRow: some View {
        HStack(spacing: 12) {
            Button {
                go(-1)
            } label: {
                Image(systemName: "arrow.left")
                    .frame(minWidth: 24)
            }
            .buttonStyle(.appPlain)
            .disabled(store.cardDeck.index == 0)
            .accessibilityLabel("Previous card")
            .keyboardShortcut(.leftArrow, modifiers: [])

            Spacer(minLength: 0)
            Text("Card \(store.cardDeck.index + 1) of \(store.cardDeck.ids.count)")
                .appFont(.footnote)
                .foregroundStyle(Palette.muted)
            Spacer(minLength: 0)

            Button {
                go(1)
            } label: {
                if store.isLastCard {
                    Text("Next round →")
                } else {
                    Image(systemName: "arrow.right").frame(minWidth: 24)
                }
            }
            .buttonStyle(.appPlain)
            .accessibilityLabel(store.isLastCard ? "Next round" : "Next card")
            .keyboardShortcut(.rightArrow, modifiers: [])
        }
    }

    private var progress: some View {
        let selected = store.selectedCards.count
        let confident = store.selectedConfidentCount
        return VStack(spacing: 6) {
            ProgressBar(value: selected > 0 ? Double(confident) / Double(selected) : 0)
            HStack {
                Text(store.currentCard == nil ? "No cards in this set" : "")
                Spacer()
                Text("\(confident) of \(selected) confident")
            }
            .appFont(.caption)
            .foregroundStyle(Palette.muted)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(confident) of \(selected) confident")
        .accessibilityValue(selected > 0 ? "\(Int((Double(confident) / Double(selected) * 100).rounded())) percent" : "")
    }
}

/// One flashcard: question, hint, answer, references and rating buttons.
struct FlashcardView: View {
    let card: Card
    var onSwipe: (Int) -> Void

    @Environment(RevisionStore.self) private var store
    @Environment(Speaker.self) private var speaker
    @State private var showHint = false

    private var revealed: Bool { store.cardDeck.revealed }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            meta

            Text(card.q)
                .appFont(.title2, weight: .semibold)
                .foregroundStyle(Palette.ink)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader)
                .accessibilityLabel("Question: \(card.q)")

            if revealed {
                answer
            } else {
                unrevealed
            }

            if store.speechEnabled {
                FlowButtons {
                    Button {
                        speaker.resume(currentSpeech)
                    } label: {
                        Label("Read aloud", systemImage: "speaker.wave.2")
                    }
                    .buttonStyle(.app(.plain, expand: true))
                    Button {
                        speaker.stopReading()
                    } label: {
                        Label("Stop reading", systemImage: "stop.circle")
                    }
                    .buttonStyle(.app(.plain, expand: true))
                    .accessibilityAddTraits(speaker.paused ? .isSelected : [])
                }
            }
        }
        .frame(minHeight: 300, alignment: .top)
        .cardStyle(padding: 22)
        .contentShape(Rectangle())
        .onTapGesture {
            if !revealed { store.reveal() }
        }
        .simultaneousGesture(
            DragGesture(minimumDistance: 30)
                .onEnded { value in
                    let dx = value.translation.width, dy = value.translation.height
                    guard abs(dx) > 70, abs(dx) > abs(dy) * 1.5 else { return }
                    onSwipe(dx < 0 ? 1 : -1)
                }
        )
        .accessibilityAction(named: "Next card") { onSwipe(1) }
        .accessibilityAction(named: "Previous card") { onSwipe(-1) }
        .onChange(of: revealed, initial: true) { _, isRevealed in
            guard store.speechEnabled else { return }
            speaker.speak(isRevealed ? card.a : card.q)
        }
    }

    private var currentSpeech: String {
        if revealed { return card.a }
        return showHint ? card.hint : card.q
    }

    private var meta: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .center, spacing: 8) {
                Tag(text: "\(card.unit) · \(card.topic)")
                Spacer(minLength: 0)
            }
            Text("\(store.statusLabel(for: card.id)) · #\(card.id)   \(outcomeLabel(unit: card.unit, outcomes: card.outcomes))".uppercased())
                .tracking(0.6)
                .appFont(.caption2, weight: .medium)
                .foregroundStyle(Palette.muted)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(card.unit), \(card.topic). \(store.statusLabel(for: card.id)). Card \(card.id). \(outcomeLabel(unit: card.unit, outcomes: card.outcomes))")
    }

    @ViewBuilder private var unrevealed: some View {
        if showHint {
            Text(card.hint)
                .appFont(.body)
                .foregroundStyle(Palette.ink)
                .fixedSize(horizontal: false, vertical: true)
                .padding(14)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Palette.tagFill, in: RoundedRectangle(cornerRadius: 10))
                .accessibilityLabel("Hint: \(card.hint)")
        }
        Spacer(minLength: 0)
        FlowButtons {
            Button(showHint ? "Hide hint" : "Show hint") {
                showHint.toggle()
                if store.speechEnabled {
                    if showHint { speaker.speak(card.hint) } else { speaker.stop() }
                }
            }
            .buttonStyle(.app(.soft, expand: true))
            .disabled(card.hint.isEmpty)
            .accessibilityValue(showHint ? "Hint shown" : "")

            Button {
                store.reveal()
            } label: {
                Text("Reveal answer ↗")
            }
            .buttonStyle(.app(.primary, expand: true))
            .keyboardShortcut(.space, modifiers: [])
            .accessibilityLabel("Reveal answer")
        }
    }

    @ViewBuilder private var answer: some View {
        Divider().overlay(Palette.line)
        Text(card.a)
            .appFont(.body)
            .foregroundStyle(Palette.ink)
            .fixedSize(horizontal: false, vertical: true)
            .textSelection(.enabled)
            .accessibilityLabel("Answer: \(card.a)")
        ReferencesView(references: store.data.references(for: card))
        FlowButtons {
            Button {
                withAnimation(.snappy(duration: 0.25)) { store.rate(.review) }
            } label: {
                Text("Revisit")
            }
            .buttonStyle(.app(.plain, expand: true))
            .keyboardShortcut("1", modifiers: [])
            .accessibilityHint("Rates this card Revisit and moves to the next")

            Button {
                withAnimation(.snappy(duration: 0.25)) { store.rate(.known) }
            } label: {
                Text("Confident ✓")
            }
            .buttonStyle(.app(.soft, expand: true))
            .keyboardShortcut("2", modifiers: [])
            .accessibilityLabel("Confident")
            .accessibilityHint("Rates this card Confident and moves to the next")
        }
    }
}
