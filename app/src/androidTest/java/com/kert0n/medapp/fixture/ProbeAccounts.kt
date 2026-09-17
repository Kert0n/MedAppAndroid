package com.kert0n.medapp.fixture

import androidx.test.platform.app.InstrumentationRegistry
import com.kert0n.medapp.network.account.AccessTokens
import com.kert0n.medapp.network.account.AccountCredentials
import com.kert0n.medapp.network.account.CredentialSource
import com.kert0n.medapp.network.account.CredentialsSaved
import com.kert0n.medapp.network.account.StoredAccount
import com.kert0n.medapp.network.server.MedAppApi
import com.kert0n.medapp.network.server.medAppHttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlin.uuid.Uuid

/**
 * Пробные пользователи боевого сервера — **один клиент на пользователя на весь прогон**, общий для
 * всех проб (AGENTS «Связь с сервером»). Учётки заведены один раз и лежат в `local.properties`;
 * сервер ограничивает не их, а выдачу пропуска: 20 за 5 минут с одного адреса, какой бы учётка ни
 * была. Клиент на каждый класс проб просил бы пропуск заново, и несколько прогонов подряд
 * упирались в 429. Пропуск живёт 10 минут; истёкший клиент перевыпускает сам, по 401.
 *
 * `null` у [anna]/[boris] и непустой [skipReason] — пробы не включены (`-Pprobe`) или учётки не заведены.
 * Третья учётка спрошена отдельным [thirdSkipReason]: истории двух людей ей ничего не должны и без
 * неё идут как шли.
 */
object ProbeAccounts {

    private class Fixed(private val account: AccountCredentials) : CredentialSource {
        override suspend fun read(): StoredAccount = StoredAccount.Present(account)
        override suspend fun save(credentials: AccountCredentials): CredentialsSaved =
            error("пробы учёток не заводят: они заведены один раз и лежат в local.properties")

        override suspend fun confirm(): CredentialsSaved = CredentialsSaved.SAVED
        override suspend fun forget(): CredentialsSaved =
            error("пробы учёток не стирают: они заведены один раз и лежат в local.properties")
    }

    private val arguments get() = InstrumentationRegistry.getArguments()

    /** Адрес сервера проб; `null` — пробы не включены. */
    val baseUrl: String? by lazy { arguments.getString("probeBaseUrl")?.takeIf { it.isNotBlank() } }

    private fun credentials(user: String): AccountCredentials? {
        val login = arguments.getString("probeLogin$user")
        val key = arguments.getString("probeKey$user")
        if (login.isNullOrBlank() || key.isNullOrBlank()) return null
        return AccountCredentials(Uuid.parse(login), key)
    }

    /** Учётка A — владелец в `ContractProbe`, Анна в сценариях двух людей. */
    val annaAccount: AccountCredentials? by lazy { credentials("A") }

    /** Учётка B — гость в `ContractProbe`, Борис в сценариях двух людей. */
    val borisAccount: AccountCredentials? by lazy { credentials("B") }

    /** Учётка C — третий человек: нужна там, где полок две, а людей трое. */
    val viktorAccount: AccountCredentials? by lazy { credentials("C") }

    val skipReason: String? get() = when {
        baseUrl == null -> "пробы боевого сервера включаются только -Pprobe"
        annaAccount == null || borisAccount == null -> "пробные пользователи не заведены: scripts/register-probe-users.sh"
        else -> null
    }

    /** Почему история троих идёт не здесь: `null` — идёт. */
    val thirdSkipReason: String? get() = skipReason
        ?: "третий пробный пользователь не заведён: scripts/register-probe-users.sh".takeIf { viktorAccount == null }

    val anna: MedAppApi? by lazy { annaAccount?.let { api(it) } }

    val boris: MedAppApi? by lazy { borisAccount?.let { api(it) } }

    /** Без учётки: пропуска не просит, и лимит выдачи не тратит. */
    val viktor: MedAppApi? by lazy { viktorAccount?.let { api(it) } }

    val anonymous: MedAppApi? by lazy { baseUrl?.let { MedAppApi(medAppHttpClient(OkHttp.create(), it, tokens = null)) } }

    private fun api(account: AccountCredentials): MedAppApi? =
        baseUrl?.let { MedAppApi(medAppHttpClient(OkHttp.create(), it, tokens = AccessTokens(Fixed(account)))) }
}
