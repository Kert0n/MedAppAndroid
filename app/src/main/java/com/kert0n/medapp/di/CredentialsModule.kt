package com.kert0n.medapp.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.kert0n.medapp.network.account.CredentialSource
import com.kert0n.medapp.platform.credentials.KeystoreCredentialSource
import com.kert0n.medapp.platform.credentials.KeystoreKey
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CredentialsStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CredentialsFile

/**
 * Сеть получает учётку через свой порт, а хранит её платформа. Файл DataStore лежит в каталоге
 * `datastore`, который правила резервного копирования исключают (PLAN G2).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CredentialsModule {

    @Binds
    @Singleton
    abstract fun credentials(implementation: KeystoreCredentialSource): CredentialSource

    companion object {

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
    }
}
