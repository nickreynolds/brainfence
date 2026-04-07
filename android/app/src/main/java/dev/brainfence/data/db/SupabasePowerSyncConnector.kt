package dev.brainfence.data.db

import com.powersync.connector.supabase.SupabaseConnector
import io.github.jan.supabase.SupabaseClient
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Thin wrapper around PowerSync's official SupabaseConnector.
 * Handles fetchCredentials (Supabase JWT → PowerSync token) and
 * uploadData (local CRUD ops → Supabase Postgrest) automatically.
 */
@Singleton
class SupabasePowerSyncConnector @Inject constructor(
    supabase: SupabaseClient,
    @Named("powerSyncUrl") powerSyncUrl: String,
) : SupabaseConnector(supabase, powerSyncUrl)
