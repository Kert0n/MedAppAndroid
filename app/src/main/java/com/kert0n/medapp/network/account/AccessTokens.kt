package com.kert0n.medapp.network.account

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Пропуск MedApp: живёт только в памяти процесса и выдаётся по учётке (PLAN B1, G2).
 *
 * Выдача одна на всех: запросы, одновременно получившие 401 со старым пропуском, ждут одну
 * выдачу и берут её результат — **любой**, а не только удачный, — иначе лимит выдачи по адресу
 * сгорел бы на одном всплеске. [AccessTokenIssue.Rejected] от сервера запоминается на время
 * процесса: учётка не принята, и по тому же ключу пропуск просить нечего.
 *
 * Отсутствие учётки [AccessTokenIssue.Rejected] тоже, но оно не запоминается: регистрация
 * заводит учётку в том же процессе, и после неё пропуск должен выдаваться.
 *
 * **Пропуск — учётки** (C1): сменилась учётка — пропуска нет, и память об отказе тоже. Сервер
 * принимал бы старый пропуск ещё часы, но он пропуск чужой теперь учётки, и предъявлять его
 * значило бы действовать от прежнего имени. Об этом говорит тот, кто учётку пишет, — [forget].
 */
@Singleton
class AccessTokens @Inject constructor(private val credentials: CredentialSource) {

    private val mutex = Mutex()

    /** Сколько выдач уже состоялось. По нему видно, что выдача прошла, пока мы ждали очереди. */
    @Volatile
    private var issues: Long = 0

    @Volatile
    private var last: AccessTokenIssue? = null

    val current: String? get() = (last as? AccessTokenIssue.Issued)?.token

    /** Учётка сменилась: её пропуска у нас больше нет, следующий запрос попросит пропуск по новой. */
    suspend fun forget() = mutex.withLock {
        last = null
        issues++
    }

    /**
     * Новый пропуск вместо [stale]. Если его уже заменил другой запрос — или другой запрос уже
     * получил отказ, — возвращается тот результат, и второй выдачи не происходит.
     */
    suspend fun renew(
        stale: String?,
        issue: suspend (AccountCredentials) -> AccessTokenIssue
    ): AccessTokenIssue {
        val awaited = issues
        return mutex.withLock {
            val known = last
            if (issues != awaited) known?.let { return@withLock it }
            when (known) {
                is AccessTokenIssue.Issued -> if (known.token != stale) return@withLock known
                AccessTokenIssue.Rejected -> return@withLock known
                else -> Unit
            }
            val account = (credentials.read() as? StoredAccount.Present)?.credentials
                ?: return@withLock AccessTokenIssue.Rejected
            issue(account).also {
                last = it
                issues++
            }
        }
    }
}
