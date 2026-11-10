import RevisionCore
import SwiftUI

struct SettingsScreen: View {
    @Environment(RevisionStore.self) private var store
    @Environment(Speaker.self) private var speaker
    @Environment(ReminderScheduler.self) private var reminders
    @Environment(\.dyslexiaMode) private var dyslexia
    @Environment(\.openURL) private var openURL
    @State private var confirmingReset = false
    @State private var checkedManually = false
    @State private var resetStarsAndNotes = false
    @State private var notificationsDenied = false
    @State private var askingPermission = false

    var body: some View {
        @Bindable var store = store
        Form {
            Section {
                Toggle(isOn: reminderBinding) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Daily reminder").appFont(.body)
                        Text("A notification with the number of cards due, once a day.")
                            .appFont(.footnote)
                            .foregroundStyle(Palette.muted)
                    }
                }
                .disabled(askingPermission)
                if store.reminderEnabled {
                    DatePicker(selection: reminderTime, displayedComponents: .hourAndMinute) {
                        Text("Time").appFont(.body)
                    }
                    .accessibilityLabel("Reminder time")
                }
            } header: {
                Text("Reminders")
            } footer: {
                Text("Tapping the reminder opens the cards that are due.")
            }
            .listRowBackground(rowBackground)

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
                    if let change = store.lastChange {
                        Text(lastChangeText(change))
                            .appFont(.footnote)
                            .foregroundStyle(Palette.muted)
                    }
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
                Toggle(isOn: $resetStarsAndNotes) {
                    Text("Also delete stars and notes").appFont(.body)
                }
                Button("Reset saved progress", role: .destructive) { confirmingReset = true }
            } footer: {
                Text("Ratings, review schedules, quiz answers, mock exams, stars, notes and your place in each deck are saved on this device only. Your streak is kept.")
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
            Button("Reset", role: .destructive) {
                store.resetProgress(includingStarsAndNotes: resetStarsAndNotes)
                resetStarsAndNotes = false
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Clear all flashcard ratings and saved quiz answers? This resets \(store.data.cards.count) flashcards and \(store.data.questions.count) quiz questions on this device."
                 + (resetStarsAndNotes ? " Your stars and notes will be deleted too." : ""))
        }
        .alert("Notifications are off", isPresented: $notificationsDenied) {
            Button("Open Settings") { reminders.openSystemSettings() }
            Button("Not now", role: .cancel) {}
        } message: {
            Text("To get a daily reminder, allow notifications for HNC Revision in Settings.")
        }
    }

    /// Turning the reminder on asks for permission first; if it's refused, the toggle goes back off.
    private var reminderBinding: Binding<Bool> {
        Binding(get: { store.reminderEnabled }, set: { on in
            guard on else {
                store.reminderEnabled = false
                return
            }
            askingPermission = true
            Task {
                let allowed = await reminders.requestPermission()
                askingPermission = false
                store.reminderEnabled = allowed
                if !allowed { notificationsDenied = true }
            }
        })
    }

    private var reminderTime: Binding<Date> {
        Binding(get: {
            Calendar.current.date(bySettingHour: store.reminderMinutes / 60, minute: store.reminderMinutes % 60,
                                  second: 0, of: Date()) ?? Date()
        }, set: { date in
            let c = Calendar.current.dateComponents([.hour, .minute], from: date)
            store.reminderMinutes = (c.hour ?? 18) * 60 + (c.minute ?? 0)
        })
    }

    private func lastChangeText(_ change: ContentChange) -> String {
        var parts = ["\(change.added) added", "\(change.updated) updated"]
        if change.questionsChanged > 0 {
            parts.append(change.questionsChanged == 1 ? "1 quiz question changed" : "\(change.questionsChanged) quiz questions changed")
        }
        return "Last change: \(parts.joined(separator: ", ")) · \(shortDate(change.date))"
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
