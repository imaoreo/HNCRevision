import RevisionCore
import SwiftUI

/// How the revision material maps to each unit's published outcomes.
struct OutcomesScreen: View {
    @Environment(RevisionStore.self) private var store
    @Environment(\.openURL) private var openURL

    var body: some View {
        ScrollView {
            FilterBar()
                .padding([.horizontal, .top], 16)
                .readableWidth()
            LazyVStack(alignment: .leading, spacing: 16) {
                intro
                ForEach(units) { unit in
                    unitSection(unit)
                }
                Text("These are original study prompts, not official assessment questions or assessor-approved coverage.")
                    .appFont(.footnote)
                    .foregroundStyle(Palette.muted)
                    .padding(.top, 8)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .readableWidth()
        }
        .background(PaperBackground())
        .navigationTitle("Outcomes")
        .navigationBarTitleDisplayMode(.inline)
    }

    private var units: [StudyUnit] {
        store.filter.unit == Filter.allUnits ? store.data.units : store.data.units.filter { $0.code == store.filter.unit }
    }

    private var intro: some View {
        let checked = store.data.units.first.flatMap { store.data.coverage[$0.code]?.checked } ?? ""
        return VStack(alignment: .leading, spacing: 10) {
            Eyebrow(checked.isEmpty ? "Specification check" : "Specification check · \(checked)")
            Text("How your revision matches the units")
                .appFont(.title2, weight: .semibold)
                .accessibilityAddTraits(.isHeader)
            Text("Mapped against the published outcome statements and knowledge/skills lists. Counts show related revision material, **not completion or full syllabus coverage**. Practical tasks, written work and lecturer materials remain essential.")
                .appFont(.body)
            Text("J0J734 is an integrated exam: its rows show examination sections rather than invented learning outcomes.")
                .appFont(.body)
                .foregroundStyle(Palette.muted)
        }
        .foregroundStyle(Palette.ink)
    }

    @ViewBuilder
    private func unitSection(_ unit: StudyUnit) -> some View {
        if let coverage = store.data.coverage[unit.code] {
            let graded = unit.code == Filter.gradedUnit
            VStack(alignment: .leading, spacing: 12) {
                Divider().overlay(Palette.line)
                Text("\(unit.code) · \(unit.title)")
                    .appFont(.title3, weight: .semibold)
                    .accessibilityAddTraits(.isHeader)
                if !coverage.gaps.isEmpty {
                    Text(coverage.gaps).appFont(.body)
                }
                VStack(alignment: .leading, spacing: 4) {
                    if let url = URL(string: coverage.source) {
                        Button("Published specification ↗") { openURL(url) }
                            .appFont(.footnote, weight: .semibold)
                            .foregroundStyle(Palette.link)
                            .underline()
                            .frame(minHeight: 32)
                            .accessibilityAddTraits(.isLink)
                    }
                    Text("Version \(coverage.version) · Relevant pages \(coverage.pages) · Checked \(coverage.checked)")
                        .appFont(.footnote)
                        .foregroundStyle(Palette.muted)
                }

                ForEach(coverage.outcomes) { outcome in
                    let counts = store.data.coverageCounts(unit: unit.code, outcome: outcome.id)
                    VStack(alignment: .leading, spacing: 10) {
                        Text(graded ? outcome.title : "Outcome \(outcome.id) · \(outcome.title)")
                            .appFont(.headline)
                            .accessibilityAddTraits(.isHeader)
                        Text("\(counts.cards) related flashcards · \(counts.questions) MCQs")
                            .appFont(.subheadline, weight: .semibold)
                        if !outcome.note.isEmpty {
                            Text(outcome.note)
                                .appFont(.subheadline)
                                .foregroundStyle(Palette.note)
                        }
                        if !outcome.practice.isEmpty {
                            Text("\(Text("Apply it:").bold()) \(outcome.practice)")
                                .appFont(.subheadline)
                        }
                        Button(graded ? "Study this section" : "Study this outcome") {
                            store.studyOutcome(unit: unit.code, outcome: outcome.id)
                        }
                        .buttonStyle(.appPlain)
                        .accessibilityHint("Opens Study filtered to this \(graded ? "section" : "outcome")")
                    }
                    .foregroundStyle(Palette.ink)
                    .cardStyle(radius: 12, padding: 16)
                }
            }
            .foregroundStyle(Palette.ink)
        }
    }
}
