package com.kert0n.medapp.network.account

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.kert0n.medapp.BuildConfig
import com.kert0n.medapp.domain.account.AccountReadiness
import com.kert0n.medapp.feature.account.AccountRegistration
import com.kert0n.medapp.feature.account.StoredAccount
import com.kert0n.medapp.network.server.ApiFailure
import com.kert0n.medapp.network.server.ApiResult
import com.kert0n.medapp.network.server.MedAppApi
import com.kert0n.medapp.network.server.medAppHttpClient
import com.kert0n.medapp.platform.credentials.KeystoreCredentialSource
import com.kert0n.medapp.platform.credentials.KeystoreKey
import io.ktor.client.engine.okhttp.OkHttp
import java.io.File
import java.security.KeyStore
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Регистрация против боевого сервера целиком, как её пройдёт устройство (PLAN B1, G2): токен
 * сборки → выданная учётка → ключ в AndroidKeyStore → пропуск по сохранённому → авторизованный
 * запрос.
 *
 * Каждый прогон заводит на сервере новую учётку, поэтому проверка включается только
 * `-PprobeRegistration` и в обычную пробу контракта не входит. Прогоняется один раз, когда
 * меняется код регистрации или хранения ключа.
 */
class RegistrationProbe {

    @Test
    fun deviceRegistersKeepsItsKeyAndIsAccepted() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val baseUrl = arguments.getString("probeBaseUrl")
        assumeTrue(
            "проверка регистрации включается только -PprobeRegistration: каждый прогон заводит учётку",
            arguments.getString("probeRegistration") == "true" && !baseUrl.isNullOrBlank()
        )

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val alias = "medapp.registration-probe.${Uuid.random()}"
        val file = File(context.cacheDir, "$alias.preferences_pb")
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val credentials = KeystoreCredentialSource(
                PreferenceDataStoreFactory.create(scope = scope) { file },
                file,
                KeystoreKey(alias),
                Dispatchers.IO
            )
            val tokens = AccessTokens(credentials)
            val api = MedAppApi(medAppHttpClient(OkHttp.create(), baseUrl!!, tokens = tokens))
            val registration = AccountRegistration(credentials, ServerAccounts(api, BuildConfig.REGISTRATION_TOKEN, tokens))

            assertEquals(AccountReadiness.Ready, registration.ensure())
            val stored = credentials.read()
            assertTrue("придуманная учётка сохранена и подтверждена", stored is StoredAccount.Present)
            assertEquals(AccountReadiness.Ready, registration.ensure())

            val snapshot = api.snapshot()
            assertTrue("сохранённая учётка принята сервером: $snapshot", snapshot is ApiResult.Success)

            // Потерянный ответ: повтор теми же данными второй учётки не заводит, а логин занят —
            // нами, и это показывает пропуск по тем же данным (PLAN B1).
            val account = (stored as StoredAccount.Present).credentials
            val repeated = api.register(account, BuildConfig.REGISTRATION_TOKEN)
            assertEquals(ApiResult.Failure(ApiFailure.Conflict), repeated)
            assertTrue("учётка наша: пропуск выдан", api.token(account) is ApiResult.Success)
        } finally {
            scope.cancel()
            file.delete()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        }
    }
}
