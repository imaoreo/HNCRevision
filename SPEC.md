# HNC Revision — native apps spec

Native iOS (SwiftUI) and Android (Kotlin, Jetpack Compose) versions of the HNC Cyber Security revision site at
https://data-security-revision-cards.exquisitemeadow.chatgpt.site/ (made by a classmate/lecturer, who agreed to the apps).
The apps are for one college class, distributed through TestFlight and Google Play testing.

- App name: **HNC Revision**. Bundle id / application id: `net.estopia.hncrevision`. Version 1.0.0, build 1.
- iOS: SwiftUI, iOS 18.0+, iPhone and iPad, Swift 6, XcodeGen `project.yml` (team `LQCQTAV3UW`, automatic signing), `ITSAppUsesNonExemptEncryption = NO`.
- Android: Kotlin + Compose + Material 3, minSdk 26, compileSdk 37, targetSdk 36, AGP 9.4.1 (built-in Kotlin, so only the `org.jetbrains.kotlin.plugin.compose` 2.4.21 plugin), Gradle 9.8.1 wrapper, JDK 17 target. No third-party networking or JSON libraries: `HttpURLConnection` + `org.json` are fine. No Estopia SDK.
- No accounts, analytics, ads or tracking. Only network access is the site itself (and opening reference links in the browser).

## Data: live from the site, with an offline fallback

The site keeps its data in three scripts, each a JavaScript file with `const NAME = <literal>;` declarations:

| File | Declarations |
|---|---|
| `cards.js` | `UNITS` (7 units: `code`, `title`), `CARDS` (182: `id` int, `q`, `a`, `topic`, `unit`, `outcomes` [string], `hint`, `references` [{`label`,`url`}], optional `legalSource` url, optional `source`), `COVERAGE` (object keyed by unit code: `version`, `pages`, `gaps`, `outcomes` [{`id`,`title`,`practice`,`note`}], `source` url, `checked`) |
| `quiz.js` | `QUESTIONS` (84: `id` string, `cardId` int, `unit`, `topic`, `q`, `options` [4 strings], `answer` index, `explanation`, `outcomes` [string], optional `legalSource`) |
| `vlsm.js` | `vlsmExercises` (6: `title`, `parent` CIDR, `requests` [[lan, hosts]], `rows` [[lan, hosts, network/prefix, mask, firstUsable, lastUsable, broadcast, usableCapacity]]), `exerciseHints` (6 × 3 strings, **single-quoted JS strings**, not JSON), then UI code to ignore |

Bundled copies live in `shared/data/` (refresh with `shared/update-data.sh`). Both apps ship those three files as resources.

**Parsing.** Write a small lenient JavaScript-literal parser (both apps, same behaviour): find `const NAME` followed by optional whitespace and `=`, then parse one literal starting there: objects, arrays, strings in `"` or `'` with JS escapes (`\n \t \" \' \\ \/ \uXXXX`), numbers, `true`/`false`/`null`, unquoted identifier keys, trailing commas, `//` and `/* */` comments. Stop after the literal; ignore the rest of the file. Field-level decoding should tolerate missing optional fields and unknown extra fields; a card or question missing a required field is skipped rather than failing the whole load.

**Loading.**
1. On launch, load immediately from the last successful live copy saved in the app's caches/files directory if present, else the bundled copy. The UI never waits on the network.
2. Then refresh in the background: GET the site root, find the current script URLs with a regex on `src="(cards|quiz|vlsm)\.js[^"]*"` (they carry `?v=N` cache-busters), falling back to the bare filenames. Download all three (timeout ~15 s, no caching), parse them, and only if all parse and contain at least one unit, card and question, swap them in and save the raw files as the new cached copy. A failed refresh keeps the current data silently.
3. Refresh again when the app returns to the foreground if the last check was over 30 minutes ago, and on pull-to-refresh / a "Check for updates" button in Settings.
4. Settings shows where data came from: "Live from the website · updated <relative time>", or "Saved copy from <date> (offline)", or "Built-in copy (offline)", plus counts (units, flashcards, quiz questions).

## Features (match the site)

State held in the app: `unit` (default `J0H934` Data Security; `all` = all units), `outcome` (`all` or an outcome id), `topic` (`All topics` or a topic), `mode`.

Filters:
- `unitCards` = cards in the unit (all if `all`). `baseCards` = unitCards matching outcome (`outcome == all` or `card.outcomes` contains it). `selected` = baseCards matching topic.
- `selectedQuestions` = questions matching unit, outcome and topic the same way.
- Topics list = `All topics` + distinct topics of baseCards in data order, each with its count.
- Outcome picker lists `COVERAGE[unit].outcomes`, labelled "Outcome <id> · <title>" (for `J0J734`, just the title and "All sections"). Disabled when unit is `all`.
- Unit info line: title + " · SCQF level 8" for `FR2435`, else " · SCQF level 7"; "Study all seven units together." for all. Link "Unit specification" to `COVERAGE[unit].source` (all units: `https://www.sqa.org.uk/files_ccc/GP1015.pdf`).
- Changing unit resets outcome and topic; changing outcome resets topic.

Put the unit / outcome / topic filters in a compact filter bar or sheet shared by the Study, Rated, Browse and Quiz screens. Show the stat line: flashcards in unit, confident count, to-revisit count.

Navigation: a tab bar (iOS `TabView`, Android `NavigationBar`) with **Study**, **Quiz**, **Browse**, **More**. More holds Rated cards, Outcomes, VLSM, and Settings. (Rated cards can alternatively be a segmented toggle on Study; your call, keep it obvious.)

### Study (flashcards)
- Card shows: tag "<unit> · <topic>", status ("Confident" / "Revisit" / "Not rated") · "#<id>", "Outcomes 1, 2" (prefix "Sections" for unit `J0J734`), the question.
- Before reveal: **Show hint** toggles the hint text; **Reveal answer**. Tapping the card also reveals.
- After reveal: the answer, then **References**: the card's references, plus `legalSource` labelled "Legislation or regulator guidance" if not already listed, plus the unit spec `COVERAGE[unit].source` labelled "SQA · Unit specification and outcomes" if not already listed (dedupe by URL). Links open in the browser (SFSafariViewController / Custom Tabs are fine). Buttons **Revisit** and **Confident ✓** rate the card and move to the next.
- Previous / next buttons and swipe left/right. Position "Card n of N", progress bar = confident / selected, "x of y confident".
- **Rounds**: a set `cardSeen` of card ids. A deck is built by `nextRound(items, seen)`: the items not yet seen (in data order); if all are seen, clear them from `seen` and return them shuffled. A card is marked seen when revealed or when moving forward past it. Moving forward from the last card starts the next round ("Next round →" label on the last card).
- **Shuffle**: deck = shuffled unseen eligible cards (if none, start a new round). **Restart**: remove eligible cards from `cardSeen`, rebuild.
- A collapsible tracker: "Flashcards · x seen · y unseen this round", listing unseen and seen questions.
- Empty state: "No cards in this set" / "No cards match this rating" with a button back to Study.

### Rated cards
Same card UI but the deck is only rated cards, filtered by a picker: Confident and Revisit (default) / Confident only / Revisit only. Rating a card into a value the filter excludes removes it from the deck.

### Browse
Search field (matches question + answer + topic + unit, case-insensitive) over `selected`. Expandable rows: question; expanded shows answer, references, a "Show hint" disclosure, and a footer "<unit> · <topic> · Outcomes … · Card <id>". When read-aloud is on, buttons to read question / hint / answer.

### Quiz (multiple choice)
- Deck = `nextRound(selectedQuestions, quizSeen)`, each question with its 4 options shuffled.
- Header: "Question n of N · c/a correct so far" and "Saved latest answers: x/y correct" (y = selected questions with a saved result).
- Card: tag, "Outcomes … · Flashcard <cardId>", question, options labelled A–D. Pick one, then **Check answer** (disabled until picked). After checking, options lock, correct one green, wrong pick red, feedback "Correct" / "Review this one", "Correct answer: …", explanation, references (references come from the linked card, `cardId`, plus the question's `legalSource` and unit spec), and the note "Related flashcard: <id>. This answer supports revision; it does not demonstrate practical competence."
- Checking saves `quizProgress[id] = {correct, choice, answeredAt}` and adds the id to `quizSeen`. First checked answer counts.
- Nav: **← Previous**, **Finish & review**, **Next question →** (enabled once checked; "See results" on the last).
- Results: "c correct out of a answered", "w to revise · u unanswered · N questions in this set", "This is a revision score, not an official unit grade. Flashcard confidence ratings are separate." Buttons **Retry incorrect (w)** (disabled when 0; deck = shuffled wrong ones), **Next round**, **Continue unanswered** (if any). Expandable list of answered questions with ✓ / ↺, your answer, correct answer, explanation, references.
- Empty: "No MCQs for this filter" — "Choose another outcome or topic. Graded Unit sections 2 and 3 require written responses; use the Outcomes tab for practice tasks."
- Tracker like Study: "Multiple choice · x seen · y unseen this round".

### Outcomes (coverage)
Intro: eyebrow "Specification check · <checked date of first unit>", heading "How your revision matches the units", text: "Mapped against the published outcome statements and knowledge/skills lists. Counts show related revision material, not completion or full syllabus coverage. Practical tasks, written work and lecturer materials remain essential." and "J0J734 is an integrated exam: its rows show examination sections rather than invented learning outcomes."
For the current unit (or every unit when `all`): "<code> · <title>", the `gaps` text, "Published specification ↗ · Version v · Relevant pages p · Checked d", then per outcome: "Outcome <id> · <title>" (no prefix for J0J734), "<n> related flashcards · <m> MCQs" (counted across the whole unit), the `note`, "Apply it: <practice>", and a button **Study this outcome** (or "section") which sets that unit + outcome, topic All, and switches to Study.
Footer: "These are original study prompts, not official assessment questions or assessor-approved coverage."

### VLSM practice
Picker of the 6 exercises ("1. Warm-up", …). Intro: "Give each LAN the smallest subnet that fits. Allocate largest first, starting at the base network address, and break ties in alphabetical order. Use conventional IPv4 subnets with network and broadcast addresses reserved."
Prompt "Allocate subnets within <parent>.", list "<lan> needs <hosts> usable addresses", "Find each LAN's network address and prefix, subnet mask, usable host range and broadcast address."
A multi-line "Your working" text editor, saved per exercise. **Show hint (1 of 3)** reveals hints one at a time ("All 3 hints shown" when done). **Reveal answer** / **Hide answer** shows a worked solution: for each row "<lan> needs <hosts> hosts. Allow two reserved addresses, then round <hosts+2> up to <capacity+2> addresses. Allocate <network>. The next block starts immediately after <broadcast>." and a horizontally scrollable table: LAN, Hosts needed, Network / prefix, Subnet mask, First usable, Last usable, Broadcast, Usable capacity.

### Settings
- Read flashcards aloud (default off): uses system TTS (AVSpeechSynthesizer / Android TextToSpeech), language en-GB. When on, Study reads the question on show, the answer on reveal, and the hint when shown; Study shows **Read aloud** and **Stop reading** buttons. Stop on leaving the screen.
- Dyslexia reading mode (default off): a plain sans font, extra letter spacing (~0.05em) and line spacing (~1.6), softer cream background. Applies app-wide.
- Data source status and **Check for updates** (see Loading).
- **Reset saved progress**: confirm "Clear all flashcard ratings and saved quiz answers? This resets N flashcards and M quiz questions on this device." then clear ratings, quiz results, seen sets and saved sessions.
- About: "Original revision prompts; use alongside lecturer materials. Content from the HNC Revision Studio website." with a link to the site.

## Persistence (on device only)
UserDefaults / SharedPreferences (or a small JSON file):
- `progress`: card id → `known` | `review`.
- `quizProgress`: question id → {correct, choice, answeredAt}.
- `cardSeen`, `quizSeen`, rating filter, last view (unit, outcome, topic, tab), and the current study/rated/quiz deck (ids, index, revealed / option orders, responses, done) keyed by a scope string of unit+outcome+topic+mode so reopening the app resumes where you were. When resuming, drop the saved deck if any id no longer exists in the data.
- VLSM working text per exercise; the two accessibility settings.

## Look
Match the site: ink `#173d37` (primary, text), paper `#f5f5ef` (background), line `#dce2d9`, muted `#6d7c75`, accent `#d5e9a7` (soft buttons, Confident), white cards with 20 pt corner radius and a hairline border. Primary buttons are ink with white text. Correct = green tint, wrong = red tint. Support dark mode with matching dark tokens (background ~`#0f1f1c`, cards ~`#173d37`-ish surface, text ~`#eef2ea`, accent kept). Respect Dynamic Type / font scaling, VoiceOver / TalkBack labels, minimum 44 pt / 48 dp touch targets. Phone and tablet layouts should both work.
Icon: `shared/icon/icon-1024.png` (iOS) and `shared/icon/foreground.svg` on `#173d37` (Android adaptive icon; convert to a vector drawable).

---

# Version 1.1 (1.1.0, build 2 / versionCode 2)

Seven additions. Everything new is saved on the device with the existing lenient pattern (a field that fails to load falls back to its default), so 1.0 users keep their ratings, quiz results and decks. All new logic lives in the testable layer (iOS `RevisionCore`, Android `logic/`), and dates use the device's local calendar day. Inject "now" and the random source so tests are deterministic.

## 1. Spaced repetition (Leitner boxes)
- Saved per card: `schedule[cardId] = {box: Int, due: local date (yyyy-MM-dd)}`.
- When a card is rated (Study, Rated, Due, Starred, New modes): **Confident** → `box = min(box + 1, 4)` (unscheduled cards start from box 0, so the first Confident gives box 1); **Revisit** → `box = 0`. Then `due = today + interval(box)` with intervals box 0 = 1 day, 1 = 3, 2 = 7, 3 = 14, 4 = 30.
- The existing `progress` rating (known / review) is still written exactly as before.
- Migration (first launch with no `schedule` saved): every 1.0 Revisit card → box 0, due today; every Confident card → box 1, due today + 3.
- **Due** card mode, next to Study / Rated cards (segmented control: Study · Due · Rated · Starred): deck = cards in the current filter (`selected`) with `due <= today`, sorted by `due` ascending then data order. No rounds; rating a card removes it from the Due deck (it's no longer due). Empty state: "Nothing due. Nice work." with "Next due: <date>" if any, and a button to Study.
- Stat line gains "N due" (unit-wide, like the other stats). Study tab badge shows the due count across all units when > 0.
- Reset clears schedules with the ratings.

## 2. Mock exam (Quiz tab: segmented "Practice" | "Mock exam")
- Setup screen: units multi-select (default: the current filter unit, or all units when `all`), question count 10 / 20 / 40 (capped at what's available, showing "(n available)"), timer toggle (90 s per question), **Start**. A preset button **"Graded Unit Section 1 style"**: 5 random questions each from J0H934, J0HL34, J0HJ34 and J0J734 (20 total, 30-minute timer). Caption: "Practice in the style of the Graded Unit exam's selected-response section. Not an official paper."
- Selection: random sample across the chosen units (for the preset, per unit), options shuffled like Practice.
- During: question n of N, remaining time (if timed), A–D options, Previous / Next, a question-number grid to jump around, **Submit**. Answers can be changed; no feedback until submitted. At 0:00 it submits automatically. Submitting with unanswered questions asks to confirm.
- Results: "x / N (p%)", time taken, a per-unit table (unit code · title, correct / asked, %), then the review list (reuse the Practice results rows: ✓ / ↺, your answer or "Not answered", correct answer, explanation, references).
- History: the last 20 attempts saved as `{date, units, total, correct, durationSeconds, perUnit}`; shown under the setup screen with the date, score and % ("12 Nov · 15/20 · 75%").
- An attempt in progress is saved and resumed (remaining time keeps counting from the saved deadline).
- Mock answers do not change Practice `quizProgress` or `quizSeen`, but do count as activity for streaks.

## 3. VLSM generator and drills (VLSM screen: segmented "Exercises" | "Generate" | "Drills")
- **Exercises**: the 1.0 screen, unchanged.
- **Solver** (shared by Generate and tests): sort LANs by hosts descending, ties by name ascending. For each: block = smallest power of two ≥ hosts + 2 (minimum 4), prefix = 32 − log2(block), network = next free address, mask, first = network + 1, last = broadcast − 1, broadcast = network + block − 1, usable capacity = block − 2. The next block starts at broadcast + 1. Must reproduce the `rows` of all 6 site exercises exactly.
- **Generate**: difficulty Easy (parent /24, 3 LANs), Medium (/24, 4–5 LANs), Hard (/23 or /22, 5–6 LANs). Parent network: a random private block (192.168.x.0, 172.16–31.x.0 or 10.x.y.0) aligned to its prefix. LAN names "LAN A", "LAN B"… in random order of size. Host counts random in 2…(parent size / 2 − 2), redrawn until the solved allocation fits inside the parent. **New question** button.
- Same layout as Exercises: prompt, request list, working text (not saved for generated ones), 3 hints, reveal worked solution with the same step text and table.
- Answer boxes per LAN (in solver order): Network / prefix, Subnet mask, First usable, Last usable, Broadcast. **Check answers** marks each field green or red (trim spaces; prefix written as "a.b.c.d/n"). "x of y correct".
- **Drills**: rounds of 10 random questions from four kinds: (a) "/n as a subnet mask?" ↔ "255.255.255.x as a prefix?", (b) "Usable hosts in a /n?", (c) "Network address of a.b.c.d/n?", (d) "Broadcast address of a.b.c.d/n?" (prefixes 16–30). Typed answer, **Check**, then shows correct/incorrect with the answer and a one-line working ("Block size 32, so …"). End-of-round score and **New round**. Best score saved.

## 4. Bookmarks and notes
- `starred: set of card ids`, `notes: cardId → text` (max 2000 chars).
- Star toggle (☆/★, label "Star card" / "Unstar card") on the card in Study/Due/Rated/Starred/New, in Browse rows, and on Practice/mock feedback (stars the linked flashcard).
- **Starred** card mode: starred cards in the current filter, data order, with rounds like Study. Empty: "No starred cards yet. Tap ☆ on any card to save it here."
- "My note" area under the revealed answer (and in Browse): shows the note, or "Add a note" which opens an editor (sheet/dialog) with Save / Delete. Browse search also matches notes.
- Reset saved progress gets a second choice: "Also delete stars and notes" (off by default).

## 5. Reminders, streaks and progress
- **Activity**: the set of local dates on which the user rated a card, checked a Practice answer, or answered/submitted a mock question. Also a count per day (number of such actions).
- **Streak**: consecutive days ending today, or ending yesterday if there's no activity yet today. **Best streak** saved. Study header (or stat line) shows "🔥 n-day streak" when n ≥ 1.
- **Reminder** (Settings → "Daily reminder" toggle + time picker, default 18:00): turning on asks for notification permission (iOS `UNUserNotificationCenter`; Android 13+ `POST_NOTIFICATIONS`). If denied, the toggle turns back off with an explanation and a link to system settings. One notification per day at that time: title "HNC Revision", body "N cards are due. Keep your n-day streak going." / "N cards are due today." / "A few minutes of revision keeps it fresh." (when nothing is due). Reschedule whenever the app saves state so the body stays current (iOS: schedule the next 7 days with their computed due counts; Android: inexact daily `AlarmManager` alarm whose receiver computes the text from saved state, re-armed by a `BOOT_COMPLETED` receiver and on app start). Tapping opens the app on Study → Due.
- **Progress** screen (More): current and best streak, a 14-day bar chart of activity counts (accessible: each bar labelled with day and count), then per unit: "confident x / y cards", "n due", "quiz: c / a correct (p%)" from Practice results, and the latest mock score if any.

## 6. Home screen widget
- Snapshot written by the app whenever state saves or data loads: `{dueCount, streak, cardOfDay: {id, unit, topic, q}, updatedAt}`. Card of the day = a deterministic pick from all cards using the date (e.g. day number mod card count over cards sorted by id), preferring due cards when any are due.
- **Small**: "N due" large, "🔥 n-day streak", app mark. **Medium**: "Card of the day", unit · topic, question (up to 3 lines), and "N due". Tapping opens the app; tapping the medium widget opens that card in Study (deep link `hncrevision://card/<id>`; iOS `widgetURL`/`Link`, Android PendingIntent extra), which shows it as a one-card deck.
- iOS: a WidgetKit extension target `HNCRevisionWidget` (bundle id `net.estopia.hncrevision.widget`) sharing the App Group `group.net.estopia.hncrevision`. Move the app's saved state from `UserDefaults.standard` to `UserDefaults(suiteName: group)` with a one-time copy of the existing key; write the snapshot into the group; call `WidgetCenter.shared.reloadAllTimelines()` after changes. Timeline refreshes at local midnight.
- Android: `AppWidgetProvider` + RemoteViews layouts (small and medium sizes via `appwidget-provider` xml with resize), colours from the app theme, dark-mode aware; updated by the app after saves and by `updatePeriodMillis`/midnight.

## 7. New-content alerts
- Fingerprint: for each card `id → hash(q + a + hint)`; for each question `id → hash(q + options + answer + explanation)` (a stable hash such as FNV-1a 64 written in both apps identically isn't required; each platform only compares with its own saved fingerprints).
- After every successful data load (bundled, cached or live): if no fingerprints are saved, save them silently. Otherwise card ids that are new or whose hash changed are added to `newCardIds` (a saved set); questions changed are counted only. Save the new fingerprints and a `lastChange = {date, added, updated, questionsChanged}` when anything changed.
- Study shows a banner when `newCardIds` (limited to cards that still exist) is non-empty: "N new or updated cards from the website" with **Study them** (a **New** card mode: those cards in data order, ignoring the filter) and a close button (clears the set). Viewing (revealing) a card removes it from the set.
- Settings' data section adds "Last change: 3 added, 2 updated · 12 Nov" when known.

## Wording and look
Reuse the 1.0 components, colours and card styles. All new controls need accessibility labels, Dynamic Type / font scaling and dark mode.
