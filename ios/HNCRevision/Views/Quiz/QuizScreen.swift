import RevisionCore
import SwiftUI

/// Multiple-choice practice for the current filter.
struct QuizScreen: View {
    @Environment(RevisionStore.self) private var store
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    Color.clear.frame(height: 0).id("top")
                    FilterBar()
                    tracker

                    if store.quizDeck.ids.isEmpty {
                        EmptyStateCard(
                            title: "No MCQs for this filter",
                            message: "Choose another outcome or topic. Graded Unit sections 2 and 3 require written responses; use the Outcomes tab for practice tasks."
                        )
                    } else if store.quizDeck.done {
                        QuizResultsView()
                    } else if let question = store.currentQuestion {
                        header
                        QuestionCard(question: question)
                            .id(question.id)
                        navigation
                        Text("Choose one answer, then check it. Your first checked answer is scored for this attempt. Quiz results are saved on this device separately from flashcard ratings.")
                            .appFont(.footnote)
                            .foregroundStyle(Palette.muted)
                    }
                }
                .padding(16)
                .readableWidth()
            }
            .refreshable { await store.refresh() }
            .background(PaperBackground())
            .navigationTitle("Quiz")
            .onChange(of: store.quizDeck.index) {
                withAnimation(reduceMotion ? nil : .default) { proxy.scrollTo("top", anchor: .top) }
            }
            .onChange(of: store.quizDeck.done) {
                withAnimation(reduceMotion ? nil : .default) { proxy.scrollTo("top", anchor: .top) }
            }
        }
        .onAppear { store.activateQuiz() }
    }

    private var tracker: some View {
        let questions = store.selectedQuestions
        return RoundTracker(
            kind: "Multiple choice",
            unseen: questions.filter { !store.quizSeen.contains($0.id) }.map(\.q),
            seen: questions.filter { store.quizSeen.contains($0.id) }.map(\.q)
        )
    }

    private var header: some View {
        let saved = store.savedQuizScore
        return VStack(alignment: .leading, spacing: 2) {
            Text("Question \(store.quizDeck.index + 1) of \(store.quizDeck.ids.count) · \(store.correctQuestions.count)/\(store.answeredQuestions.count) correct so far")
            Text("Saved latest answers: \(saved.correct)/\(saved.total) correct")
        }
        .appFont(.footnote)
        .foregroundStyle(Palette.muted)
        .accessibilityElement(children: .combine)
    }

    private var navigation: some View {
        let checked = store.currentQuestion.flatMap { store.response(for: $0.id)?.checked } ?? false
        return FlowButtons {
            Button("← Previous") { store.quizPrevious() }
                .buttonStyle(.app(.plain, expand: true))
                .disabled(store.quizDeck.index == 0)
                .accessibilityLabel("Previous question")
            Button("Finish & review") { store.finishQuiz() }
                .buttonStyle(.app(.plain, expand: true))
            Button(store.isLastQuestion ? "See results" : "Next question →") { store.quizNext() }
                .buttonStyle(.app(.primary, expand: true))
                .disabled(!checked)
                .accessibilityLabel(store.isLastQuestion ? "See results" : "Next question")
                .keyboardShortcut(.rightArrow, modifiers: [])
        }
    }
}

struct QuestionCard: View {
    let question: Question
    @Environment(RevisionStore.self) private var store

    private var response: QuizResponse? { store.response(for: question.id) }
    private var checked: Bool { response?.checked == true }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            VStack(alignment: .leading, spacing: 6) {
                Tag(text: "\(question.unit) · \(question.topic)")
                Text(metaLine.uppercased())
                    .tracking(0.6)
                    .appFont(.caption2, weight: .medium)
                    .foregroundStyle(Palette.muted)
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("\(question.unit), \(question.topic). \(metaLine)")

            Text(question.q)
                .appFont(.title3, weight: .semibold)
                .foregroundStyle(Palette.ink)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader)

            VStack(spacing: 10) {
                ForEach(Array(store.currentOrder.enumerated()), id: \.element) { position, option in
                    OptionRow(
                        letter: letter(position),
                        text: question.options[option],
                        state: state(for: option),
                        action: { store.choose(option) }
                    )
                    .disabled(checked)
                }
            }

            if checked, let response {
                feedback(response)
            } else {
                Button("Check answer") {
                    withAnimation(.snappy(duration: 0.2)) { store.checkAnswer() }
                }
                .buttonStyle(.app(.primary, expand: true))
                .disabled(response == nil)
                .keyboardShortcut(.return, modifiers: [])
            }
        }
        .cardStyle(padding: 22)
    }

    private var metaLine: String {
        var parts = [outcomeLabel(unit: question.unit, outcomes: question.outcomes)]
        if let cardId = question.cardId { parts.append("Flashcard \(cardId)") }
        return parts.joined(separator: " · ")
    }

    private func letter(_ position: Int) -> String {
        let letters = ["A", "B", "C", "D", "E", "F", "G", "H"]
        return position < letters.count ? letters[position] : String(position + 1)
    }

    private func state(for option: Int) -> OptionRow.State {
        guard let response else { return .idle }
        if response.checked {
            if option == question.answer { return .correct }
            if option == response.choice { return .wrong }
            return .idle
        }
        return option == response.choice ? .selected : .idle
    }

    private func feedback(_ response: QuizResponse) -> some View {
        let correct = response.choice == question.answer
        return VStack(alignment: .leading, spacing: 8) {
            Label(correct ? "Correct" : "Review this one",
                  systemImage: correct ? "checkmark.circle.fill" : "arrow.uturn.backward.circle.fill")
                .appFont(.headline)
            Text("Correct answer: \(question.options[question.answer])")
                .appFont(.body, weight: .medium)
            if !question.explanation.isEmpty {
                Text(question.explanation).appFont(.body)
            }
            ReferencesView(references: store.data.references(for: question))
            if let cardId = question.cardId {
                Text("Related flashcard: \(cardId). This answer supports revision; it does not demonstrate practical competence.")
                    .appFont(.footnote)
                    .foregroundStyle(Palette.muted)
            }
        }
        .foregroundStyle(Palette.ink)
        .fixedSize(horizontal: false, vertical: true)
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(correct ? Palette.tagFill : Palette.wrongFill, in: RoundedRectangle(cornerRadius: 10))
        .accessibilityElement(children: .contain)
    }
}

struct OptionRow: View {
    enum State { case idle, selected, correct, wrong }

    let letter: String
    let text: String
    let state: State
    let action: () -> Void

    @Environment(\.dyslexiaMode) private var dyslexia

    var body: some View {
        Button(action: action) {
            HStack(alignment: .firstTextBaseline, spacing: 12) {
                Image(systemName: icon)
                    .foregroundStyle(iconColor)
                    .accessibilityHidden(true)
                Text("\(Text("\(letter). ").bold())\(text)")
                    .appFont(.body)
                    .foregroundStyle(Palette.ink)
                    .multilineTextAlignment(.leading)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(14)
            .frame(minHeight: 44)
            .background(fill, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous).strokeBorder(border, lineWidth: state == .idle ? 1 : 1.5))
            .contentShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(letter). \(text)")
        .accessibilityValue(accessibilityValue)
        .accessibilityAddTraits(state == .selected ? .isSelected : [])
    }

    private var icon: String {
        switch state {
        case .idle: return "circle"
        case .selected: return "largecircle.fill.circle"
        case .correct: return "checkmark.circle.fill"
        case .wrong: return "xmark.circle.fill"
        }
    }

    private var iconColor: Color {
        switch state {
        case .idle: return Palette.muted
        case .selected: return Palette.ink
        case .correct: return Palette.correctBorder
        case .wrong: return Palette.wrongBorder
        }
    }

    private var fill: Color {
        switch state {
        case .idle: return ThemeColors(dyslexia: dyslexia).surface
        case .selected: return Palette.selectedFill
        case .correct: return Palette.correctFill
        case .wrong: return Palette.wrongFill
        }
    }

    private var border: Color {
        switch state {
        case .idle: return Palette.line
        case .selected: return Palette.ink
        case .correct: return Palette.correctBorder
        case .wrong: return Palette.wrongBorder
        }
    }

    private var accessibilityValue: String {
        switch state {
        case .idle: return ""
        case .selected: return "Selected"
        case .correct: return "Correct answer"
        case .wrong: return "Your answer, incorrect"
        }
    }
}

/// "Practice results" after finishing a quiz attempt.
struct QuizResultsView: View {
    @Environment(RevisionStore.self) private var store

    var body: some View {
        let answered = store.answeredQuestions
        let wrong = store.wrongQuestions
        let unanswered = store.unansweredCount
        VStack(alignment: .leading, spacing: 14) {
            VStack(alignment: .leading, spacing: 10) {
                Eyebrow("Practice results")
                Text("\(store.correctQuestions.count) correct out of \(answered.count) answered")
                    .appFont(.title2, weight: .semibold)
                    .accessibilityAddTraits(.isHeader)
                Text("\(wrong.count) to revise · \(unanswered) unanswered · \(store.quizDeck.ids.count) questions in this set")
                    .appFont(.body)
                    .foregroundStyle(Palette.muted)
                Text("This is a revision score, not an official unit grade. Flashcard confidence ratings are separate.")
                    .appFont(.footnote)
                    .foregroundStyle(Palette.muted)
                FlowButtons {
                    Button("Retry incorrect (\(wrong.count))") { store.retryIncorrect() }
                        .buttonStyle(.app(.plain, expand: true))
                        .disabled(wrong.isEmpty)
                    Button("Next round") { store.startNextQuizRound() }
                        .buttonStyle(.app(.primary, expand: true))
                    if unanswered > 0 {
                        Button("Continue unanswered") { store.continueUnanswered() }
                            .buttonStyle(.app(.plain, expand: true))
                    }
                }
                .padding(.top, 4)
            }
            .foregroundStyle(Palette.ink)
            .cardStyle(padding: 22)

            ForEach(answered) { question in
                ResultRow(question: question)
            }
        }
    }
}

struct ResultRow: View {
    let question: Question
    @Environment(RevisionStore.self) private var store
    @State private var expanded = false

    var body: some View {
        let choice = store.response(for: question.id)?.choice ?? 0
        let correct = choice == question.answer
        DisclosureGroup(isExpanded: $expanded) {
            VStack(alignment: .leading, spacing: 8) {
                Text("Your answer: \(question.options[safe: choice] ?? "")").appFont(.body)
                Text("\(Text("Correct answer: ").bold())\(question.options[question.answer])").appFont(.body)
                if !question.explanation.isEmpty {
                    Text(question.explanation).appFont(.body)
                }
                ReferencesView(references: store.data.references(for: question))
                Text(footer)
                    .appFont(.caption)
                    .foregroundStyle(Palette.muted)
            }
            .foregroundStyle(Palette.ink)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, 8)
        } label: {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(correct ? "✓" : "↺")
                    .fontWeight(.bold)
                    .foregroundStyle(correct ? Palette.correctBorder : Palette.wrongBorder)
                    .accessibilityLabel(correct ? "Correct" : "To revise")
                Text(question.q)
                    .appFont(.body, weight: .medium)
                    .foregroundStyle(Palette.ink)
                    .multilineTextAlignment(.leading)
            }
        }
        .tint(Palette.ink)
        .cardStyle(radius: 12, padding: 14)
    }

    private var footer: String {
        var parts = [question.unit, question.topic, outcomeLabel(unit: question.unit, outcomes: question.outcomes)]
        if let cardId = question.cardId { parts.append("Flashcard \(cardId)") }
        return parts.joined(separator: " · ")
    }
}

extension Array {
    subscript(safe index: Int) -> Element? { indices.contains(index) ? self[index] : nil }
}
