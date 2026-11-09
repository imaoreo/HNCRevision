import RevisionCore
import SwiftUI

struct MoreScreen: View {
    @Environment(RevisionStore.self) private var store
    @Environment(\.dyslexiaMode) private var dyslexia

    var body: some View {
        List {
            Section {
                Button {
                    store.showRatedCards()
                } label: {
                    row("Rated cards", "Go back over cards you marked Confident or Revisit", "star.square.on.square")
                }
                .accessibilityHint("Opens Study showing rated cards")
                NavigationLink {
                    OutcomesScreen()
                } label: {
                    row("Outcomes", "How your revision matches the units", "list.bullet.clipboard")
                }
                NavigationLink {
                    VLSMScreen()
                } label: {
                    row("VLSM practice", "IPv4 subnetting exercises", "network")
                }
            }
            .listRowBackground(ThemeColors(dyslexia: dyslexia).surface)
            Section {
                NavigationLink {
                    SettingsScreen()
                } label: {
                    row("Settings", "Reading aloud, dyslexia mode, data and progress", "gearshape")
                }
            }
            .listRowBackground(ThemeColors(dyslexia: dyslexia).surface)
        }
        .scrollContentBackground(.hidden)
        .background(ThemeColors(dyslexia: dyslexia).paper)
        .navigationTitle("More")
        .onAppear { store.deactivateDecks() }
    }

    private func row(_ title: String, _ subtitle: String, _ icon: String) -> some View {
        HStack(spacing: 14) {
            Image(systemName: icon)
                .font(.title3)
                .foregroundStyle(Palette.ink)
                .frame(width: 30)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).appFont(.body, weight: .semibold).foregroundStyle(Palette.ink)
                Text(subtitle).appFont(.footnote).foregroundStyle(Palette.muted)
            }
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
    }
}
