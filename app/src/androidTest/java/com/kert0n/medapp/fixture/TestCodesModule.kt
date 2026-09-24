package com.kert0n.medapp.fixture

import com.kert0n.medapp.di.ScanModule
import com.kert0n.medapp.domain.scan.PackageCodes
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

/**
 * Реестр маркировки сквозных проверок — в памяти: живой спрашивает одна проба и только по явной
 * просьбе, сервис чужой (AGENTS «Реестр маркировки»). Отвечает он тем же, что настоящий ответил о
 * коробке угля, с которой снята фикстура ответа.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [ScanModule::class])
object TestCodesModule {

    @Provides
    @Singleton
    fun packageCodes(): PackageCodes = FakePackageCodes(FakePackageCodes.found())
}
