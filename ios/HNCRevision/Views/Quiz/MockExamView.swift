import RevisionCore
import SwiftUI

/// Mock exam: setup (with history), an attempt in progress, or the results of the one just submitted.
struct MockExamView: View {
    var scrollToTop: () -> Void
    @Environment(RevisionStore.self) private var store

    var body: some View {
        Group {
            if let attempt = store.mockAttempt {
                if attempt.isSubmitted {
                    MockResultsView()
                } else {
                    MockAttemptView()
                }
            } else {
                MockSetupView()
            }
        }
        .onChange(of: store.mockAttempt?.isSubmitted) { scrollToTop() }
        .onChange(of: store.mockAttempt?.index) { scrollToTop() }
    }
}

// MARK: - Setup

struct MockSetupView: View {
    @Environment(RevisionStore.self) private var store
    @State private var units: Set<String> = []
    @State private var count = 20
    @State private var timed = true
    @State private var initialised = false

    var body: some View {
        let available = store.mockAvailable(units: units)
        let questions = min(count, available)
        VStack(alignment: .leading, spacing: 14) {
            VStack(alignment: .leading, spacing: 10) {
                Eyebrow("Mock exam")
                Text("Test yourself against the clock")
                    .appFont(.title2, weight: .semibold)
                    .accessibilityAddTraits(.isHeader)
                Text("Random questions from the units you choose. Answers can be changed until you submit; you’ll see your score and a review at the end.")
                    .appFont(.body)
                    .foregroundStyle(Palette.muted)
            }
            .foregroundStyle(Palette.ink)

            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text("Units").appFont(.headline).accessibilityAddTraits(.isHeader)
                    Spacer()
                    Button(units.count == store.data.units.count ? "Clear" : "Select all") {
                        units = units.count == store.data.units.count ? [] : Set(store.data.units.map(\.code))
                    }
                    .appFont(.subheadline, weight: .semibold)
                    .foregroundStyle(Palette.link)
                    .frame(minHeight: 44)
                }
                ForEach(store.data.units) { unit in
                    let selected = units.contains(unit.code)
                    let n = store.mockAvailable(units: [unit.code])
                    Button {
                        if selected { units.remove(unit.code) } else { units.insert(unit.code) }
                    } label: {
                        HStack(alignment: .firstTextBaseline, spacing: 12) {
                            Image(systemName: selected ? "checkmark.square.fill" : "square")
                                .foregroundStyle(selected ? Palette.ink : Palette.muted)
                                .accessibilityHidden(true)
                            VStack(alignment: .leading, spacing: 2) {
                                Text("\(unit.code) · \(unit.title)")
                                    .appFont(.body)
                                    .foregroundStyle(Palette.ink)
                                    .multilineTextAlignment(.leading)
                                Text(n == 1 ? "1 question" : "\(n) questions")
                                    .appFont(.caption)
                                    .foregroundStyle(Palette.muted)
                            }
                            Spacer(minLength: 0)
                        }
                        .frame(minHeight: 44)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel("\(unit.code), \(unit.title), \(n) questions")
                    .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
                }
            }
            .cardStyle(padding: 16)

            VStack(alignment: .leading, spacing: 12) {
                Text("Questions").appFont(.headline).accessibilityAddTraits(.isHeader)
                Picker("Number of questions", selection: $count) {
                    ForEach(MockExam.questionCounts, id: \.self) { Text("\($0)").tag($0) }
                }
                .pickerStyle(.segmented)
                Text(available == 0 ? "Choose at least one unit with questions."
                     : "\(questions) questions (\(available) available)")
                    .appFont(.footnote)
                    .foregroundStyle(Palette.muted)
                Toggle(isOn: $timed) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Timer").appFont(.body)
                        Text(timed && questions > 0
                             ? "90 seconds per question · \(MockExam.spokenDuration(questions * MockExam.secondsPerQuestion)) in all"
                             : "90 seconds per question")
                            .appFont(.footnote)
                            .foregroundStyle(Palette.muted)
                    }
                }
                .tint(Palette.progressFill)
                Button("Start") {
                    store.startMock(units: units, count: count, timed: timed)
                }
                .buttonStyle(.app(.primary, expand: true))
                .disabled(available == 0)
                .accessibilityHint("Starts a mock exam of \(questions) questions")
            }
            .foregroundStyle(Palette.ink)
            .cardStyle(padding: 16)

            VStack(alignment: .leading, spacing: 10) {
                Button("Graded Unit Section 1 style") { store.startPresetMock() }
                    .buttonStyle(.app(.soft, expand: true))
                    .accessibilityHint("20 questions, 5 each from J0H934, J0HL34, J0HJ34 and J0J734, with a 30-minute timer")
                Text("Practice in the style of the Graded Unit exam’s selected-response section. Not an official paper.")
                    .appFont(.footnote)
                    .foregroundStyle(Palette.muted)
                Text("5 questions each from \(MockExam.presetUnits.joined(separator: ", ")) · 30 minutes")
                    .appFont(.footnote)
                    .foregroundStyle(Palette.muted)
            }
            .cardStyle(padding: 16)

            history
        }
        .onAppear {
            guard !initialised else { return }
            initialised = true
            units = store.defaultMockUnits
        }
    }

    @ViewBuilder private var history: some View {
        if !store.mockHistory.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                Text("Recent attempts").appFont(.headline).accessibilityAddTraits(.isHeader)
                ForEach(Array(store.mockHistory.enumerated()), id: \.offset) { _, record in
                    HStack {
                        Text("\(shortDate(record.date)) · \(record.correct)/\(record.total) · \(record.percent)%")
                            .appFont(.body, weight: .medium)
                            .monospacedDigit()
                        Spacer()
                        Text(record.units.count > 3 ? "\(record.units.count) units" : record.units.joined(separator: ", "))
                            .appFont(.caption)
                            .foregroundStyle(Palette.muted)
                            .multilineTextAlignment(.trailing)
                    }
                    .frame(minHeight: 32)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel("\(record.date.formatted(date: .long, time: .omitted)): \(record.correct) out of \(record.total), \(record.percent) percent")
                    Divider().overlay(Palette.line)
                }
            }
            .foregroundStyle(Palette.ink)
            .cardStyle(padding: 16)
        }
    }
}

// MARK: - In progress

struct MockAttemptView: View {
    @Environment(RevisionStore.self) private var store
    @State private var confirmingSubmit = false
    @State private var confirmingAbandon = false

    var body: some View {
        if let attempt = store.mockAttempt, let question = store.currentMockQuestion {
            VStack(alignment: .leading, spacing: 14) {
                header(attempt)
                questionCard(question, attempt: attempt)
                FlowButtons {
                    Button("← Previous") { store.mockGo(to: attempt.index - 1) }
                        .buttonStyle(.app(.plain, expand: true))
                        .disabled(attempt.index == 0)
                        .accessibilityLabel("Previous question")
                    Button("Next →") { store.mockGo(to: attempt.index + 1) }
                        .buttonStyle(.app(.plain, expand: true))
                        .disabled(attempt.index >= attempt.ids.count - 1)
                        .accessibilityLabel("Next question")
                        .keyboardShortcut(.rightArrow, modifiers: [])
                }
                grid(attempt)
                Button("Submit") {
                    if attempt.unansweredCount > 0 { confirmingSubmit = true } else { store.submitMock() }
                }
                .buttonStyle(.app(.primary, expand: true))
                Button("Abandon attempt", role: .destructive) { confirmingAbandon = true }
                    .buttonStyle(.app(.plain, expand: true))
                Text("No feedback until you submit. Mock answers don’t change your Practice results.")
                    .appFont(.footnote)
                    .foregroundStyle(Palette.muted)
            }
            .task(id: attempt.startedAt) {
                // Submits automatically when the time runs out (also after reopening the app).
                while !Task.isCancelled {
                    if store.checkMockTimer() { break }
                    try? await Task.sleep(for: .seconds(1))
                }
            }
            .alert("Submit with unanswered questions?", isPresented: $confirmingSubmit) {
                Button("Submit") { store.submitMock() }
                Button("Keep going", role: .cancel) {}
            } message: {
                Text(attempt.unansweredCount == 1 ? "1 question has no answer." : "\(attempt.unansweredCount) questions have no answer.")
            }
            .alert("Abandon this attempt?", isPresented: $confirmingAbandon) {
                Button("Abandon", role: .destructive) { store.closeMock() }
                Button("Keep going", role: .cancel) {}
            } message: {
                Text("Your answers won’t be scored or saved.")
            }
        }
    }

    private func header(_ attempt: MockAttempt) -> some View {
        HStack(alignment: .firstTextBaseline) {
            Text("Question \(attempt.index + 1) of \(attempt.ids.count)")
                .appFont(.subheadline, weight: .semibold)
                .foregroundStyle(Palette.ink)
            Spacer()
            if attempt.deadline != nil {
                TimelineView(.periodic(from: .now, by: 1)) { context in
                    let left = Int((attempt.remaining(at: context.date) ?? 0).rounded(.up))
                    Label(MockExam.clock(left), systemImage: "timer")
                        .appFont(.subheadline, weight: .semibold)
                        .monospacedDigit()
                        .foregroundStyle(left < 60 ? Palette.wrongBorder : Palette.ink)
                        .accessibilityLabel("\(MockExam.spokenDuration(left)) left")
                }
            }
        }
        .accessibilityElement(children: .combine)
    }

    private func questionCard(_ question: Question, attempt: MockAttempt) -> some View {
        let chosen = attempt.answers[question.id]
        return VStack(alignment: .leading, spacing: 14) {
            Tag(text: "\(question.unit) · \(question.topic)")
                .accessibilityLabel("\(question.unit), \(question.topic)")
            Text(question.q)
                .appFont(.title3, weight: .semibold)
                .foregroundStyle(Palette.ink)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader)
            VStack(spacing: 10) {
                ForEach(Array(store.currentMockOrder.enumerated()), id: \.element) { position, option in
                    OptionRow(letter: ["A", "B", "C", "D", "E", "F"][safe: position] ?? String(position + 1),
                              text: question.options[option],
                              state: chosen == option ? .selected : .idle,
                              action: { store.mockChoose(option) })
                }
            }
        }
        .cardStyle(padding: 22)
    }

    private func grid(_ attempt: MockAttempt) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("\(attempt.ids.count - attempt.unansweredCount) of \(attempt.ids.count) answered")
                .appFont(.footnote)
                .foregroundStyle(Palette.muted)
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 44), spacing: 8)], spacing: 8) {
                ForEach(Array(attempt.ids.enumerated()), id: \.offset) { index, id in
                    let answered = attempt.answers[id] != nil
                    let current = index == attempt.index
                    Button {
                        store.mockGo(to: index)
                    } label: {
                        Text("\(index + 1)")
                            .appFont(.callout, weight: .semibold)
                            .monospacedDigit()
                            .frame(maxWidth: .infinity, minHeight: 44)
                            .foregroundStyle(answered ? Palette.onAccent : Palette.ink)
                            .background(answered ? Palette.accent : Color.clear, in: RoundedRectangle(cornerRadius: 8))
                            .overlay(RoundedRectangle(cornerRadius: 8)
                                .strokeBorder(current ? Palette.ink : Palette.line, lineWidth: current ? 2 : 1))
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Question \(index + 1)")
                    .accessibilityValue([answered ? "Answered" : "Not answered", current ? "Current" : nil]
                        .compactMap { $0 }.joined(separator: ", "))
                    .accessibilityAddTraits(current ? .isSelected : [])
                }
            }
        }
        .cardStyle(radius: 12, padding: 12)
    }
}

// MARK: - Results

struct MockResultsView: View {
    @Environment(RevisionStore.self) private var store

    var body: some View {
        if let attempt = store.mockAttempt, let result = store.mockResult {
            VStack(alignment: .leading, spacing: 14) {
                VStack(alignment: .leading, spacing: 10) {
                    Eyebrow("Mock exam results")
                    Text("\(result.correct) / \(result.total) (\(result.percent)%)")
                        .appFont(.largeTitle, weight: .bold)
                        .accessibilityLabel("\(result.correct) out of \(result.total), \(result.percent) percent")
                        .accessibilityAddTraits(.isHeader)
                    Text("Time taken: \(MockExam.spokenDuration(result.durationSeconds))")
                        .appFont(.body)
                        .foregroundStyle(Palette.muted)
                    Text("This is a revision score, not an official unit grade.")
                        .appFont(.footnote)
                        .foregroundStyle(Palette.muted)
                    Button("New mock exam") { store.closeMock() }
                        .buttonStyle(.app(.primary, expand: true))
                        .padding(.top, 4)
                }
                .foregroundStyle(Palette.ink)
                .cardStyle(padding: 22)

                VStack(alignment: .leading, spacing: 8) {
                    Text("By unit").appFont(.headline).accessibilityAddTraits(.isHeader)
                    ForEach(result.perUnit, id: \.unit) { score in
                        HStack(alignment: .firstTextBaseline, spacing: 12) {
                            Text("\(score.unit) · \(store.data.unit(score.unit)?.title ?? score.unit)")
                                .appFont(.subheadline)
                                .multilineTextAlignment(.leading)
                            Spacer(minLength: 8)
                            Text("\(score.correct) / \(score.asked)")
                                .appFont(.subheadline, weight: .semibold)
                                .monospacedDigit()
                            Text("\(score.percent)%")
                                .appFont(.subheadline)
                                .monospacedDigit()
                                .foregroundStyle(Palette.muted)
                                .frame(minWidth: 44, alignment: .trailing)
                        }
                        .accessibilityElement(children: .ignore)
                        .accessibilityLabel("\(score.unit), \(store.data.unit(score.unit)?.title ?? ""): \(score.correct) out of \(score.asked), \(score.percent) percent")
                        Divider().overlay(Palette.line)
                    }
                }
                .foregroundStyle(Palette.ink)
                .cardStyle(padding: 16)

                Text("Review").appFont(.headline).foregroundStyle(Palette.ink).accessibilityAddTraits(.isHeader)
                ForEach(store.mockQuestions) { question in
                    ResultRow(question: question, choice: attempt.answers[question.id])
                }
            }
        }
    }
}
