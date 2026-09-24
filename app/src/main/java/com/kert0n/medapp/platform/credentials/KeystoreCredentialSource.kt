package com.kert0n.medapp.platform.credentials

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.kert0n.medapp.di.CredentialsFile
import com.kert0n.medapp.di.CredentialsStore
import com.kert0n.medapp.di.IoDispatcher
import com.kert0n.medapp.domain.account.AccountCredentials
import com.kert0n.medapp.feature.account.CredentialSource
import com.kert0n.medapp.feature.account.CredentialsSaved
import com.kert0n.medapp.feature.account.StoredAccount
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.util.Base64
import javax.inject.Inject
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Учётка на устройстве (PLAN G2): логин открыто, пароль — шифротекстом с вектором инициализации в
 * DataStore, а ключ расшифровки — в AndroidKeyStore. Каталог DataStore исключён из облачной копии
 * и переноса, поэтому шифротекст без своего ключа никуда не уезжает.
 *
 * Рядом лежит признак того, что сервер эти данные принял: данные придумывает устройство и
 * записывает их раньше запроса, поэтому «записано» и «сервер знает» — разные состояния.
 *
 * Сохранённое, которое не открывается, — это [StoredAccount.Unreadable], а не отсутствие учётки:
 * сброс хранилища не должен выглядеть приглашением зарегистрироваться заново. Повреждённый файл
 * DataStore попадает в тот же случай, и обработчика, который молча заменил бы его пустым, здесь
 * нет: пустой файл — это «учётки нет», то есть приглашение завести вторую поверх локальных данных.
 * Стирает сохранённое только [forget] — по решению человека, и повреждённый файл он стирает
 * целиком: править его нечем.
 */
class KeystoreCredentialSource @Inject constructor(
    @CredentialsStore private val store: DataStore<Preferences>,
    @CredentialsFile private val file: File,
    private val key: KeystoreKey,
    @IoDispatcher private val io: CoroutineDispatcher
) : CredentialSource {

    override suspend fun read(): StoredAccount = withContext(io) {
        try {
            val saved = store.data.first()
            val login = saved[LOGIN] ?: return@withContext StoredAccount.Absent
            val iv = saved[PASSWORD_IV]
            val ciphertext = saved[PASSWORD_CIPHERTEXT]
            if (iv == null || ciphertext == null) return@withContext StoredAccount.Unreadable
            val plain = key.open(
                KeystoreKey.Sealed(decode(iv), decode(ciphertext)),
                associated = login.encodeToByteArray()
            )
            val account = AccountCredentials(Uuid.parse(login), plain.decodeToString())
            if (saved[CONFIRMED] == true) StoredAccount.Present(account) else StoredAccount.Pending(account)
        } catch (_: IOException) {
            StoredAccount.Unreadable
        } catch (_: GeneralSecurityException) {
            StoredAccount.Unreadable
        } catch (_: ProviderException) {
            StoredAccount.Unreadable
        } catch (_: IllegalArgumentException) {
            StoredAccount.Unreadable
        }
    }

    override suspend fun save(credentials: AccountCredentials): CredentialsSaved = withContext(io) {
        val login = credentials.login.toString()
        try {
            val sealed = key.seal(credentials.password.encodeToByteArray(), associated = login.encodeToByteArray())
            store.edit {
                it[LOGIN] = login
                it[PASSWORD_IV] = encode(sealed.iv)
                it[PASSWORD_CIPHERTEXT] = encode(sealed.ciphertext)
                it[CONFIRMED] = false
            }
            CredentialsSaved.SAVED
        } catch (_: IOException) {
            CredentialsSaved.LOST
        } catch (_: GeneralSecurityException) {
            CredentialsSaved.LOST
        } catch (_: ProviderException) {
            CredentialsSaved.LOST
        }
    }

    override suspend fun confirm(): CredentialsSaved = withContext(io) {
        try {
            // Подтверждать нечего, пока учётка не записана: признак сам по себе ничего не значит.
            store.edit { if (it[LOGIN] != null) it[CONFIRMED] = true }
            CredentialsSaved.SAVED
        } catch (_: IOException) {
            CredentialsSaved.LOST
        }
    }

    override suspend fun forget(): CredentialsSaved = withContext(io) {
        try {
            store.edit { it.clear() }
            CredentialsSaved.SAVED
        } catch (_: IOException) {
            // Файл не читается — и правка поверх него невозможна: он стирается целиком.
            if (!file.exists() || file.delete()) CredentialsSaved.SAVED else CredentialsSaved.LOST
        }
    }

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun decode(text: String): ByteArray = Base64.getDecoder().decode(text)

    private companion object {
        val LOGIN = stringPreferencesKey("login")
        val PASSWORD_IV = stringPreferencesKey("password_iv")
        val PASSWORD_CIPHERTEXT = stringPreferencesKey("password_ciphertext")
        val CONFIRMED = booleanPreferencesKey("confirmed")
    }
}
