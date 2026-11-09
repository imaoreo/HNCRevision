import RevisionCore
import SwiftUI

@main
struct HNCRevisionApp: App {
    @State private var store: RevisionStore
    @State private var speaker = Speaker()
    @Environment(\.scenePhase) private var scenePhase

    init() {
        let repository = DataRepository(
            bundledDirectory: Bundle.main.resourceURL ?? Bundle.main.bundleURL,
            cacheDirectory: DataRepository.defaultCacheDirectory
        )
        _store = State(initialValue: RevisionStore(repository: repository))
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(store)
                .environment(speaker)
                .task { await store.refreshIfStale() }
        }
        .onChange(of: scenePhase) { _, phase in
            switch phase {
            case .active:
                Task { await store.refreshIfStale() }
            case .background:
                speaker.stop()
            default:
                break
            }
        }
    }
}
