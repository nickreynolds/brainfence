import BrainfenceCore
import Foundation
import OSLog
import PowerSync
import Supabase

/// Connects PowerSync to Supabase: hands PowerSync the user's Supabase JWT,
/// and uploads local writes through PostgREST.
///
/// Uploads go through `UploadNormalizer` so JSONB / array / boolean columns
/// reach Postgres as real JSON types rather than the text PowerSync stores
/// locally — the Swift equivalent of Android's SupabasePowerSyncConnector.
final class SupabaseConnector: PowerSyncBackendConnectorProtocol {
    private let client: SupabaseClient
    private let powerSyncURL: String
    private let uploadLog: UploadErrorLog
    private let logger = Logger(subsystem: "dev.brainfence.macos", category: "upload")

    init(client: SupabaseClient, powerSyncURL: String, uploadLog: UploadErrorLog) {
        self.client = client
        self.powerSyncURL = powerSyncURL
        self.uploadLog = uploadLog
    }

    func fetchCredentials() async throws -> PowerSyncCredentials? {
        do {
            // Refreshes the access token if it has expired.
            let session = try await client.auth.session
            return PowerSyncCredentials(endpoint: powerSyncURL, token: session.accessToken)
        } catch AuthError.sessionMissing {
            return nil
        }
    }

    func uploadData(database: PowerSyncDatabaseProtocol) async throws {
        guard let transaction = try await database.getNextCrudTransaction() else { return }

        for entry in transaction.crud {
            do {
                try await upload(entry)
            } catch let error where Self.isUnrecoverable(error) {
                // Retrying can never succeed (constraint / type / RLS violation),
                // and leaving it queued would block every later write. Skip the
                // entry, but record it loudly — this always indicates a bug.
                logger.error("Discarding \(entry.op.rawValue) \(entry.table)/\(entry.id): \(String(describing: error))")
                await uploadLog.record(entry: entry, error: error)
            }
            // Any other error propagates: PowerSync retries the whole
            // transaction after its retry delay. Upserts make that idempotent.
        }
        try await transaction.complete()
    }

    private func upload(_ entry: CrudEntry) async throws {
        let table = client.from(entry.table)
        switch entry.op {
        case .put:
            var values = normalizedValues(entry)
            values["id"] = .string(entry.id)
            try await table.upsert(values, returning: .minimal).execute()
        case .patch:
            let values = normalizedValues(entry)
            guard !values.isEmpty else { return }
            try await table.update(values, returning: .minimal).eq("id", value: entry.id).execute()
        case .delete:
            try await table.delete(returning: .minimal).eq("id", value: entry.id).execute()
        }
    }

    private func normalizedValues(_ entry: CrudEntry) -> [String: BrainfenceCore.JSONValue] {
        let raw = (entry.opDataTyped ?? [:]).mapValues(BrainfenceCore.JSONValue.init(powerSync:))
        return UploadNormalizer.normalize(table: entry.table, values: raw)
    }

    /// Postgres error classes that retrying cannot fix.
    private static func isUnrecoverable(_ error: any Error) -> Bool {
        guard let error = error as? PostgrestError, let code = error.code else { return false }
        return code.hasPrefix("22")      // data exception (bad type / malformed array literal)
            || code.hasPrefix("23")      // integrity constraint violation
            || code == "42501"           // insufficient privilege (RLS)
            || code == "PGRST204"        // column not found — local schema ahead of Postgres
    }
}

extension BrainfenceCore.JSONValue {
    init(powerSync value: PowerSync.JsonValue) {
        switch value {
        case .string(let s): self = .string(s)
        case .int(let i): self = .int(i)
        case .double(let d): self = .double(d)
        case .bool(let b): self = .bool(b)
        case .null: self = .null
        case .array(let a): self = .array(a.map(Self.init(powerSync:)))
        case .object(let o): self = .object(o.mapValues(Self.init(powerSync:)))
        }
    }
}

/// Uploads that were rejected permanently and discarded, kept for the
/// Diagnostics screen. Most recent first, capped.
@MainActor
@Observable
final class UploadErrorLog {
    struct Item: Identifiable, Sendable {
        let id = UUID()
        let at: Date
        let summary: String
        let error: String
    }

    private(set) var items: [Item] = []

    nonisolated func record(entry: CrudEntry, error: any Error) async {
        let item = Item(at: Date(), summary: "\(entry.op.rawValue) \(entry.table) \(entry.id)", error: String(describing: error))
        await MainActor.run {
            items.insert(item, at: 0)
            if items.count > 50 { items.removeLast(items.count - 50) }
        }
    }
}
