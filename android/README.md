# HNC Revision — Android

Kotlin + Jetpack Compose + Material 3 version of the HNC Revision app (see `../SPEC.md`).
Application id `net.estopia.hncrevision`, version 1.1.0 (versionCode 2), minSdk 26, targetSdk 36, compileSdk 37.

## Build and test

```sh
./gradlew test                 # JVM unit tests (parser, loading/caching, filters, rounds, quiz, persistence,
                               # 1.1: scheduler, mock exam, VLSM solver/generator/drills, content diff, streaks, widget)
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew bundleRelease        # app/build/outputs/bundle/release/app-release.aab (R8-minified)
./gradlew lint
```

`local.properties` (git-ignored) needs `sdk.dir=/Users/<you>/Library/Android/sdk`.

## Release signing

Release builds are signed only when you point Gradle at a `keystore.properties` kept outside the repo:

```sh
./gradlew bundleRelease -PhncKeystoreProperties=/path/to/keystore.properties
```

with keys `storeFile`, `storePassword`, `keyAlias`, `keyPassword`. Without it the AAB is unsigned.
Never commit keystores or that file (`*.jks` and `keystore.properties` are git-ignored).

## Where things live

- `app/src/main/java/net/estopia/hncrevision/data/` — `JsLiteral` (lenient JS-literal parser + tiny JSON writer),
  `DataParser` (the three scripts → models), `Models`, `DataRepository` (bundled/cached/live loading,
  script-URL discovery, `HttpURLConnection` fetch, cache in `filesDir/live-data`).
- `.../logic/` (plain Kotlin, no Android; "now" comes from an injected `java.time.Clock`, randomness from a
  seeded `kotlin.random.Random`):
  - `RevisionEngine` — filters, flashcard decks (Study, Due, Rated, Starred, New, single Card) and rounds, ratings,
    quiz, mock exams, stars/notes, activity, new-content tracking, saved sessions, immutable snapshots for the UI.
  - `Rounds.kt` (`nextRound`), `Persistence.kt` (`ProgressStore` over a key/value store; SharedPreferences in the app).
  - 1.1: `Scheduler.kt` (Leitner boxes, 1.0 migration, Due ordering), `MockExam.kt` (selection, preset, timing,
    scoring, history), `VlsmGenerator.kt` (`Ipv4`, `VlsmSolver`, `VlsmGenerator`, `VlsmChecker`, `VlsmDrills`),
    `ContentDiff.kt` (FNV-1a 64 fingerprints), `Activity.kt` (activity log, streaks), `Reminder.kt` (notification
    text, next trigger time), `WidgetContent.kt` (widget snapshot, card of the day).
- `.../ui/` — `AppViewModel` (data refresh, settings, reminder, VLSM generator/drill state, widget updates,
  text-to-speech), `App.kt` (tabs: Study, Quiz, Browse, More; Study badge = cards due), one file per screen
  (`StudyScreen`, `QuizScreen` + `MockExamScreen`, `BrowseScreen`, `MoreScreens` (menu, Outcomes, VLSM, Settings),
  `VlsmPractice` (Generate, Drills), `ProgressScreen`), `Common.kt` (shared filter bar, cards, buttons, links, star
  button, notes, segmented control), `Theme.kt` (site colours, dark mode, dyslexia reading mode), `Icons.kt`.
- `.../system/` — `AppStorage` (SharedPreferences store and data repository shared with the receivers),
  `Reminders.kt` (daily reminder: inexact `AlarmManager.setWindow` alarm, `ReminderReceiver` builds the
  notification from saved state and arms the next day, `BootReceiver` re-arms after boot / app update / clock
  change; channel `daily-reminder`), `HomeWidgets.kt` (`HncWidgetProvider` with RemoteViews: small "N due" +
  streak, medium "Card of the day" from ~4 × 2 cells; redrawn by the app after each change, recomputed by a
  just-after-midnight alarm and `updatePeriodMillis`).
- Deep links: `hncrevision://card/<id>` (or the `EXTRA_CARD_ID` extra) opens that card as a one-card deck; the
  reminder notification opens Study → Due (`ACTION_OPEN_DUE`). Try with
  `adb shell am start -a android.intent.action.VIEW -d hncrevision://card/12 net.estopia.hncrevision`.
- Bundled data: `../shared/data/{cards,quiz,vlsm}.js` are added as an assets source directory (not copied).
  Refresh them with `../shared/update-data.sh`.
- Launcher icon: `res/drawable/ic_launcher_foreground.xml` / `ic_launcher_monochrome.xml`
  (from `../shared/icon/foreground.svg`) on `#173d37`.
- Widget/notification resources: `res/layout/widget_{small,medium}.xml`, `res/xml/hnc_widget_info.xml`,
  `res/drawable/{widget_background,widget_pill,ic_app_mark,ic_notification}.xml`; widget colours in
  `values/colors.xml` and `values-night/colors.xml`.
- Tests: `app/src/test/java/net/estopia/hncrevision/` (read the real data files from `../shared/data`).
  `EngineV11Test` covers loading 1.0-shaped saved data and the one-time Leitner migration.

## Saved data (SharedPreferences `hnc-revision`)

1.0 keys are unchanged: `progress`, `quizProgress`, `session`, `vlsmWorking`, `readAloud`, `dyslexiaReading`.
1.1 adds `schedule`, `starred`, `notes`, `activity`, `bestStreak`, `mockHistory`, `mockAttempt`, `vlsmDrillBest`,
`fingerprints`, `newCardIds`, `lastChange`, `reminder`, `widgetSnapshot` (and `quizMode` / `focusCard` inside
`session`). Every value loads leniently: missing or unreadable → its default. `schedule` missing (or unreadable)
triggers the 1.0 → 1.1 migration; it's then always saved (as `{}` after a reset), so the migration runs once.
