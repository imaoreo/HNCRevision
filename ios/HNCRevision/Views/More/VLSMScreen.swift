import RevisionCore
import SwiftUI

/// Variable Length Subnet Masking practice: the site's exercises, generated questions and quick drills.
struct VLSMScreen: View {
    enum Section: String, CaseIterable { case exercises, generate, drills }

    @State private var section: Section = .exercises
    @State private var exercise = 0
    @FocusState private var keyboardFocused: Bool

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Picker("VLSM practice", selection: $section) {
                    Text("Exercises").tag(Section.exercises)
                    Text("Generate").tag(Section.generate)
                    Text("Drills").tag(Section.drills)
                }
                .pickerStyle(.segmented)
                .accessibilityLabel("VLSM practice type")

                switch section {
                case .exercises: VLSMExercisesView(selection: $exercise, focused: $keyboardFocused)
                case .generate: VLSMGenerateView(focused: $keyboardFocused)
                case .drills: SubnetDrillView(focused: $keyboardFocused)
                }
            }
            .foregroundStyle(Palette.ink)
            .padding(16)
            .readableWidth()
        }
        .scrollDismissesKeyboard(.interactively)
        .background(PaperBackground())
        .navigationTitle("VLSM practice")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItemGroup(placement: .keyboard) {
                Spacer()
                Button("Done") { keyboardFocused = false }
            }
        }
    }
}

/// The site's six exercises (the 1.0 screen).
struct VLSMExercisesView: View {
    @Binding var selection: Int
    var focused: FocusState<Bool>.Binding
    @Environment(RevisionStore.self) private var store
    @Environment(\.dyslexiaMode) private var dyslexia
    @State private var hintsShown = 0
    @State private var showAnswer = false

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            if let exercise = store.data.vlsmExercises[safe: selection] {
                VLSMIntro()
                picker
                VLSMQuestionView(exercise: exercise)
                working
                VLSMHintList(hints: hints, shown: hintsShown)
                FlowButtons {
                    Button(hintButtonTitle) { withAnimation { hintsShown += 1 } }
                        .buttonStyle(.app(.soft, expand: true))
                        .disabled(hintsShown >= hintCount)
                    Button(showAnswer ? "Hide answer" : "Reveal answer") {
                        withAnimation { showAnswer.toggle() }
                    }
                    .buttonStyle(.app(.primary, expand: true))
                    .accessibilityValue(showAnswer ? "Expanded" : "Collapsed")
                }
                if showAnswer {
                    VLSMSolutionView(rows: exercise.rows)
                }
            } else {
                EmptyStateCard(title: "No VLSM exercises", message: "Check for updates in Settings.")
            }
        }
        .onChange(of: selection) {
            hintsShown = 0
            showAnswer = false
        }
    }

    private var hints: [String] { store.data.vlsmHints[safe: selection] ?? [] }
    private var hintCount: Int { hints.count }

    private var hintButtonTitle: String {
        if hintCount == 0 { return "No hints" }
        return hintsShown >= hintCount ? "All \(hintCount) hints shown" : "Show hint (\(hintsShown + 1) of \(hintCount))"
    }

    private var picker: some View {
        HStack {
            Text("Practice question").appFont(.subheadline, weight: .semibold)
            Spacer()
            Picker("Choose a practice question", selection: $selection) {
                ForEach(Array(store.data.vlsmExercises.enumerated()), id: \.offset) { index, exercise in
                    Text("\(index + 1). \(exercise.title)").tag(index)
                }
            }
            .pickerStyle(.menu)
            .tint(Palette.link)
        }
        .cardStyle(radius: 12, padding: 12)
    }

    private var working: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Your working").appFont(.subheadline, weight: .semibold)
            TextEditor(text: Binding(
                get: { store.vlsmWorking(for: selection) },
                set: { store.setVLSMWorking($0, for: selection) }
            ))
            .appFont(.body)
            .focused(focused)
            .scrollContentBackground(.hidden)
            .frame(minHeight: 160)
            .padding(8)
            .background(ThemeColors(dyslexia: dyslexia).surface, in: RoundedRectangle(cornerRadius: 10))
            .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(Palette.line))
            .overlay(alignment: .topLeading) {
                if store.vlsmWorking(for: selection).isEmpty {
                    Text("Start by ordering the LANs by host count, then calculate each block size.")
                        .appFont(.body)
                        .foregroundStyle(Palette.muted)
                        .padding(.horizontal, 13)
                        .padding(.vertical, 16)
                        .allowsHitTesting(false)
                        .accessibilityHidden(true)
                }
            }
            .accessibilityLabel("Your working")
            .accessibilityHint("Saved on this device for this question")
        }
    }
}

struct VLSMIntro: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Eyebrow("IPv4 subnetting practice")
            Text("Variable Length Subnet Masking")
                .appFont(.title2, weight: .semibold)
                .accessibilityAddTraits(.isHeader)
            Text("Give each LAN the smallest subnet that fits. Allocate largest first, starting at the base network address, and break ties in alphabetical order. Use conventional IPv4 subnets with network and broadcast addresses reserved.")
                .appFont(.body)
        }
    }
}

/// "Allocate subnets within …" and the list of LANs.
struct VLSMQuestionView: View {
    let exercise: VLSMExercise

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Allocate subnets within \(exercise.parent).")
                .appFont(.headline)
            ForEach(exercise.requests, id: \.self) { request in
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text("•").accessibilityHidden(true)
                    Text("\(request.lan) needs \(request.hosts) usable addresses")
                }
                .appFont(.body)
            }
            Text("Find each LAN’s network address and prefix, subnet mask, usable host range and broadcast address.")
                .appFont(.body)
                .foregroundStyle(Palette.muted)
        }
        .cardStyle(padding: 18)
    }
}

struct VLSMHintList: View {
    let hints: [String]
    let shown: Int

    var body: some View {
        if shown > 0 {
            VStack(alignment: .leading, spacing: 8) {
                ForEach(Array(hints.prefix(shown).enumerated()), id: \.offset) { index, hint in
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Text("\(index + 1).").fontWeight(.semibold)
                        Text(hint)
                    }
                    .appFont(.body)
                    .accessibilityElement(children: .combine)
                    .accessibilityLabel("Hint \(index + 1): \(hint)")
                }
            }
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Palette.tagFill, in: RoundedRectangle(cornerRadius: 10))
        }
    }
}

/// The worked solution: one sentence per LAN and the allocation table.
struct VLSMSolutionView: View {
    let rows: [VLSMRow]

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Worked solution")
                .appFont(.title3, weight: .semibold)
                .accessibilityAddTraits(.isHeader)
            ForEach(Array(rows.enumerated()), id: \.offset) { index, row in
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text("\(index + 1).").fontWeight(.semibold)
                    Text(row.explanation)
                }
                .appFont(.body)
                .accessibilityElement(children: .combine)
            }
            ScrollView(.horizontal) {
                Grid(alignment: .leading, horizontalSpacing: 16, verticalSpacing: 10) {
                    GridRow {
                        ForEach(VLSMExercise.tableHeadings, id: \.self) { heading in
                            Text(heading).appFont(.footnote, weight: .semibold)
                        }
                    }
                    Divider().overlay(Palette.line)
                    ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                        GridRow {
                            ForEach(Array(row.cells.enumerated()), id: \.offset) { _, cell in
                                Text(cell).appFont(.footnote).monospacedDigit()
                            }
                        }
                        .accessibilityElement(children: .ignore)
                        .accessibilityLabel(zip(VLSMExercise.tableHeadings, row.cells).map { "\($0): \($1)" }.joined(separator: ", "))
                        Divider().overlay(Palette.line)
                    }
                }
                .fixedSize()
                .padding(.vertical, 4)
            }
            .accessibilityLabel("Subnet allocation table")
        }
        .cardStyle(padding: 18)
    }
}
