package com.kert0n.medapp.network.value

import com.kert0n.medapp.domain.value.Vocabulary
import com.kert0n.medapp.network.server.ApiFailure
import com.kert0n.medapp.network.server.ApiResult
import com.kert0n.medapp.network.server.MedAppApi
import javax.inject.Inject

/**
 * Владеет снимком словаря и его дочитыванием. Разбор, назвавший единицу или форму, которых в
 * снимке нет, не отвергается: словарь дочитывается с сервера, и разбор повторяется. Без связи
 * промах остаётся промахом с названной причиной — снимок старый, а не запись негодная — и
 * повторится при следующей связи.
 */
class VocabularyResolver @Inject constructor(
    private val store: VocabularyStore,
    private val api: MedAppApi
) {

    suspend fun snapshot(): Vocabulary = store.snapshot()

    /** Свежий словарь с сервера, записанный поверх снимка; отказ чтения снимка не трогает. */
    suspend fun refresh(): ApiResult<Vocabulary> {
        val units = when (val read = api.quantityUnits()) {
            is ApiResult.Failure -> return read
            is ApiResult.Success -> read.value.map { it.toQuantityUnit() }
        }
        val forms = when (val read = api.formTypes()) {
            is ApiResult.Failure -> return read
            is ApiResult.Success -> read.value.map { it.toDosageForm() }
        }
        store.save(units, forms)
        return ApiResult.Success(store.snapshot())
    }

    /**
     * Разбор по снимку с одним повтором: промах дочитывает словарь и повторяет [read] по свежему.
     * Второй промах после свежего словаря — не задержка, а запись, называющая то, чего сервер не
     * знает; она отдаётся как [Resolution.Unresolved] с той же причиной.
     */
    suspend fun <T> resolve(read: (Vocabulary) -> T): Resolution<T> = session().resolve(read)

    /** Заход разбора многих записей — снимка, прохода очереди, — в котором словарь дочитывается один раз. */
    fun session(): Session = Session()

    /**
     * Один заход разбора. Сколько бы записей в нём ни промахнулось, словарь дочитывается **не
     * больше раза**: промах второй записи после дочитанного словаря — уже не «снимок устарел», а
     * повторять чтение, не удавшееся без связи, в том же заходе незачем.
     */
    inner class Session internal constructor() {

        private var refreshed: ApiResult<Vocabulary>? = null

        /** Дочитать словарь в этом заходе не удалось: промах ждёт следующего захода, а не человека. */
        val refreshFailed: Boolean get() = refreshed is ApiResult.Failure

        /** Дочитать словарь, если в этом заходе ещё не дочитывали; `true` — дочитан сейчас и успешно. */
        suspend fun refreshOnce(): Boolean {
            if (refreshed != null) return false
            return refresh().also { refreshed = it } is ApiResult.Success
        }

        suspend fun <T> resolve(read: (Vocabulary) -> T): Resolution<T> {
            val miss = try {
                return Resolution.Resolved(read(store.snapshot()))
            } catch (missed: VocabularyMiss) {
                missed
            }
            refreshOnce()
            return when (val result = checkNotNull(refreshed)) {
                is ApiResult.Failure -> Resolution.Unresolved(miss, result.failure)
                is ApiResult.Success -> try {
                    Resolution.Resolved(read(store.snapshot()))
                } catch (missed: VocabularyMiss) {
                    Resolution.Unresolved(missed, failure = null)
                }
            }
        }
    }

    /** Чем кончился разбор: объект — или промах, который дочитать не удалось, и почему. */
    sealed interface Resolution<out T> {

        data class Resolved<T>(val value: T) : Resolution<T>

        /** [failure] — почему словарь не дочитался; `null` — дочитался, а записи в нём всё равно нет. */
        data class Unresolved(val miss: VocabularyMiss, val failure: ApiFailure?) : Resolution<Nothing> {

            /** Почему разбор отложен — словами для журнала операции. */
            val reason: String get() = "словарь не знает ${miss.message}"
        }
    }
}
