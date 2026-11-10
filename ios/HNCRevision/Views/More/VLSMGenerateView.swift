import RevisionCore
import SwiftUI

/// Random VLSM questions with answer boxes that can be checked.
struct VLSMGenerateView: View {
    var focused: FocusState<Bool>.Binding
    @Environment(\.dyslexiaMode) private var dyslexia
    @State private var difficulty: VLSMGenerator.Difficulty = .easy
    @State private var exercise: VLSMExercise?
    @State private var working = ""
    @State private var hintsShown = 0
    @State private var showAnswer = false
    /// Row index → field → typed answer.
    @State private var answers: [Int: [VLSMAnswer.Field: String]] = [:]
    @State private var checked = false

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            VStack(alignment: .leading, spacing: 8) {
                Eyebrow("Generated practice")
                Text("New VLSM questions")
                    .appFont(.title2, weight: .semibold)
                    .accessibilityAddTraits(.isHeader)
                Text("Same rules as the exercises: largest LAN first, ties in alphabetical order, starting at the parent’s network address.")
                    .appFont(.body)
            }
            Picker("Difficulty", selection: $difficulty) {
                ForEach(VLSMGenerator.Difficulty.allCases) { Text($0.label).tag($0) }
            }
            .pickerStyle(.segmented)
            .accessibilityLabel("Difficulty")
            Text(difficultyCaption)
                .appFont(.footnote)
                .foregroundStyle(Palette.muted)

            if let exercise {
                VLSMQuestionView(exercise: exercise)
                workingEditor
                answerBoxes(exercise)
                VLSMHintList(hints: VLSMSolver.hints(for: exercise), shown: hintsShown)
                FlowButtons {
                    Button(hintsShown >= 3 ? "All 3 hints shown" : "Show hint (\(hintsShown + 1) of 3)") {
                        withAnimation { hintsShown += 1 }
                    }
                    .buttonStyle(.app(.soft, expand: true))
                    .disabled(hintsShown >= 3)
                    Button(showAnswer ? "Hide answer" : "Reveal answer") {
                        withAnimation { showAnswer.toggle() }
                    }
                    .buttonStyle(.app(.primary, expand: true))
                    .accessibilityValue(showAnswer ? "Expanded" : "Collapsed")
                }
                if showAnswer {
                    VLSMSolutionView(rows: exercise.rows)
                }
            }
            Button {
                generate()
            } label: {
                Label("New question", systemImage: "arrow.triangle.2.circlepath")
            }
            .buttonStyle(.app(.plain, expand: true))
        }
        .onAppear { if exercise == nil { generate() } }
        .onChange(of: difficulty) { generate() }
    }

    private var difficultyCaption: String {
        switch difficulty {
        case .easy: return "A /24 shared by 3 LANs."
        case .medium: return "A /24 shared by 4 or 5 LANs."
        case .hard: return "A /23 or /22 shared by 5 or 6 LANs."
        }
    }

    private func generate() {
        var rng = SystemRandomNumberGenerator()
        exercise = VLSMGenerator.generate(difficulty, using: &rng)
        working = ""
        hintsShown = 0
        showAnswer = false
        answers = [:]
        checked = false
    }

    private var workingEditor: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Your working").appFont(.subheadline, weight: .semibold)
            TextEditor(text: $working)
                .appFont(.body)
                .focused(focused)
                .scrollContentBackground(.hidden)
                .frame(minHeight: 120)
                .padding(8)
                .background(ThemeColors(dyslexia: dyslexia).surface, in: RoundedRectangle(cornerRadius: 10))
                .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(Palette.line))
                .accessibilityLabel("Your working")
                .accessibilityHint("Not saved for generated questions")
            Text("Working isn’t saved for generated questions.")
                .appFont(.caption)
                .foregroundStyle(Palette.muted)
        }
    }

    private func answerBoxes(_ exercise: VLSMExercise) -> some View {
        let total = exercise.rows.count * VLSMAnswer.Field.allCases.count
        let right = exercise.rows.enumerated().reduce(0) { sum, item in
            sum + VLSMAnswer.Field.allCases.filter { field in
                VLSMAnswer.isCorrect(answers[item.offset]?[field] ?? "", field: field, row: item.element)
            }.count
        }
        return VStack(alignment: .leading, spacing: 14) {
            Text("Your answers")
                .appFont(.title3, weight: .semibold)
                .accessibilityAddTraits(.isHeader)
            Text("In allocation order. Write the network as a.b.c.d/n.")
                .appFont(.footnote)
                .foregroundStyle(Palette.muted)
            ForEach(Array(exercise.rows.enumerated()), id: \.offset) { index, row in
                VStack(alignment: .leading, spacing: 8) {
                    Text("\(row.lan) · \(row.hosts) hosts")
                        .appFont(.headline)
                        .accessibilityAddTraits(.isHeader)
                    ForEach(VLSMAnswer.Field.allCases, id: \.self) { field in
                        answerField(index: index, row: row, field: field)
                    }
                }
                if index < exercise.rows.count - 1 { Divider().overlay(Palette.line) }
            }
            Button("Check answers") {
                focused.wrappedValue = false
                withAnimation { checked = true }
            }
            .buttonStyle(.app(.primary, expand: true))
            if checked {
                Text("\(right) of \(total) correct")
                    .appFont(.headline)
                    .accessibilityAddTraits(.updatesFrequently)
            }
        }
        .cardStyle(padding: 18)
    }

    private func answerField(index: Int, row: VLSMRow, field: VLSMAnswer.Field) -> some View {
        let text = answers[index]?[field] ?? ""
        let correct = VLSMAnswer.isCorrect(text, field: field, row: row)
        let binding = Binding(
            get: { answers[index]?[field] ?? "" },
            set: { answers[index, default: [:]][field] = $0 }
        )
        return ViewThatFits(in: .horizontal) {
            HStack(spacing: 10) {
                Text(field.label).appFont(.subheadline).frame(width: 130, alignment: .leading)
                input(binding, field: field, row: row, correct: correct)
            }
            VStack(alignment: .leading, spacing: 4) {
                Text(field.label).appFont(.subheadline)
                input(binding, field: field, row: row, correct: correct)
            }
        }
    }

    private func input(_ text: Binding<String>, field: VLSMAnswer.Field, row: VLSMRow, correct: Bool) -> some View {
        TextField(field == .network ? "a.b.c.d/n" : "a.b.c.d", text: text)
            .appFont(.body)
            .monospacedDigit()
            .keyboardType(.numbersAndPunctuation)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .focused(focused)
            .padding(.horizontal, 10)
            .frame(minHeight: 44)
            .background(fill(correct), in: RoundedRectangle(cornerRadius: 8))
            .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(border(correct), lineWidth: checked ? 1.5 : 1))
            .accessibilityLabel("\(row.lan) \(field.label)")
            .accessibilityValue(checked ? (correct ? "Correct" : "Incorrect") : "")
    }

    private func fill(_ correct: Bool) -> Color {
        guard checked else { return ThemeColors(dyslexia: dyslexia).surface }
        return correct ? Palette.correctFill : Palette.wrongFill
    }

    private func border(_ correct: Bool) -> Color {
        guard checked else { return Palette.line }
        return correct ? Palette.correctBorder : Palette.wrongBorder
    }
}

/// Rounds of ten quick subnetting questions.
struct SubnetDrillView: View {
    var focused: FocusState<Bool>.Binding
    @Environment(RevisionStore.self) private var store
    @Environment(\.dyslexiaMode) private var dyslexia
    @State private var round: DrillRound?
    @State private var answer = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            VStack(alignment: .leading, spacing: 8) {
                Eyebrow("Subnetting drills")
                Text("Quick-fire questions")
                    .appFont(.title2, weight: .semibold)
                    .accessibilityAddTraits(.isHeader)
                Text("Masks, prefixes, host counts, network and broadcast addresses for /16 to /30. Ten per round.")
                    .appFont(.body)
                Text(store.drillBest > 0 ? "Best score: \(store.drillBest) / \(SubnetDrill.roundLength)" : "No best score yet")
                    .appFont(.footnote, weight: .semibold)
                    .foregroundStyle(Palette.muted)
            }
            if let round {
                if round.isFinished {
                    finished(round)
                } else if let drill = round.current {
                    question(drill, round: round)
                }
            }
        }
        .onAppear { if round == nil { newRound() } }
    }

    private func newRound() {
        var rng = SystemRandomNumberGenerator()
        round = DrillRound(using: &rng)
        answer = ""
    }

    private func question(_ drill: SubnetDrill, round: DrillRound) -> some View {
        let result = round.currentResult
        return VStack(alignment: .leading, spacing: 14) {
            Text("Question \(round.index + 1) of \(round.questions.count) · \(round.score) correct")
                .appFont(.footnote)
                .foregroundStyle(Palette.muted)
            Text(drill.prompt)
                .appFont(.title3, weight: .semibold)
                .monospacedDigit()
                .accessibilityAddTraits(.isHeader)
            TextField("Your answer", text: $answer)
                .appFont(.body)
                .monospacedDigit()
                .keyboardType(.numbersAndPunctuation)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .focused(focused)
                .disabled(result != nil)
                .submitLabel(.done)
                .onSubmit(check)
                .padding(.horizontal, 12)
                .frame(minHeight: 44)
                .background(fill(result), in: RoundedRectangle(cornerRadius: 10))
                .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(border(result), lineWidth: result == nil ? 1 : 1.5))
                .accessibilityLabel("Your answer")

            if let result {
                VStack(alignment: .leading, spacing: 6) {
                    Label(result ? "Correct" : "Incorrect",
                          systemImage: result ? "checkmark.circle.fill" : "xmark.circle.fill")
                        .appFont(.headline)
                    Text("Answer: \(drill.displayAnswer)").appFont(.body, weight: .medium)
                    Text(drill.working).appFont(.body)
                }
                .foregroundStyle(Palette.ink)
                .fixedSize(horizontal: false, vertical: true)
                .padding(14)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(result ? Palette.correctFill : Palette.wrongFill, in: RoundedRectangle(cornerRadius: 10))
                .accessibilityElement(children: .combine)

                Button(round.index == round.questions.count - 1 ? "See score" : "Next question →") {
                    self.round?.next()
                    answer = ""
                    if self.round?.isFinished == true, let score = self.round?.score { store.recordDrillScore(score) }
                }
                .buttonStyle(.app(.primary, expand: true))
            } else {
                Button("Check", action: check)
                    .buttonStyle(.app(.primary, expand: true))
                    .disabled(answer.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
        .cardStyle(padding: 22)
    }

    private func check() {
        guard round?.currentResult == nil, !answer.trimmingCharacters(in: .whitespaces).isEmpty else { return }
        withAnimation(.snappy(duration: 0.2)) { _ = round?.check(answer) }
    }

    private func finished(_ round: DrillRound) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Eyebrow("Round complete")
            Text("\(round.score) / \(round.questions.count)")
                .appFont(.largeTitle, weight: .bold)
                .accessibilityLabel("\(round.score) out of \(round.questions.count)")
                .accessibilityAddTraits(.isHeader)
            Text(round.score >= store.drillBest ? "Your best score so far." : "Best score: \(store.drillBest) / \(round.questions.count)")
                .appFont(.body)
                .foregroundStyle(Palette.muted)
            Button("New round") { newRound() }
                .buttonStyle(.app(.primary, expand: true))
        }
        .foregroundStyle(Palette.ink)
        .cardStyle(padding: 22)
    }

    private func fill(_ result: Bool?) -> Color {
        guard let result else { return ThemeColors(dyslexia: dyslexia).surface }
        return result ? Palette.correctFill : Palette.wrongFill
    }

    private func border(_ result: Bool?) -> Color {
        guard let result else { return Palette.line }
        return result ? Palette.correctBorder : Palette.wrongBorder
    }
}
