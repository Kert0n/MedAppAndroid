package com.kert0n.medapp.fixture

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.kert0n.medapp.di.CredentialsFile
import com.kert0n.medapp.di.CredentialsModule
import com.kert0n.medapp.di.CredentialsStore
import com.kert0n.medapp.network.account.AccountCredentials
import com.kert0n.medapp.network.account.CredentialSource
import com.kert0n.medapp.network.account.CredentialsSaved
import com.kert0n.medapp.network.account.StoredAccount
import com.kert0n.medapp.platform.credentials.KeystoreCredentialSource
import com.kert0n.medapp.platform.credentials.KeystoreKey
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import java.io.File
import javax.inject.Singleton

/**
 * Учётная запись приложения в проверках. Обычно — настоящая, из Keystore; под `-Pprobe`
 * приложение живёт **пробной учёткой A**, и тогда историю двоих можно вести по-настоящему:
 * приложение ведёт одного человека по экранам, а второй действует своим клиентом
 * (`ProbeAccounts.boris`) против того же боевого сервера (AGENTS «Связь с сервером»).
 *
 * Своей учётки проверка не заводит: пробные пользователи заведены один раз и лежат в
 * `local.properties`.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [CredentialsModule::class])
object TestCredentialsModule {

    @Provides
    @Singleton
    fun credentials(real: KeystoreCredentialSource): CredentialSource =
        ProbeAccounts.annaAccount?.let(::Probe) ?: real

    @Provides
    @Singleton
    @CredentialsFile
    fun file(@ApplicationContext context: Context): File = context.preferencesDataStoreFile("credentials")

    @Provides
    @Singleton
    @CredentialsStore
    fun store(@CredentialsFile file: File): DataStore<Preferences> = PreferenceDataStoreFactory.create { file }

    @Provides
    @Singleton
    fun keystoreKey(): KeystoreKey = KeystoreKey("medapp.account.key")

    /** Учётка, которую не заводят и не стирают: она уже есть и переживает прогон. */
    private class Probe(private val account: AccountCredentials) : CredentialSource {
        override suspend fun read(): StoredAccount = StoredAccount.Present(account)
        override suspend fun save(credentials: AccountCredentials): CredentialsSaved =
            error("пробная учётка заведена один раз и лежит в local.properties")

        override suspend fun confirm(): CredentialsSaved = CredentialsSaved.SAVED
        override suspend fun forget(): CredentialsSaved =
            error("пробную учётку не стирают: она общая на все прогоны")
    }
}
