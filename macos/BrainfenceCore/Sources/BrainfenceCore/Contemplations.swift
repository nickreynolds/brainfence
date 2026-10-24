import Foundation

// Contemplations: short passages delivered as notifications through the day.
// Port of android/…/domain/contemplation/ContemplationSchedule.kt and
// data/contemplation/ContemplationLibrary.kt (via desktop-app's TypeScript).

public enum DeckOrdering: String, Sendable, CaseIterable {
    case sequential, random

    public init(raw: String?) { self = raw == "random" ? .random : .sequential }
}

public enum ContemplationResponseType: String, Sendable, CaseIterable {
    /// Just read. (Not `.none`, which would clash with `Optional.none`.)
    case noResponse = "none"
    case affirm

    public init(raw: String?) { self = raw == "affirm" ? .affirm : .noResponse }
}

/// `contemplation_deliveries.response` once the user has affirmed one.
public let contemplationAffirmed = "affirmed"
public let defaultAffirmLabel = "I remember"

public struct ContemplationDeck: Sendable, Hashable, Identifiable {
    public let id: String
    public var name: String
    public var description: String?
    public var ordering: DeckOrdering
    public var responseType: ContemplationResponseType
    public var responseLabel: String?
    public var weight: Int
    public var isEnabled: Bool
    public var libraryKey: String?
    public var sortOrder: Int
    public var itemCount: Int

    public init(
        id: String, name: String, description: String? = nil, ordering: DeckOrdering = .random,
        responseType: ContemplationResponseType = .noResponse, responseLabel: String? = nil, weight: Int = 1,
        isEnabled: Bool = true, libraryKey: String? = nil, sortOrder: Int = 0, itemCount: Int = 0
    ) {
        self.id = id
        self.name = name
        self.description = description
        self.ordering = ordering
        self.responseType = responseType
        self.responseLabel = responseLabel
        self.weight = weight
        self.isEnabled = isEnabled
        self.libraryKey = libraryKey
        self.sortOrder = sortOrder
        self.itemCount = itemCount
    }

    /// The affirm button's label, or nil when the deck doesn't ask for one.
    public var affirmLabel: String? {
        guard responseType == .affirm else { return nil }
        let label = responseLabel?.trimmingCharacters(in: .whitespaces) ?? ""
        return label.isEmpty ? defaultAffirmLabel : label
    }
}

public struct Contemplation: Sendable, Hashable, Identifiable {
    public let id: String
    public let deckId: String
    public var title: String?
    public var body: String
    public var attribution: String?
    public var sortOrder: Int

    public init(id: String, deckId: String, title: String?, body: String, attribution: String?, sortOrder: Int) {
        self.id = id
        self.deckId = deckId
        self.title = title
        self.body = body
        self.attribution = attribution
        self.sortOrder = sortOrder
    }
}

public struct ContemplationDelivery: Sendable, Hashable, Identifiable {
    public let id: String
    public let deckId: String?
    public let contemplationId: String?
    public let title: String?
    public let body: String
    public let attribution: String?
    public let deliveredAt: String
    public let response: String?
    public let respondedAt: String?

    public init(
        id: String, deckId: String?, contemplationId: String?, title: String?, body: String,
        attribution: String?, deliveredAt: String, response: String?, respondedAt: String?
    ) {
        self.id = id
        self.deckId = deckId
        self.contemplationId = contemplationId
        self.title = title
        self.body = body
        self.attribution = attribution
        self.deliveredAt = deliveredAt
        self.response = response
        self.respondedAt = respondedAt
    }
}

// MARK: - Schedule

public struct ContemplationScheduleConfig: Sendable, Equatable {
    public let perDay: Int
    /// Seconds of day.
    public let windowStart: Int
    /// Seconds of day.
    public let windowEnd: Int

    public init(perDay: Int, windowStart: Int, windowEnd: Int) {
        self.perDay = perDay
        self.windowStart = windowStart
        self.windowEnd = windowEnd
    }
}

/// Fraction of each segment kept clear at either end.
private let slotMargin = 0.2

/// Deterministic PRNG (mulberry32), the same as the Windows client's: same
/// seed, same sequence of values in [0, 1).
public func seededRandom(_ seed: UInt32) -> () -> Double {
    var a = seed
    return {
        a = a &+ 0x6D2B_79F5
        var t = a
        t = (t ^ (t >> 15)) &* (t | 1)
        t ^= t &+ ((t ^ (t >> 7)) &* (t | 61))
        return Double(t ^ (t >> 14)) / 4_294_967_296
    }
}

/// Delivery times (seconds of day) for [date]: the window split into perDay
/// segments with one moment drawn from the middle of each. Deterministic for
/// a date and seed, so the day's times survive restarts without being stored.
public func contemplationSlots(for date: CivilDate, config: ContemplationScheduleConfig, seed: Int) -> [Int] {
    guard config.perDay > 0, config.windowEnd > config.windowStart else { return [] }
    let windowSeconds = config.windowEnd - config.windowStart
    let segment = Double(windowSeconds) / Double(config.perDay)
    let random = seededRandom(UInt32(truncatingIfNeeded: seed &* 31 &+ date.epochDay))
    return (0..<config.perDay).map { i in
        let within = slotMargin + random() * (1 - 2 * slotMargin)
        let offset = Int(((Double(i) + within) * segment).rounded(.down))
        return config.windowStart + min(offset, windowSeconds - 1)
    }
}

/// The first slot strictly after [now] (today's, else tomorrow's), or nil.
public func nextContemplationSlot(
    after now: Date,
    config: ContemplationScheduleConfig,
    seed: Int,
    timeZone: TimeZone = .current
) -> Date? {
    let clock = ZonedClock(timeZone: timeZone)
    for dayOffset in 0...1 {
        let date = clock.date(of: now).plusDays(dayOffset)
        let midnight = clock.startOfDay(date)
        let next = contemplationSlots(for: date, config: config, seed: seed)
            .map { midnight.addingTimeInterval(TimeInterval($0)) }
            .first { $0 > now }
        if let next { return next }
    }
    return nil
}

/// Weighted choice: index into [weights], each share proportional to its value.
public func pickWeightedIndex(_ weights: [Int], random: () -> Double) -> Int {
    precondition(!weights.isEmpty, "No weights to pick from")
    let clamped = weights.map { max(1, $0) }
    var roll = Int((random() * Double(clamped.reduce(0, +))).rounded(.down))
    for (i, w) in clamped.enumerated() {
        if roll < w { return i }
        roll -= w
    }
    return clamped.count - 1
}

/// Index of the next item to deliver from a deck.
public func pickItemIndex(ordering: DeckOrdering, itemIds: [String], lastDeliveredId: String?, random: () -> Double) -> Int {
    precondition(!itemIds.isEmpty, "Deck has no items")
    let lastIndex = lastDeliveredId.flatMap { itemIds.firstIndex(of: $0) } ?? -1
    if ordering == .sequential { return lastIndex < 0 ? 0 : (lastIndex + 1) % itemIds.count }
    if itemIds.count == 1 || lastIndex < 0 { return Int((random() * Double(itemIds.count)).rounded(.down)) }
    // Draw from the others, skipping over the last-delivered slot.
    let i = Int((random() * Double(itemIds.count - 1)).rounded(.down))
    return i >= lastIndex ? i + 1 : i
}

// MARK: - Library

public struct LibraryItem: Sendable {
    public let title: String?
    public let body: String
    public let attribution: String?
}

public struct LibraryDeck: Sendable {
    public let key: String
    public let name: String
    public let description: String
    public let ordering: DeckOrdering
    public let responseType: ContemplationResponseType
    public let responseLabel: String?
    public let weight: Int
    public let items: [LibraryItem]
}

private let remembrancesSource = "Upajjhatthana Sutta (AN 5.57), tr. Thich Nhat Hanh"

/// Starter decks. Adding one copies it into the user's tables tagged with its
/// key; the library is a template, never read at delivery time.
public let contemplationLibrary: [LibraryDeck] = [
    LibraryDeck(
        key: "five_remembrances",
        name: "The Five Remembrances",
        description: "The Buddha's five daily recollections, delivered in turn.",
        ordering: .sequential,
        responseType: .affirm,
        responseLabel: "I remember",
        weight: 4,
        items: [
            LibraryItem(title: "The First Remembrance", body: "I am of the nature to grow old. There is no way to escape growing old.", attribution: remembrancesSource),
            LibraryItem(title: "The Second Remembrance", body: "I am of the nature to have ill health. There is no way to escape having ill health.", attribution: remembrancesSource),
            LibraryItem(title: "The Third Remembrance", body: "I am of the nature to die. There is no way to escape death.", attribution: remembrancesSource),
            LibraryItem(
                title: "The Fourth Remembrance",
                body: "All that is dear to me and everyone I love are of the nature to change. There is no way to escape being separated from them.",
                attribution: remembrancesSource
            ),
            LibraryItem(
                title: "The Fifth Remembrance",
                body: "My actions are my only true belongings. I cannot escape the consequences of my actions. My actions are the ground upon which I stand.",
                attribution: remembrancesSource
            ),
        ]
    ),
    LibraryDeck(
        key: "sayings",
        name: "Sayings",
        description: "Occasional sayings on death, comparison and contentment.",
        ordering: .random,
        responseType: .noResponse,
        responseLabel: nil,
        weight: 1,
        items: [
            LibraryItem(title: nil, body: "To be a happy person, one must contemplate death five times daily.", attribution: "Bhutanese saying"),
            LibraryItem(title: nil, body: "Comparison is the thief of joy.", attribution: "Attributed to Theodore Roosevelt"),
            LibraryItem(title: nil, body: "There is no such thing as bad weather, only bad clothing.", attribution: "Scandinavian saying"),
            LibraryItem(
                title: nil,
                body: "You could leave life right now. Let that determine what you do and say and think.",
                attribution: "Marcus Aurelius, Meditations 2.11"
            ),
            LibraryItem(title: nil, body: "Enough is as good as a feast.", attribution: "English proverb"),
        ]
    ),
]
