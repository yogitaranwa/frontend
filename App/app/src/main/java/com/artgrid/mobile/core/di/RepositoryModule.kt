/**
 * RepositoryModule.kt
 * Responsibility : Binds every Repository interface to its Impl — Hilt's @Binds
 *                  keeps the concrete class out of ViewModel constructors.
 * API calls      : none (DI configuration)
 * Injects        : all RepositoryImpl classes
 */
package com.artgrid.mobile.core.di

import com.artgrid.mobile.data.auth.AuthRepositoryImpl
import com.artgrid.mobile.data.chat.ChatRepositoryImpl
import com.artgrid.mobile.data.ml.MlRepositoryImpl
import com.artgrid.mobile.data.progression.ProgressionRepositoryImpl
import com.artgrid.mobile.data.reference.ReferenceRepositoryImpl
import com.artgrid.mobile.domain.auth.AuthRepository
import com.artgrid.mobile.domain.chat.ChatRepository
import com.artgrid.mobile.domain.ml.MlRepository
import com.artgrid.mobile.domain.progression.ProgressionRepository
import com.artgrid.mobile.domain.reference.ReferenceRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds @Singleton
    abstract fun bindAuthRepository(impl: AuthRepositoryImpl): AuthRepository

    @Binds @Singleton
    abstract fun bindMlRepository(impl: MlRepositoryImpl): MlRepository

    @Binds @Singleton
    abstract fun bindChatRepository(impl: ChatRepositoryImpl): ChatRepository

    @Binds @Singleton
    abstract fun bindReferenceRepository(impl: ReferenceRepositoryImpl): ReferenceRepository

    @Binds @Singleton
    abstract fun bindProgressionRepository(impl: ProgressionRepositoryImpl): ProgressionRepository
}
