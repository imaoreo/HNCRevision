import RevisionCore
import UIKit
import UserNotifications
import WidgetKit

/// Owns the store (so a notification tapped at launch can reach it) and handles notification taps.
final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    let store: RevisionStore
    let reminders = ReminderScheduler()
    private var refreshTask: Task<Void, Never>?

    override init() {
        let repository = DataRepository(
            bundledDirectory: Bundle.main.resourceURL ?? Bundle.main.bundleURL,
            cacheDirectory: DataRepository.defaultCacheDirectory
        )
        store = RevisionStore(repository: repository, storage: SharedStorage.make())
        super.init()
        store.onSave = { [weak self] in self?.scheduleRefresh() }
    }

    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        scheduleRefresh()
        return true
    }

    /// After saves settle, refresh the widget and the next week's reminders (their text includes due counts).
    private func scheduleRefresh() {
        refreshTask?.cancel()
        refreshTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(1))
            guard let self, !Task.isCancelled else { return }
            WidgetCenter.shared.reloadAllTimelines()
            await self.reminders.reschedule(self.store)
        }
    }

    // MARK: UNUserNotificationCenterDelegate

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter,
                                            willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        [.banner, .list]
    }

    /// Tapping a reminder opens Study → Due.
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter,
                                            didReceive response: UNNotificationResponse) async {
        await MainActor.run { self.store.openDue() }
    }
}

/// The saved state lives in the App Group so the widget can read the snapshot.
enum SharedStorage {
    static func make() -> UserDefaults {
        guard let group = UserDefaults(suiteName: WidgetSnapshot.appGroup) else { return .standard }
        // 1.0 kept everything in the standard defaults: copy it over once.
        StateMigration.copyOnce(key: RevisionStore.storageKey, from: UserDefaults.standard, to: group)
        return group
    }
}
