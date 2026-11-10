import RevisionCore
import SwiftUI

@main
struct HNCRevisionApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @State private var speaker = Speaker()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(appDelegate.store)
                .environment(appDelegate.reminders)
                .environment(speaker)
                .onOpenURL { url in
                    speaker.stop()
                    appDelegate.store.open(url)
                }
                .task { await appDelegate.store.refreshIfStale() }
        }
        .onChange(of: scenePhase) { _, phase in
            switch phase {
            case .active:
                Task { await appDelegate.store.refreshIfStale() }
                Task { await appDelegate.reminders.appBecameActive(appDelegate.store) }
            case .background:
                speaker.stop()
            default:
                break
            }
        }
    }
}
