import Observation
import RevisionCore
import UIKit
import UserNotifications

/// The daily reminder: the next 7 days are scheduled with their own due counts, and rescheduled after saves.
@MainActor
@Observable
final class ReminderScheduler {
    static let identifierPrefix = "daily-reminder-"
    private let center = UNUserNotificationCenter.current()

    /// Asks for permission (the first time) and reports whether notifications are allowed.
    func requestPermission() async -> Bool {
        let settings = await center.notificationSettings()
        switch settings.authorizationStatus {
        case .authorized, .provisional, .ephemeral:
            return true
        case .notDetermined:
            return (try? await center.requestAuthorization(options: [.alert, .sound, .badge])) ?? false
        default:
            return false
        }
    }

    func reschedule(_ store: RevisionStore) async {
        let pending = await center.pendingNotificationRequests()
        let old = pending.map(\.identifier).filter { $0.hasPrefix(Self.identifierPrefix) }
        center.removePendingNotificationRequests(withIdentifiers: old)
        guard store.reminderEnabled else { return }
        let status = await center.notificationSettings().authorizationStatus
        guard status == .authorized || status == .provisional || status == .ephemeral else { return }

        let calendar = Calendar.current
        for plan in store.reminderPlan() {
            let content = UNMutableNotificationContent()
            content.title = Reminder.title
            content.body = plan.body
            content.sound = .default
            content.userInfo = ["link": DeepLink.dueURL.absoluteString]
            let components = calendar.dateComponents([.year, .month, .day, .hour, .minute], from: plan.fireDate)
            let trigger = UNCalendarNotificationTrigger(dateMatching: components, repeats: false)
            let request = UNNotificationRequest(identifier: Self.identifierPrefix + plan.day.description,
                                                content: content, trigger: trigger)
            try? await center.add(request)
        }
    }

    /// If notifications were turned off in Settings, the reminder toggle follows; otherwise refresh the week ahead.
    func appBecameActive(_ store: RevisionStore) async {
        if store.reminderEnabled {
            let status = await center.notificationSettings().authorizationStatus
            if status == .denied { store.reminderEnabled = false }
        }
        await reschedule(store)
    }

    func openSystemSettings() {
        if let url = URL(string: UIApplication.openNotificationSettingsURLString) {
            UIApplication.shared.open(url)
        }
    }
}
