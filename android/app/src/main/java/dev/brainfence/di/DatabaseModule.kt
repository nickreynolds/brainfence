package dev.brainfence.di

import android.content.Context
import com.powersync.DatabaseDriverFactory
import com.powersync.PowerSyncDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.brainfence.data.db.AppSchema
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Named("powerSyncUrl")
    fun providePowerSyncUrl(): String =
        "http://10.0.2.2:8080" // localhost from Android emulator; update for physical device

    @Provides
    @Singleton
    fun providePowerSyncDatabase(@ApplicationContext context: Context): PowerSyncDatabase =
        PowerSyncDatabase(
            factory = DatabaseDriverFactory(context),
            schema  = AppSchema,
        )
}
