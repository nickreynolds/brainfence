import BrainfenceCore
import Foundation
import PowerSync

/// Contemplation decks, their items and deliveries.
/// Port of android/…/data/contemplation/ContemplationRepository.kt.
struct ContemplationRepository: Sendable {
    let db: PowerSyncDatabaseProtocol

    static let deckTables = ["contemplation_decks", "contemplations"]
    static let deliveryTables = ["contemplation_deliveries"]

    private static let deckColumns = """
        d.id, d.name, d.description, d.ordering, d.response_type, d.response_label,
        d.weight, d.is_enabled, d.library_key, d.sort_order,
        (SELECT COUNT(*) FROM contemplations c WHERE c.deck_id = d.id) AS item_count
        """

    @Sendable
    private static func mapDeck(_ c: SqlCursor) throws -> ContemplationDeck {
        ContemplationDeck(
            id: try c.getString(index: 0),
            name: c.getStringOptional(index: 1) ?? "",
            description: c.getStringOptional(index: 2),
            ordering: DeckOrdering(raw: c.getStringOptional(index: 3)),
            responseType: ContemplationResponseType(raw: c.getStringOptional(index: 4)),
            responseLabel: c.getStringOptional(index: 5),
            weight: Int(c.getInt64Optional(index: 6) ?? 1),
            isEnabled: (c.getInt64Optional(index: 7) ?? 1) != 0,
            libraryKey: c.getStringOptional(index: 8),
            sortOrder: Int(c.getInt64Optional(index: 9) ?? 0),
            itemCount: Int(c.getInt64Optional(index: 10) ?? 0)
        )
    }

    func decks() async throws -> [ContemplationDeck] {
        try await db.getAll(
            sql: "SELECT \(Self.deckColumns) FROM contemplation_decks d ORDER BY d.sort_order, d.created_at",
            parameters: [],
            mapper: Self.mapDeck
        )
    }

    func deck(id: String) async throws -> ContemplationDeck? {
        try await db.getOptional(
            sql: "SELECT \(Self.deckColumns) FROM contemplation_decks d WHERE d.id = ?",
            parameters: [id],
            mapper: Self.mapDeck
        )
    }

    func createDeck(userId: String, name: String) async throws -> String {
        let id = UUID().uuidString.lowercased()
        let now = Timestamp.format(Date())
        try await db.execute(
            sql: """
                INSERT INTO contemplation_decks
                    (id, user_id, name, description, ordering, response_type, response_label,
                     weight, is_enabled, library_key, sort_order, created_at, updated_at)
                VALUES (?, ?, ?, NULL, 'random', 'none', NULL, 1, 1, NULL, 0, ?, ?)
                """,
            parameters: [id, userId, name, now, now]
        )
        return id
    }

    func addFromLibrary(_ deck: LibraryDeck, userId: String) async throws {
        let now = Timestamp.format(Date())
        let deckId = UUID().uuidString.lowercased()
        try await db.writeTransaction { tx in
            try tx.execute(
                sql: """
                    INSERT INTO contemplation_decks
                        (id, user_id, name, description, ordering, response_type, response_label,
                         weight, is_enabled, library_key, sort_order, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?, 0, ?, ?)
                    """,
                parameters: [
                    deckId, userId, deck.name, deck.description, deck.ordering.rawValue, deck.responseType.rawValue,
                    deck.responseLabel, deck.weight, deck.key, now, now,
                ]
            )
            for (index, item) in deck.items.enumerated() {
                try tx.execute(
                    sql: """
                        INSERT INTO contemplations (id, user_id, deck_id, title, body, attribution, sort_order, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                    parameters: [UUID().uuidString.lowercased(), userId, deckId, item.title, item.body, item.attribution, index, now, now]
                )
            }
        }
    }

    func updateDeck(
        id: String, name: String, description: String?, ordering: DeckOrdering,
        responseType: ContemplationResponseType, responseLabel: String?, weight: Int
    ) async throws {
        try await db.execute(
            sql: """
                UPDATE contemplation_decks
                SET name = ?, description = ?, ordering = ?, response_type = ?, response_label = ?, weight = ?, updated_at = ?
                WHERE id = ?
                """,
            parameters: [name, description, ordering.rawValue, responseType.rawValue, responseLabel, max(1, weight), Timestamp.format(Date()), id]
        )
    }

    func setDeckEnabled(id: String, enabled: Bool) async throws {
        try await db.execute(
            sql: "UPDATE contemplation_decks SET is_enabled = ?, updated_at = ? WHERE id = ?",
            parameters: [enabled ? 1 : 0, Timestamp.format(Date()), id]
        )
    }

    /// Deletes a deck and its items (the server cascade doesn't apply locally). Deliveries stay.
    func deleteDeck(id: String) async throws {
        try await db.writeTransaction { tx in
            try tx.execute(sql: "DELETE FROM contemplations WHERE deck_id = ?", parameters: [id])
            try tx.execute(sql: "DELETE FROM contemplation_decks WHERE id = ?", parameters: [id])
        }
    }

    func items(deckId: String) async throws -> [Contemplation] {
        try await db.getAll(
            sql: """
                SELECT id, deck_id, title, body, attribution, sort_order FROM contemplations
                WHERE deck_id = ? ORDER BY sort_order, created_at
                """,
            parameters: [deckId]
        ) { c in
            Contemplation(
                id: try c.getString(index: 0),
                deckId: c.getStringOptional(index: 1) ?? "",
                title: c.getStringOptional(index: 2),
                body: c.getStringOptional(index: 3) ?? "",
                attribution: c.getStringOptional(index: 4),
                sortOrder: Int(c.getInt64Optional(index: 5) ?? 0)
            )
        }
    }

    func addItem(deckId: String, userId: String, title: String?, body: String, attribution: String?) async throws {
        let now = Timestamp.format(Date())
        try await db.execute(
            sql: """
                INSERT INTO contemplations (id, user_id, deck_id, title, body, attribution, sort_order, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, (SELECT COALESCE(MAX(sort_order), -1) + 1 FROM contemplations WHERE deck_id = ?), ?, ?)
                """,
            parameters: [UUID().uuidString.lowercased(), userId, deckId, title, body, attribution, deckId, now, now]
        )
    }

    func updateItem(id: String, title: String?, body: String, attribution: String?) async throws {
        try await db.execute(
            sql: "UPDATE contemplations SET title = ?, body = ?, attribution = ?, updated_at = ? WHERE id = ?",
            parameters: [title, body, attribution, Timestamp.format(Date()), id]
        )
    }

    func deleteItem(id: String) async throws {
        try await db.execute(sql: "DELETE FROM contemplations WHERE id = ?", parameters: [id])
    }

    struct Choice: Sendable {
        let deck: ContemplationDeck
        let item: Contemplation
    }

    /// A deck by weight among enabled decks with items, then an item by the deck's ordering.
    func pickNext() async throws -> Choice? {
        let candidates = try await decks().filter { $0.isEnabled && $0.itemCount > 0 }
        guard !candidates.isEmpty else { return nil }
        let random = { Double.random(in: 0..<1) }
        let deck = candidates[pickWeightedIndex(candidates.map(\.weight), random: random)]
        let deckItems = try await items(deckId: deck.id)
        guard !deckItems.isEmpty else { return nil }
        let last = try await db.getOptional(
            sql: """
                SELECT COALESCE(contemplation_id, '') FROM contemplation_deliveries
                WHERE deck_id = ? ORDER BY delivered_at DESC LIMIT 1
                """,
            parameters: [deck.id]
        ) { c in c.getStringOptional(index: 0) ?? "" }
        let index = pickItemIndex(ordering: deck.ordering, itemIds: deckItems.map(\.id), lastDeliveredId: last, random: random)
        return Choice(deck: deck, item: deckItems[index])
    }

    /// Logs a delivery, snapshotting its content. Returns the delivery id.
    func recordDelivery(_ choice: Choice, userId: String) async throws -> String {
        let id = UUID().uuidString.lowercased()
        let now = Timestamp.format(Date())
        try await db.execute(
            sql: """
                INSERT INTO contemplation_deliveries
                    (id, user_id, deck_id, contemplation_id, title, body, attribution, delivered_at, response, responded_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, ?)
                """,
            parameters: [id, userId, choice.deck.id, choice.item.id, choice.item.title, choice.item.body, choice.item.attribution, now, now]
        )
        return id
    }

    /// Records the user's response; the first response wins.
    func recordResponse(deliveryId: String, response: String) async throws {
        try await db.execute(
            sql: "UPDATE contemplation_deliveries SET response = ?, responded_at = ? WHERE id = ? AND response IS NULL",
            parameters: [response, Timestamp.format(Date()), deliveryId]
        )
    }

    func recentDeliveries(limit: Int = 20) async throws -> [ContemplationDelivery] {
        try await db.getAll(
            sql: """
                SELECT id, deck_id, contemplation_id, title, body, attribution, delivered_at, response, responded_at
                FROM contemplation_deliveries ORDER BY delivered_at DESC LIMIT ?
                """,
            parameters: [limit]
        ) { c in
            ContemplationDelivery(
                id: try c.getString(index: 0),
                deckId: c.getStringOptional(index: 1),
                contemplationId: c.getStringOptional(index: 2),
                title: c.getStringOptional(index: 3),
                body: c.getStringOptional(index: 4) ?? "",
                attribution: c.getStringOptional(index: 5),
                deliveredAt: c.getStringOptional(index: 6) ?? "",
                response: c.getStringOptional(index: 7),
                respondedAt: c.getStringOptional(index: 8)
            )
        }
    }
}
