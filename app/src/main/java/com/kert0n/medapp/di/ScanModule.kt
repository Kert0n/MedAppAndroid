package com.kert0n.medapp.di

import com.kert0n.medapp.domain.scan.PackageCodes
import com.kert0n.medapp.network.marking.MarkingPackageCodes
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Код с коробки спрашивают у реестра маркировки — отдельным клиентом без пропуска MedApp (PLAN G3).
 *
 * Привязка стоит отдельно от сетевого модуля по той же причине, что и справочник: сквозные
 * проверки подменяют реестр подделкой, а живой спрашивает одна проба и только по явной просьбе —
 * сервис чужой, и лишний трафик может нас отрезать (AGENTS «Реестр маркировки»).
 */
@Module
@InstallIn(SingletonComponent::class)
object ScanModule {

    @Provides
    @Singleton
    fun packageCodes(implementation: MarkingPackageCodes): PackageCodes = implementation
}
