# HNC Revision

Native iOS (SwiftUI) and Android (Kotlin, Jetpack Compose) apps for the HNC Cyber Security revision site,
https://data-security-revision-cards.exquisitemeadow.chatgpt.site/. Flashcards, quizzes, unit outcomes and VLSM practice.
The apps download the site's `cards.js`, `quiz.js` and `vlsm.js` in the background and fall back to a built-in copy offline.

- `SPEC.md`: what both apps do.
- `ios/`: the iOS app (see `ios/README.md`). App Store Connect app 6832620323, bundle id `net.estopia.hncrevision`.
- `android/`: the Android app (see `android/README.md`). Play package `net.estopia.hncrevision`.
- `shared/data/`: the built-in copy of the site's data. Refresh it with `shared/update-data.sh` before a release.
- `shared/icon/`, `shared/store/`: app icon, Play listing text, feature graphic and screenshots.

Testing links: TestFlight https://testflight.apple.com/join/uZEwTNmR · Google Play https://play.google.com/apps/testing/net.estopia.hncrevision
Privacy policy: https://estopia.net/hnc-revision/privacy
