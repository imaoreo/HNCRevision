import RevisionCore
import SwiftUI

struct SettingsScreen: View {
    @Environment(RevisionStore.self) private var store
    @Environment(Speaker.self) private var speaker
    @Environment(\.dyslexiaMode) private var dyslexia
    @Environment(\.openURL) private var openURL
    @State private var confirmingReset = false
    @State private var checkedManually = false

    var body: some View {
        @Bindable var store = store
        Form {
            Section {
                Toggle(isOn: $store.speechEnabled) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Read flashcards aloud").appFont(.body)
                        Text("Hear questions as you move through Study and Rated cards, then hints and answers when you reveal them.")
                            .appFont(.footnote)
                            .foregroundStyle(Palette.muted)
                    }
                }
                Toggle(isOn: $store.dyslexiaMode) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Dyslexia reading mode").appFont(.body)
                        Text("Use a plain font, more space between letters and lines, and a softer background.")
                            .appFont(.footnote)
                            .foregroundStyle(Palette.muted)
                    }
                }
            } header: {
                Text("Accessibility")
            }
            .listRowBackground(rowBackground)

            Section {
                VStack(alignment: .leading, spacing: 6) {
                    TimelineView(.periodic(from: .now, by: 30)) { context in
                        Text(sourceDescription(now: context.date))
                            .appFont(.body, weight: .medium)
                    }
                    Text("\(store.data.units.count) units · \(store.data.cards.count) flashcards · \(store.data.questions.count) quiz questions")
                        .appFont(.footnote)
                        .foregroundStyle(Palette.muted)
                }
                .padding(.vertical, 4)
                .accessibilityElement(children: .combine)

                Button {
                    checkedManually = true
                    Task { await store.refresh() }
                } label: {
                    HStack {
                        Text(store.isRefreshing ? "Checking…" : "Check for updates").appFont(.body)
                        Spacer()
                        if store.isRefreshing { ProgressView() }
                    }
                }
                .disabled(store.isRefreshing)

                if checkedManually, !store.isRefreshing, let ok = store.lastRefreshSucceeded {
                    Text(ok ? "You have the latest questions from the website." : "Couldn’t reach the website. You can keep revising with the copy on this device.")
                        .appFont(.footnote)
                        .foregroundStyle(Palette.muted)
                }
            } header: {
                Text("Questions and answers")
            } footer: {
                Text("The app downloads the latest cards from the revision website when it opens, and keeps a copy for offline study.")
            }
            .listRowBackground(rowBackground)

            Section {
                Button("Reset saved progress", role: .destructive) { confirmingReset = true }
            } footer: {
                Text("Ratings, quiz answers and your place in each deck are saved on this device only.")
            }
            .listRowBackground(rowBackground)

            Section {
                Text("Original revision prompts; use alongside lecturer materials. Content from the HNC Revision Studio website.")
                    .appFont(.body)
                Button {
                    openURL(DataRepository.siteURL)
                } label: {
                    Text("Open the revision website ↗").appFont(.body)
                }
                .accessibilityAddTraits(.isLink)
                LabeledContent("Version", value: appVersion)
            } header: {
                Text("About")
            }
            .listRowBackground(rowBackground)
        }
        .tint(Palette.link)
        .scrollContentBackground(.hidden)
        .background(ThemeColors(dyslexia: dyslexia).paper)
        .navigationTitle("Settings")
        .navigationBarTitleDisplayMode(.inline)
        .onChange(of: store.speechEnabled) { _, enabled in
            if !enabled { speaker.stop() }
        }
        .alert("Reset saved progress?", isPresented: $confirmingReset) {
            Button("Reset", role: .destructive) { store.resetProgress() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Clear all flashcard ratings and saved quiz answers? This resets \(store.data.cards.count) flashcards and \(store.data.questions.count) quiz questions on this device.")
        }
    }

    private var rowBackground: Color { ThemeColors(dyslexia: dyslexia).surface }

    private func sourceDescription(now: Date) -> String {
        switch store.origin {
        case .live(let date):
            let formatter = RelativeDateTimeFormatter()
            formatter.unitsStyle = .full
            let relative = now.timeIntervalSince(date) < 60 ? "just now" : formatter.localizedString(for: date, relativeTo: now)
            return "Live from the website · updated \(relative)"
        case .saved(let date):
            return "Saved copy from \(date.formatted(date: .abbreviated, time: .shortened)) (offline)"
        case .bundled:
            return "Built-in copy (offline)"
        }
    }

    private var appVersion: String {
        let info = Bundle.main.infoDictionary
        let version = info?["CFBundleShortVersionString"] as? String ?? "1.0"
        let build = info?["CFBundleVersion"] as? String ?? "1"
        return "\(version) (\(build))"
    }
}
