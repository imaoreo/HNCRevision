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
