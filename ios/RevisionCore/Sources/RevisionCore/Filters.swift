import Foundation

/// The unit / outcome / topic selection shared by Study, Rated cards, Browse and Quiz.
public struct Filter: Hashable, Codable, Sendable {
    public static let allUnits = "all"
    public static let allOutcomes = "all"
    public static let allTopics = "All topics"
    public static let defaultUnit = "J0H934"
    /// The unit whose outcomes are examination sections rather than learning outcomes.
    public static let gradedUnit = "J0J734"
    public static let allUnitsSpecURL = "https://www.sqa.org.uk/files_ccc/GP1015.pdf"

    public var unit: String
    public var outcome: String
    public var topic: String

    public init(unit: String = Filter.defaultUnit, outcome: String = Filter.allOutcomes, topic: String = Filter.allTopics) {
        self.unit = unit
        self.outcome = outcome
        self.topic = topic
    }

    func matchesUnit(_ code: String) -> Bool { unit == Filter.allUnits || code == unit }
    func matchesOutcome(_ outcomes: [String]) -> Bool { outcome == Filter.allOutcomes || outcomes.contains(outcome) }
    func matchesTopic(_ t: String) -> Bool { topic == Filter.allTopics || t == topic }
}

public struct TopicCount: Hashable, Sendable, Identifiable {
    public let topic: String
    public let count: Int
    public var id: String { topic }
}

public struct OutcomeOption: Hashable, Sendable, Identifiable {
    public let id: String
    public let label: String
}

extension RevisionData {
    public func unitCards(_ f: Filter) -> [Card] { cards.filter { f.matchesUnit($0.unit) } }

    public func baseCards(_ f: Filter) -> [Card] { unitCards(f).filter { f.matchesOutcome($0.outcomes) } }

    public func selectedCards(_ f: Filter) -> [Card] { baseCards(f).filter { f.matchesTopic($0.topic) } }

    public func selectedQuestions(_ f: Filter) -> [Question] {
        questions.filter { f.matchesUnit($0.unit) && f.matchesOutcome($0.outcomes) && f.matchesTopic($0.topic) }
    }

    /// "All topics" plus the distinct topics of the base cards in data order, each with its count.
    public func topics(_ f: Filter) -> [TopicCount] {
        let base = baseCards(f)
        var order: [String] = []
        var counts: [String: Int] = [:]
        for card in base {
            if counts[card.topic] == nil { order.append(card.topic) }
            counts[card.topic, default: 0] += 1
        }
        return [TopicCount(topic: Filter.allTopics, count: base.count)]
            + order.map { TopicCount(topic: $0, count: counts[$0] ?? 0) }
    }

    /// The outcome picker's entries for a unit, starting with "All outcomes" / "All sections".
    public func outcomeOptions(unit: String) -> [OutcomeOption] {
        let graded = unit == Filter.gradedUnit
        var options = [OutcomeOption(id: Filter.allOutcomes, label: graded ? "All sections" : "All outcomes")]
        for o in coverage[unit]?.outcomes ?? [] {
            options.append(OutcomeOption(id: o.id, label: graded ? o.title : "Outcome \(o.id) · \(o.title)"))
        }
        return options
    }

    public func unitInfo(unit code: String) -> String {
        guard let u = unit(code) else { return "Study all seven units together." }
        return u.title + (code == "FR2435" ? " · SCQF level 8" : " · SCQF level 7")
    }

    public func specURL(unit code: String) -> String {
        if code == Filter.allUnits { return Filter.allUnitsSpecURL }
        return coverage[code]?.source ?? Filter.allUnitsSpecURL
    }

    /// The answer's references: the card's own, then legislation, then the unit specification, deduplicated by URL.
    public func references(for card: Card) -> [Reference] {
        Self.mergeReferences(card.references, legalSource: card.legalSource, spec: coverage[card.unit]?.source)
    }

    /// A question's references come from its linked flashcard, plus its own legislation link and the unit specification.
    public func references(for question: Question) -> [Reference] {
        let base = question.cardId.flatMap { cardsById[$0] }?.references ?? []
        return Self.mergeReferences(base, legalSource: question.legalSource, spec: coverage[question.unit]?.source)
    }

    static func mergeReferences(_ refs: [Reference], legalSource: String?, spec: String?) -> [Reference] {
        var seen = Set<String>()
        var out: [Reference] = []
        for r in refs where !seen.contains(r.url) {
            seen.insert(r.url)
            out.append(r)
        }
        if let legal = legalSource, !legal.isEmpty, !seen.contains(legal) {
            seen.insert(legal)
            out.append(Reference(label: "Legislation or regulator guidance", url: legal))
        }
        if let spec, !spec.isEmpty, !seen.contains(spec) {
            out.append(Reference(label: "SQA · Unit specification and outcomes", url: spec))
        }
        return out
    }

    /// Cards and MCQs related to one outcome, counted across the whole unit (for the Outcomes screen).
    public func coverageCounts(unit: String, outcome: String) -> (cards: Int, questions: Int) {
        (cards.filter { $0.unit == unit && $0.outcomes.contains(outcome) }.count,
         questions.filter { $0.unit == unit && $0.outcomes.contains(outcome) }.count)
    }
}

/// "Outcomes 1, 2", or "Sections 1, 2" for the graded unit.
public func outcomeLabel(unit: String, outcomes: [String]) -> String {
    (unit == Filter.gradedUnit ? "Sections " : "Outcomes ") + outcomes.joined(separator: ", ")
}

/// A deck for the next round: the items not yet seen, in data order. When every item has been
/// seen, those items are cleared from `seen` and returned shuffled.
public func nextRound<T: Identifiable>(_ items: [T], seen: inout Set<T.ID>,
                                       using rng: inout some RandomNumberGenerator) -> [T] {
    let unseen = items.filter { !seen.contains($0.id) }
    if !unseen.isEmpty || items.isEmpty { return unseen }
    for item in items { seen.remove(item.id) }
    return items.shuffled(using: &rng)
}

public func nextRound<T: Identifiable>(_ items: [T], seen: inout Set<T.ID>) -> [T] {
    var rng = SystemRandomNumberGenerator()
    return nextRound(items, seen: &seen, using: &rng)
}
