package com.kert0n.medapp.di

import com.kert0n.medapp.domain.template.PackageTemplates
import com.kert0n.medapp.network.template.ServerPackageTemplates
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Справочник — отдельной привязкой, а не строкой сетевого модуля: сквозные проверки экранов
 * подменяют его подделкой, как базу, и в боевой справочник из тестового приложения не ходят.
 */
@Module
@InstallIn(SingletonComponent::class)
object TemplatesModule {

    @Provides
    @Singleton
    fun packageTemplates(implementation: ServerPackageTemplates): PackageTemplates = implementation
}
