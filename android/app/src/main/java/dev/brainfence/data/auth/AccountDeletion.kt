package dev.brainfence.data.auth

import com.powersync.PowerSyncDatabase
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.SignOutScope
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import javax.inject.Inject
import javax.inject.Singleton

/** Deletes the signed-in account (shared/CONTRACTS.md, Account deletion). */
@Singleton
class AccountDeletion @Inject constructor(
    private val supabase: SupabaseClient,
    private val database: PowerSyncDatabase,
) {
    /** Throws, having changed nothing, if the server couldn't delete the account. */
    suspend fun delete() {
        supabase.postgrest.rpc("delete_account")
        database.disconnectAndClear()
        // The server session went with the user, so only drop the local one.
        supabase.auth.signOut(SignOutScope.LOCAL)
    }
}
