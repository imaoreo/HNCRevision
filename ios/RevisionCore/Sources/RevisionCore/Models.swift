import Foundation

public struct StudyUnit: Identifiable, Hashable, Sendable {
    public let code: String
    public let title: String
    public var id: String { code }

    public init(code: String, title: String) {
        self.code = code
        self.title = title
    }
}

public struct Reference: Hashable, Sendable {
    public let label: String
    public let url: String

    public init(label: String, url: String) {
        self.label = label
        self.url = url
    }
}

public struct Card: Identifiable, Hashable, Sendable {
    public let id: Int
    public let q: String
    public let a: String
    public let topic: String
    public let unit: String
    public let outcomes: [String]
    public let hint: String
    public let references: [Reference]
    public let legalSource: String?
    public let source: String?

    public init(id: Int, q: String, a: String, topic: String, unit: String, outcomes: [String] = [],
                hint: String = "", references: [Reference] = [], legalSource: String? = nil, source: String? = nil) {
        self.id = id
        self.q = q
        self.a = a
        self.topic = topic
        self.unit = unit
        self.outcomes = outcomes
        self.hint = hint
        self.references = references
        self.legalSource = legalSource
        self.source = source
    }
}

public struct Question: Identifiable, Hashable, Sendable {
    public let id: String
    public let cardId: Int?
    public let unit: String
    public let topic: String
    public let q: String
    public let options: [String]
    public let answer: Int
    public let explanation: String
    public let outcomes: [String]
    public let legalSource: String?

    public init(id: String, cardId: Int?, unit: String, topic: String, q: String, options: [String], answer: Int,
                explanation: String = "", outcomes: [String] = [], legalSource: String? = nil) {
        self.id = id
        self.cardId = cardId
        self.unit = unit
        self.topic = topic
        self.q = q
        self.options = options
        self.answer = answer
        self.explanation = explanation
        self.outcomes = outcomes
        self.legalSource = legalSource
    }
}

public struct CoverageOutcome: Identifiable, Hashable, Sendable {
    public let id: String
    public let title: String
    public let practice: String
    public let note: String
}

public struct Coverage: Hashable, Sendable {
    public let version: String
    public let pages: String
    public let gaps: String
    public let outcomes: [CoverageOutcome]
    public let source: String
    public let checked: String
}

public struct VLSMRow: Hashable, Sendable {
    public let lan: String
    public let hosts: Int
    public let network: String
    public let mask: String
    public let firstUsable: String
    public let lastUsable: String
    public let broadcast: String
    public let usableCapacity: Int

    /// The row as table cells, in the site's column order.
    public var cells: [String] {
        [lan, String(hosts), network, mask, firstUsable, lastUsable, broadcast, String(usableCapacity)]
    }

    /// The worked-solution sentence the site shows for this row.
    public var explanation: String {
        "\(lan) needs \(hosts) hosts. Allow two reserved addresses, then round \(hosts + 2) up to \(usableCapacity + 2) addresses. Allocate \(network). The next block starts immediately after \(broadcast)."
    }
}

public struct VLSMRequest: Hashable, Sendable {
    public let lan: String
    public let hosts: Int
}

public struct VLSMExercise: Hashable, Sendable {
    public let title: String
    public let parent: String
    public let requests: [VLSMRequest]
    public let rows: [VLSMRow]

    public static let tableHeadings = [
        "LAN", "Hosts needed", "Network / prefix", "Subnet mask", "First usable", "Last usable", "Broadcast", "Usable capacity",
    ]
}

/// Everything the app shows, parsed from the site's three data scripts.
public struct RevisionData: Sendable {
    public let units: [StudyUnit]
    public let cards: [Card]
    public let coverage: [String: Coverage]
    public let questions: [Question]
    public let vlsmExercises: [VLSMExercise]
    public let vlsmHints: [[String]]

    public let cardsById: [Int: Card]
    public let questionsById: [String: Question]

    public init(units: [StudyUnit], cards: [Card], coverage: [String: Coverage], questions: [Question],
                vlsmExercises: [VLSMExercise], vlsmHints: [[String]]) {
        self.units = units
        self.cards = cards
        self.coverage = coverage
        self.questions = questions
        self.vlsmExercises = vlsmExercises
        self.vlsmHints = vlsmHints
        var byId: [Int: Card] = [:]
        for card in cards where byId[card.id] == nil { byId[card.id] = card }
        self.cardsById = byId
        var qById: [String: Question] = [:]
        for question in questions where qById[question.id] == nil { qById[question.id] = question }
        self.questionsById = qById
    }

    public static let empty = RevisionData(units: [], cards: [], coverage: [:], questions: [], vlsmExercises: [], vlsmHints: [])

    /// True when the data has at least one unit, card and question (the bar a live refresh must clear).
    public var isUsable: Bool { !units.isEmpty && !cards.isEmpty && !questions.isEmpty }

    public func unit(_ code: String) -> StudyUnit? { units.first { $0.code == code } }
}

// MARK: - Decoding from parsed JavaScript

public enum RevisionDataError: Error, Equatable, Sendable {
    case missingDeclaration(String)
    case parse(String)
    case unusable
}

extension RevisionData {
    /// Parses the three scripts. Throws if any declaration is missing or malformed;
    /// individual cards or questions missing a required field are skipped.
    public static func parse(cards cardsJS: String, quiz quizJS: String, vlsm vlsmJS: String) throws -> RevisionData {
        func declaration(_ name: String, _ source: String) throws -> JSValue {
            do {
                return try JSLiteralParser.value(ofConst: name, in: source)
            } catch let error as JSParseError {
                throw RevisionDataError.parse("\(name): \(error)")
            }
        }
        let unitsValue = try declaration("UNITS", cardsJS)
        let cardsValue = try declaration("CARDS", cardsJS)
        let coverageValue = try declaration("COVERAGE", cardsJS)
        let questionsValue = try declaration("QUESTIONS", quizJS)
        let exercisesValue = try declaration("vlsmExercises", vlsmJS)
        let hintsValue = try declaration("exerciseHints", vlsmJS)

        let units = (unitsValue.arrayValue ?? []).compactMap(StudyUnit.init(js:))
        let cards = (cardsValue.arrayValue ?? []).compactMap(Card.init(js:))
        var coverage: [String: Coverage] = [:]
        for (code, value) in coverageValue.objectValue ?? [:] {
            if let c = Coverage(js: value) { coverage[code] = c }
        }
        let questions = (questionsValue.arrayValue ?? []).compactMap(Question.init(js:))
        let exercises = (exercisesValue.arrayValue ?? []).compactMap(VLSMExercise.init(js:))
        let hints = (hintsValue.arrayValue ?? []).map { ($0.arrayValue ?? []).compactMap(\.looseString) }

        return RevisionData(units: units, cards: cards, coverage: coverage, questions: questions,
                            vlsmExercises: exercises, vlsmHints: hints)
    }
}

private func strings(_ value: JSValue?) -> [String] {
    (value?.arrayValue ?? []).compactMap(\.looseString)
}

private func nonEmpty(_ value: JSValue?) -> String? {
    guard let s = value?.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines), !s.isEmpty else { return nil }
    return s
}

extension StudyUnit {
    init?(js: JSValue) {
        guard let code = js["code"]?.looseString, !code.isEmpty else { return nil }
        self.init(code: code, title: js["title"]?.stringValue ?? code)
    }
}

extension Reference {
    init?(js: JSValue) {
        guard let url = nonEmpty(js["url"]) else { return nil }
        self.init(label: js["label"]?.stringValue ?? url, url: url)
    }
}

extension Card {
    init?(js: JSValue) {
        guard let id = js["id"]?.intValue,
              let q = js["q"]?.stringValue,
              let a = js["a"]?.stringValue,
              let topic = js["topic"]?.stringValue,
              let unit = js["unit"]?.looseString
        else { return nil }
        self.init(
            id: id, q: q, a: a, topic: topic, unit: unit,
            outcomes: strings(js["outcomes"]),
            hint: js["hint"]?.stringValue ?? "",
            references: (js["references"]?.arrayValue ?? []).compactMap(Reference.init(js:)),
            legalSource: nonEmpty(js["legalSource"]),
            source: nonEmpty(js["source"])
        )
    }
}

extension Question {
    init?(js: JSValue) {
        guard let id = js["id"]?.looseString,
              let unit = js["unit"]?.looseString,
              let topic = js["topic"]?.stringValue,
              let q = js["q"]?.stringValue,
              let options = js["options"]?.arrayValue?.compactMap(\.looseString), options.count >= 2,
              let answer = js["answer"]?.intValue, options.indices.contains(answer)
        else { return nil }
        self.init(
            id: id, cardId: js["cardId"]?.intValue, unit: unit, topic: topic, q: q,
            options: options, answer: answer,
            explanation: js["explanation"]?.stringValue ?? "",
            outcomes: strings(js["outcomes"]),
            legalSource: nonEmpty(js["legalSource"])
        )
    }
}

extension CoverageOutcome {
    init?(js: JSValue) {
        guard let id = js["id"]?.looseString else { return nil }
        self.init(id: id, title: js["title"]?.stringValue ?? "", practice: js["practice"]?.stringValue ?? "",
                  note: js["note"]?.stringValue ?? "")
    }
}

extension Coverage {
    init?(js: JSValue) {
        guard js.objectValue != nil else { return nil }
        self.init(
            version: js["version"]?.looseString ?? "",
            pages: js["pages"]?.looseString ?? "",
            gaps: js["gaps"]?.stringValue ?? "",
            outcomes: (js["outcomes"]?.arrayValue ?? []).compactMap(CoverageOutcome.init(js:)),
            source: js["source"]?.stringValue ?? "",
            checked: js["checked"]?.looseString ?? ""
        )
    }
}

extension VLSMExercise {
    init?(js: JSValue) {
        guard let title = js["title"]?.stringValue, let parent = js["parent"]?.stringValue else { return nil }
        let requests: [VLSMRequest] = (js["requests"]?.arrayValue ?? []).compactMap { item in
            guard let pair = item.arrayValue, pair.count >= 2,
                  let lan = pair[0].looseString, let hosts = pair[1].intValue else { return nil }
            return VLSMRequest(lan: lan, hosts: hosts)
        }
        let rows: [VLSMRow] = (js["rows"]?.arrayValue ?? []).compactMap { item in
            guard let r = item.arrayValue, r.count >= 8,
                  let lan = r[0].looseString, let hosts = r[1].intValue,
                  let network = r[2].looseString, let mask = r[3].looseString,
                  let first = r[4].looseString, let last = r[5].looseString,
                  let broadcast = r[6].looseString, let capacity = r[7].intValue
            else { return nil }
            return VLSMRow(lan: lan, hosts: hosts, network: network, mask: mask, firstUsable: first,
                           lastUsable: last, broadcast: broadcast, usableCapacity: capacity)
        }
        self.init(title: title, parent: parent, requests: requests, rows: rows)
    }
}
