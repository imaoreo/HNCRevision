import RevisionCore
import SafariServices
import SwiftUI
import UIKit

struct RootView: View {
    @Environment(RevisionStore.self) private var store
    @Environment(Speaker.self) private var speaker

    var body: some View {
        @Bindable var store = store
        TabView(selection: $store.tab) {
            Tab("Study", systemImage: "rectangle.on.rectangle.angled", value: AppTab.study) {
                NavigationStack { StudyScreen() }
            }
            .badge(store.totalDueCount)
            Tab("Quiz", systemImage: "checklist", value: AppTab.quiz) {
                NavigationStack { QuizScreen() }
            }
            Tab("Browse", systemImage: "magnifyingglass", value: AppTab.browse) {
                NavigationStack { BrowseScreen() }
            }
            Tab("More", systemImage: "ellipsis.circle", value: AppTab.more) {
                NavigationStack { MoreScreen() }
            }
        }
        .tint(Palette.link)
        .environment(\.dyslexiaMode, store.dyslexiaMode)
        .environment(\.openURL, OpenURLAction { url in
            guard url.scheme == "https" || url.scheme == "http" else { return .systemAction }
            return SafariPresenter.present(url) ? .handled : .systemAction
        })
        .onChange(of: store.tab) { speaker.stop() }
    }
}

/// Opens links in an in-app Safari view over whatever is currently on screen (including sheets).
@MainActor
enum SafariPresenter {
    static func present(_ url: URL) -> Bool {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let scene = scenes.first { $0.activationState == .foregroundActive } ?? scenes.first
        guard let root = scene?.keyWindow?.rootViewController ?? scene?.windows.first?.rootViewController else {
            return false
        }
        var top = root
        while let presented = top.presentedViewController, !presented.isBeingDismissed { top = presented }
        let safari = SFSafariViewController(url: url)
        safari.preferredControlTintColor = UIColor { traits in
            traits.userInterfaceStyle == .dark ? UIColor(hex: 0xD5E9A7) : UIColor(hex: 0x173D37)
        }
        top.present(safari, animated: true)
        return true
    }
}
