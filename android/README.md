# HNC Revision — Android

Kotlin + Jetpack Compose + Material 3 version of the HNC Revision app (see `../SPEC.md`).
Application id `net.estopia.hncrevision`, minSdk 26, targetSdk 36, compileSdk 37.

## Build and test

```sh
./gradlew test                 # JVM unit tests (parser, loading/caching, filters, rounds, quiz, persistence)
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
- `.../logic/` — `RevisionEngine` (filters, flashcard decks and rounds, ratings, quiz, saved sessions,
  immutable snapshots for the UI), `Rounds.kt` (`nextRound`), `Persistence.kt` (`ProgressStore` over a
  key/value store; SharedPreferences in the app).
- `.../ui/` — `AppViewModel` (data refresh, settings, text-to-speech), `App.kt` (tabs: Study, Quiz, Browse, More),
  one file per screen, `Common.kt` (shared filter bar, cards, buttons, links), `Theme.kt` (site colours, dark mode,
  dyslexia reading mode), `Icons.kt`.
- Bundled data: `../shared/data/{cards,quiz,vlsm}.js` are added as an assets source directory (not copied).
  Refresh them with `../shared/update-data.sh`.
- Launcher icon: `res/drawable/ic_launcher_foreground.xml` / `ic_launcher_monochrome.xml`
  (from `../shared/icon/foreground.svg`) on `#173d37`.
- Tests: `app/src/test/java/net/estopia/hncrevision/` (read the real data files from `../shared/data`).
