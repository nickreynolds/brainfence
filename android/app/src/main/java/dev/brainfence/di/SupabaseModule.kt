package dev.brainfence.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.brainfence.BuildConfig
import dev.brainfence.data.auth.EncryptedSessionManager
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.FlowType
import dev.brainfence.domain.auth.AuthCallback
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SupabaseModule {

    @Provides
    @Singleton
    fun provideSupabaseClient(@ApplicationContext context: Context): SupabaseClient =
        createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
        ) {
            install(Auth) {
                sessionManager = EncryptedSessionManager(context)
                // By default, supabase-kt stops auto-refresh when the app's last
                // activity stops and resets the session status to Initializing
                // until an activity starts again. Brainfence works mostly in the
                // background (foreground service, alarms, geofences), so that left
                // PowerSync unable to sync and background writes "signed out" for
                // most of the day. Keep the session refreshing for the whole
                // process lifetime instead.
                enableLifecycleCallbacks = false
                // Email confirmation links open the app (shared/CONTRACTS.md, Email links).
                flowType = FlowType.PKCE
                scheme = AuthCallback.SCHEME
                host = AuthCallback.HOST
            }
            install(Postgrest)
        }
}
