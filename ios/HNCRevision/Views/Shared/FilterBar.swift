import RevisionCore
import SwiftUI

/// The compact unit / outcome / topic summary shown at the top of Study, Rated, Browse and Quiz.
/// Tapping it opens the filter sheet.
struct FilterBar: View {
    @Environment(RevisionStore.self) private var store
    @State private var showingFilters = false

    var body: some View {
        Button {
            showingFilters = true
        } label: {
            VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .top, spacing: 12) {
                    VStack(alignment: .leading, spacing: 3) {
                        Eyebrow(store.filter.unit == Filter.allUnits ? "All units" : store.filter.unit)
                        Text(unitTitle)
                            .appFont(.headline)
                            .foregroundStyle(Palette.ink)
                            .multilineTextAlignment(.leading)
                        Text(selectionSummary)
                            .appFont(.footnote)
                            .foregroundStyle(Palette.muted)
                            .multilineTextAlignment(.leading)
                    }
                    Spacer(minLength: 8)
                    Image(systemName: "line.3.horizontal.decrease.circle.fill")
                        .font(.title2)
                        .foregroundStyle(Palette.ink)
                        .accessibilityHidden(true)
                }
                StatLine()
            }
            .cardStyle(radius: 16, padding: 14)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Filters. \(unitTitle). \(selectionSummary). \(StatLine.text(store))")
        .accessibilityHint("Choose unit, outcome and topic")
        .accessibilityAddTraits(.isButton)
        .sheet(isPresented: $showingFilters) {
            FilterSheet()
        }
    }

    private var unitTitle: String {
        store.data.unit(store.filter.unit)?.title ?? "All units"
    }

    private var selectionSummary: String {
        let outcome = store.outcomeOptions.first { $0.id == store.filter.outcome }?.label
            ?? (store.filter.unit == Filter.gradedUnit ? "All sections" : "All outcomes")
        return "\(outcome) · \(store.filter.topic)"
    }
}

/// "182 flashcards · 3 confident · 2 to revisit" for the current unit.
struct StatLine: View {
    @Environment(RevisionStore.self) private var store

    static func text(_ store: RevisionStore) -> String {
        "\(store.unitCards.count) flashcards · \(store.confidentCount) confident · \(store.revisitCount) to revisit"
    }

    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 18) { stats }
            VStack(alignment: .leading, spacing: 4) { stats }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Self.text(store))
    }

    @ViewBuilder private var stats: some View {
        stat(store.unitCards.count, "flashcards")
        stat(store.confidentCount, "confident")
        stat(store.revisitCount, "to revisit")
    }

    private func stat(_ value: Int, _ label: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 4) {
            Text("\(value)").appFont(.headline, weight: .bold).foregroundStyle(Palette.ink)
            Text(label).appFont(.caption).foregroundStyle(Palette.muted)
        }
        .fixedSize()
    }
}

struct FilterSheet: View {
    @Environment(RevisionStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @Environment(\.dyslexiaMode) private var dyslexia
    @Environment(\.openURL) private var openURL

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker(selection: unitBinding) {
                        Text("All units").tag(Filter.allUnits)
                        ForEach(store.data.units) { unit in
                            Text("\(unit.code) · \(unit.title)").tag(unit.code)
                        }
                    } label: {
                        Text("Unit").appFont(.body)
                    }
                    .pickerStyle(.navigationLink)

                    Picker(selection: outcomeBinding) {
                        ForEach(store.outcomeOptions) { option in
                            Text(option.label).tag(option.id)
                        }
                    } label: {
                        Text(store.filter.unit == Filter.gradedUnit ? "Section" : "Outcome").appFont(.body)
                    }
                    .pickerStyle(.navigationLink)
                    .disabled(store.filter.unit == Filter.allUnits)
                } header: {
                    Text("Your unit")
                } footer: {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(store.unitInfo)
                        if let url = URL(string: store.specURL) {
                            Button("Unit specification ↗") { openURL(url) }
                                .font(.footnote.weight(.semibold))
                                .foregroundStyle(Palette.link)
                                .accessibilityHint("Opens the SQA document")
                        }
                    }
                    .appFont(.footnote)
                }
                .listRowBackground(ThemeColors(dyslexia: dyslexia).surface)

                Section {
                    ForEach(store.topics) { item in
                        Button {
                            store.setTopic(item.topic)
                        } label: {
                            HStack {
                                Text(item.topic)
                                    .appFont(.body)
                                    .foregroundStyle(Palette.ink)
                                Spacer()
                                Text("\(item.count)")
                                    .appFont(.callout)
                                    .foregroundStyle(Palette.muted)
                                if item.topic == store.filter.topic {
                                    Image(systemName: "checkmark")
                                        .foregroundStyle(Palette.ink)
                                        .fontWeight(.semibold)
                                }
                            }
                            .frame(minHeight: 30)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("\(item.topic), \(item.count) cards")
                        .accessibilityAddTraits(item.topic == store.filter.topic ? .isSelected : [])
                    }
                } header: {
                    Text("Your topics")
                } footer: {
                    Text("Make it stick: answer aloud before revealing. Use your own words and give an example.")
                        .appFont(.footnote)
                }
                .listRowBackground(ThemeColors(dyslexia: dyslexia).surface)

                Section {
                    StatLine()
                }
                .listRowBackground(ThemeColors(dyslexia: dyslexia).surface)
            }
            .scrollContentBackground(.hidden)
            .background(ThemeColors(dyslexia: dyslexia).paper)
            .navigationTitle("Filters")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                        .fontWeight(.semibold)
                }
            }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }

    private var unitBinding: Binding<String> {
        Binding(get: { store.filter.unit }, set: { store.setUnit($0) })
    }

    private var outcomeBinding: Binding<String> {
        Binding(get: { store.filter.outcome }, set: { store.setOutcome($0) })
    }
}
