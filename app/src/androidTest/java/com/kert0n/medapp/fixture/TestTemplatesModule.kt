package com.kert0n.medapp.fixture

import com.kert0n.medapp.di.TemplatesModule
import com.kert0n.medapp.domain.template.PackageTemplates
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton
import kotlin.uuid.Uuid

/**
 * Справочник сквозных проверок — в памяти, как и их база: путь до боевого справочника читает
 * учётные данные устройства, а у тестового приложения их DataStore заводится на каждый тест
 * заново и на том же файле — чтение падает и роняет состояние формы. Проверять здесь есть что и
 * без сервера: подсказка приходит и заполняет форму.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [TemplatesModule::class])
object TestTemplatesModule {

    @Provides
    @Singleton
    fun packageTemplates(): PackageTemplates = FakePackageTemplates(
        template(id = Uuid.parse("00000000-0000-4000-8000-000000000071"), name = "Нурофен", manufacturer = "Reckitt")
    )
}
