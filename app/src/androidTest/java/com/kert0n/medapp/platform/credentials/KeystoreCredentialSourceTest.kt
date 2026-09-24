package com.kert0n.medapp.platform.credentials

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.platform.app.InstrumentationRegistry
import com.kert0n.medapp.domain.account.AccountCredentials
import com.kert0n.medapp.feature.account.CredentialsSaved
import com.kert0n.medapp.feature.account.StoredAccount
import java.io.File
import java.security.KeyStore
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Ключ лежит шифротекстом, ключ расшифровки — в AndroidKeyStore, а сохранённое, которое не
 * открывается, — это «нечитаема», а не «учётки нет» (PLAN G2).
 */
class KeystoreCredentialSourceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val alias = "medapp.test.${Uuid.random()}"
    private val credentials = AccountCredentials(
        login = Uuid.parse("00000000-0000-4000-8000-000000000071"),
        password = "k3y-shown-only-once-43-characters-long-abcd"
    )

    private lateinit var scope: CoroutineScope
    private lateinit var file: File
    private lateinit var blocked: File
    private lateinit var store: DataStore<Preferences>
    private lateinit var source: KeystoreCredentialSource

    @Before
    fun openStore() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        file = File(context.cacheDir, "$alias.preferences_pb")
        blocked = File(context.cacheDir, "$alias.blocked")
        store = PreferenceDataStoreFactory.create(scope = scope) { file }
        source = KeystoreCredentialSource(store, file, KeystoreKey(alias), Dispatchers.IO)
    }

    @After
    fun closeStore() {
        scope.cancel()
        file.delete()
        blocked.delete()
        keyStore().deleteEntry(alias)
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private suspend fun raw(name: String): String? =
        store.data.first()[stringPreferencesKey(name)]

    @Test
    fun nothingSavedMeansNoAccount() = runTest {
        assertEquals(StoredAccount.Absent, source.read())
    }

    /** Записанное само по себе не значит «сервер знает»: подтверждение приходит отдельно. */
    @Test
    fun savedAccountIsPendingUntilItIsConfirmed() = runTest {
        source.save(credentials)

        assertEquals(StoredAccount.Pending(credentials), source.read())

        source.confirm()

        assertEquals(StoredAccount.Present(credentials), source.read())
    }

    /** Подтверждать нечего, пока учётка не записана: признак сам по себе ничего не открывает. */
    @Test
    fun confirmationWithoutAnAccountChangesNothing() = runTest {
        source.confirm()

        assertEquals(StoredAccount.Absent, source.read())
    }

    /** Новые данные поверх подтверждённых снова ждут подтверждения. */
    @Test
    fun savingAgainTakesTheConfirmationAway() = runTest {
        source.save(credentials)
        source.confirm()

        source.save(credentials)

        assertEquals(StoredAccount.Pending(credentials), source.read())
    }

    @Test
    fun passwordIsStoredOnlyAsCiphertext() = runTest {
        source.save(credentials)

        val stored = store.data.first().asMap().values.joinToString()
        assertFalse(stored.contains(credentials.password))
    }

    @Test
    fun everySaveGetsItsOwnInitialisationVector() = runTest {
        source.save(credentials)
        val first = raw("password_iv")
        source.save(credentials)

        assertNotEquals(first, raw("password_iv"))
    }

    /** Сброс хранилища — утрата учётки, о которой спрашивают, а не повод регистрироваться заново. */
    @Test
    fun lostKeystoreKeyMakesTheAccountUnreadableNotAbsent() = runTest {
        source.save(credentials)
        keyStore().deleteEntry(alias)

        assertEquals(StoredAccount.Unreadable, source.read())
    }

    @Test
    fun tamperedCiphertextIsUnreadable() = runTest {
        source.save(credentials)
        store.edit { it[stringPreferencesKey("password_ciphertext")] = "AAAAAAAAAAAAAAAAAAAAAA==" }

        assertEquals(StoredAccount.Unreadable, source.read())
    }

    /**
     * Повреждённый файл хранилища — та же утрата: спрашивают человека, а не заводят вторую
     * учётку поверх локальных данных.
     */
    @Test
    fun damagedStoreIsUnreadableNotAbsent() = runTest {
        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf(0x4D, 0x65, 0x64, 0x41, 0x70, 0x70, 0x21, 0x00, 0x7F))

        assertEquals(StoredAccount.Unreadable, source.read())
    }

    /**
     * Стирает сохранённое только решение человека: после него учётки нет, и новая ложится на
     * чистое. Повреждённый файл стирается целиком — править его нечем.
     */
    @Test
    fun forgettingADamagedStoreMakesRoomForANewAccount() = runTest {
        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf(0x4D, 0x65, 0x64, 0x41, 0x70, 0x70, 0x21, 0x00, 0x7F))
        assertEquals(StoredAccount.Unreadable, source.read())

        assertEquals(CredentialsSaved.SAVED, source.forget())

        assertEquals(StoredAccount.Absent, source.read())
        assertEquals(CredentialsSaved.SAVED, source.save(credentials))
        assertEquals(StoredAccount.Pending(credentials), source.read())
    }

    /** Стирание читаемой учётки — тоже стирание: сценарий решает, звать ли его, а не хранилище. */
    @Test
    fun forgettingErasesAStoredAccount() = runTest {
        source.save(credentials)
        source.confirm()

        assertEquals(CredentialsSaved.SAVED, source.forget())

        assertEquals(StoredAccount.Absent, source.read())
        assertNull(raw("login"))
    }

    /**
     * Учётка, которую не удалось записать, — исход, а не исключение: на сервере её при этом нет,
     * и решение, повторять ли настройку, принимает человек.
     */
    @Test
    fun credentialsThatCannotBeWrittenAreReportedAsLost() = runTest {
        // Обычный файл на месте каталога: DataStore не создаст под ним свой файл.
        blocked.writeBytes(ByteArray(0))
        val unwritable = KeystoreCredentialSource(
            PreferenceDataStoreFactory.create(scope = scope) { File(blocked, "credentials.preferences_pb") },
            File(blocked, "credentials.preferences_pb"),
            KeystoreKey(alias),
            Dispatchers.IO
        )

        assertEquals(CredentialsSaved.LOST, unwritable.save(credentials))
    }

    /** Шифротекст привязан к своему логину: подставленный рядом чужой логин его не откроет. */
    @Test
    fun ciphertextDoesNotOpenUnderAnotherLogin() = runTest {
        source.save(credentials)
        store.edit { it[stringPreferencesKey("login")] = Uuid.random().toString() }

        assertEquals(StoredAccount.Unreadable, source.read())
    }
}
