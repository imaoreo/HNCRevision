import Foundation
import Testing
@testable import RevisionCore

/// `ios/../shared/data`, found relative to this file so the tests use the same files the app bundles.
let sharedData: URL = URL(fileURLWithPath: #filePath)
    .deletingLastPathComponent() // RevisionCoreTests
    .deletingLastPathComponent() // Tests
    .deletingLastPathComponent() // RevisionCore
    .deletingLastPathComponent() // ios
    .deletingLastPathComponent() // HNCRevision
    .appendingPathComponent("shared/data", isDirectory: true)

func bundledFiles() throws -> RawDataFiles {
    func read(_ name: String) throws -> String {
        try String(contentsOf: sharedData.appendingPathComponent("\(name).js"), encoding: .utf8)
    }
    return RawDataFiles(cards: try read("cards"), quiz: try read("quiz"), vlsm: try read("vlsm"))
}

@Suite("Bundled data")
struct BundledDataTests {
    @Test func parsesAllThreeFiles() throws {
        let data = try bundledFiles().parse()
        #expect(data.units.count == 7)
        #expect(data.cards.count == 182)
        #expect(data.questions.count == 84)
        #expect(data.coverage.count == 7)
        #expect(data.vlsmExercises.count == 6)
        #expect(data.vlsmHints.count == 6)
        #expect(data.vlsmHints.allSatisfy { $0.count == 3 })
        #expect(data.isUsable)
    }

    @Test func fieldsDecode() throws {
        let data = try bundledFiles().parse()
        #expect(data.units.first == StudyUnit(code: "J0HA34", title: "Computer Programming"))
        let first = try #require(data.cardsById[1])
        #expect(first.q == "What is a data security breach?")
        #expect(first.unit == "J0H934")
        #expect(first.topic == "Breaches")
        #expect(first.outcomes == ["1"])
        #expect(first.references.first?.label == "ICO · Personal data breaches and security")
        let q1 = try #require(data.questionsById["q1"])
        #expect(q1.cardId == 1)
        #expect(q1.options.count == 4)
        #expect(q1.answer == 0)
        let coverage = try #require(data.coverage["J0HA34"])
        #expect(coverage.pages == "4–6")
        #expect(coverage.version == "02")
        #expect(coverage.outcomes.count == 4)
        #expect(coverage.source == "https://www.sqa.org.uk/files/hn/J0HA34.pdf")
        #expect(data.cards.allSatisfy { data.coverage[$0.unit] != nil })
    }

    @Test func vlsmExercisesAndSingleQuotedHints() throws {
        let data = try bundledFiles().parse()
        let warmUp = data.vlsmExercises[0]
        #expect(warmUp.title == "Warm-up")
        #expect(warmUp.parent == "192.168.25.0/24")
        #expect(warmUp.requests.first == VLSMRequest(lan: "LAN A", hosts: 60))
        #expect(warmUp.rows[0].cells == ["LAN A", "60", "192.168.25.0/26", "255.255.255.192", "192.168.25.1",
                                         "192.168.25.62", "192.168.25.63", "62"])
        #expect(warmUp.rows[0].explanation.hasPrefix("LAN A needs 60 hosts. Allow two reserved addresses, then round 62 up to 64 addresses."))
        #expect(data.vlsmHints[0][0].hasPrefix("Start with LAN A."))
        // A curly apostrophe inside a single-quoted string.
        #expect(data.vlsmHints[5][2].contains("LAN B’s 64-address block"))
    }
}

@Suite("JavaScript literal parser")
struct ParserTests {
    @Test func singleQuotedAndEscapedStrings() throws {
        let value = try JSLiteralParser.parse(#"['it\'s', "say \"hi\"", 'tab\there', 'line\nbreak', 'back\\slash', 'a\/b', 'é’', "😀", 'x\x41']"#)
        #expect(value == .array([
            .string("it's"), .string("say \"hi\""), .string("tab\there"), .string("line\nbreak"),
            .string("back\\slash"), .string("a/b"), .string("é’"), .string("😀"), .string("xA"),
        ]))
    }

    @Test func trailingCommasUnquotedKeysAndComments() throws {
        let source = """
        // leading comment
        const THING = {
          name: 'Example', // trailing comment
          /* block
             comment */
          "quoted": [1, 2, 3,],
          $dollar_1: { nested: true, },
          neg: -2.5e1,
          nothing: null,
          off: false,
        };
        const OTHER = [1, 2];
        document.getElementById('x').addEventListener('click', () => {});
        """
        let value = try JSLiteralParser.value(ofConst: "THING", in: source)
        #expect(value["name"] == .string("Example"))
        #expect(value["quoted"] == .array([.number(1), .number(2), .number(3)]))
        #expect(value["$dollar_1"] == .object(["nested": .bool(true)]))
        #expect(value["neg"] == .number(-25))
        #expect(value["nothing"] == .null)
        #expect(value["off"] == .bool(false))
        #expect(try JSLiteralParser.value(ofConst: "OTHER", in: source) == .array([.number(1), .number(2)]))
    }

    @Test func declarationNeedsExactName() throws {
        let source = "const CARDS_OLD = [1]; const CARDS=[2];"
        #expect(try JSLiteralParser.value(ofConst: "CARDS", in: source) == .array([.number(2)]))
        #expect(throws: JSParseError.self) { try JSLiteralParser.value(ofConst: "MISSING", in: source) }
    }

    @Test func rejectsMalformedInput() {
        #expect(throws: JSParseError.self) { try JSLiteralParser.parse("[1, 2") }
        #expect(throws: JSParseError.self) { try JSLiteralParser.parse("{a: 1 b: 2}") }
        #expect(throws: JSParseError.self) { try JSLiteralParser.parse("'unterminated") }
        #expect(throws: JSParseError.self) { try JSLiteralParser.parse("[foo]") }
    }

    @Test func skipsInvalidCardsAndQuestionsAndToleratesExtras() throws {
        let cards = """
        const UNITS = [{code: 'U1', title: 'Unit one'}];
        const CARDS = [
          {id: 1, q: 'Q1', a: 'A1', topic: 'T', unit: 'U1', outcomes: ['1'], extra: {whatever: [1]}},
          {id: 2, q: 'missing answer', topic: 'T', unit: 'U1'},
          {id: 3, q: 'Q3', a: 'A3', topic: 'T', unit: 'U1'},
        ];
        const COVERAGE = {U1: {version: '01', outcomes: [{id: '1', title: 'First'}]}};
        """
        let quiz = """
        const QUESTIONS = [
          {id: 'q1', cardId: 1, unit: 'U1', topic: 'T', q: 'Pick', options: ['a','b','c','d'], answer: 2, outcomes: ['1']},
          {id: 'q2', unit: 'U1', topic: 'T', q: 'Bad answer index', options: ['a','b'], answer: 5},
        ];
        """
        let vlsm = "const vlsmExercises = []; const exerciseHints = [];"
        let data = try RevisionData.parse(cards: cards, quiz: quiz, vlsm: vlsm)
        #expect(data.cards.map(\.id) == [1, 3])
        #expect(data.cards[1].outcomes.isEmpty)
        #expect(data.cards[1].hint == "")
        #expect(data.questions.map(\.id) == ["q1"])
        #expect(data.coverage["U1"]?.outcomes.first?.title == "First")
        #expect(data.coverage["U1"]?.gaps == "")
    }

    @Test func missingDeclarationFailsTheLoad() {
        #expect(throws: RevisionDataError.self) {
            try RevisionData.parse(cards: "const UNITS = [];", quiz: "const QUESTIONS = [];", vlsm: "")
        }
    }
}

@Suite("Script discovery")
struct ScriptLocatorTests {
    let base = URL(string: "https://example.site/")!

    @Test func findsVersionedScripts() {
        let html = #"<script src="cards.js?v=5"></script><script src="quiz.js?v=2"></script><script src="vlsm.js?v=2"></script><script src="app.js?v=10"></script>"#
        let urls = ScriptLocator.scriptURLs(html: html, base: base)
        #expect(urls["cards"]?.absoluteString == "https://example.site/cards.js?v=5")
        #expect(urls["quiz"]?.absoluteString == "https://example.site/quiz.js?v=2")
        #expect(urls["vlsm"]?.absoluteString == "https://example.site/vlsm.js?v=2")
    }

    @Test func fallsBackToBareFilenames() {
        let urls = ScriptLocator.scriptURLs(html: #"<script src="quiz.js"></script>"#, base: base)
        #expect(urls["cards"]?.absoluteString == "https://example.site/cards.js")
        #expect(urls["quiz"]?.absoluteString == "https://example.site/quiz.js")
        #expect(urls["vlsm"]?.absoluteString == "https://example.site/vlsm.js")
        #expect(ScriptLocator.scriptURLs(html: "", base: base).count == 3)
    }

    @Test func worksOnTheSavedSitePage() {
        let html = #"</main><script src="cards.js?v=5"></script><script src="quiz.js?v=2"></script><script src="vlsm.js?v=2"></script><script src="editor.js"></script>"#
        let urls = ScriptLocator.scriptURLs(html: html, base: DataRepository.siteURL)
        #expect(urls["cards"]?.absoluteString == "https://data-security-revision-cards.exquisitemeadow.chatgpt.site/cards.js?v=5")
    }
}
