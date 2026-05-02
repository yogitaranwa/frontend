/**
 * DatabaseModule.kt
 * Responsibility : Provides Room database singleton and DAO instances via Hilt.
 * Pattern used   : @Module @InstallIn(SingletonComponent)
 * Dependencies   : ArtGridDatabase, Room
 */
package com.artgrid.mobile.core.di

import android.content.Context
import androidx.room.Room
import com.artgrid.mobile.data.db.ArtGridDatabase
import com.artgrid.mobile.data.db.dao.ProgressionDao
import com.artgrid.mobile.data.db.dao.ReferenceAssetDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideArtGridDatabase(
        @ApplicationContext context: Context,
    ): ArtGridDatabase = Room.databaseBuilder(
        context,
        ArtGridDatabase::class.java,
        "artgrid.db",
    ).build()

    @Provides
    @Singleton
    fun provideReferenceAssetDao(db: ArtGridDatabase): ReferenceAssetDao =
        db.referenceAssetDao()

    @Provides
    @Singleton
    fun provideProgressionDao(db: ArtGridDatabase): ProgressionDao =
        db.progressionDao()
}
