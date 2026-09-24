package com.kert0n.medapp.fixture

import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.medkit.MedKitProjection
import com.kert0n.medapp.domain.medkit.MedKitStatus
import com.kert0n.medapp.domain.pack.PackageProjection
import com.kert0n.medapp.domain.value.Vocabulary
import com.kert0n.medapp.feature.packages.PackageReadings
import com.kert0n.medapp.queue.Transactions
import com.kert0n.medapp.storage.medkit.MedKitStorageRepository
import com.kert0n.medapp.storage.value.VocabularyStorageRepository
import java.time.Instant
import java.time.LocalDate
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow

/**
 * Работа, которую проверка держит на паузе.
 *
 * Второе нажатие иначе не поймать: в подделках запись мгновенна, и второе нажатие приходит,
 * когда первое уже кончилось, — замок ему не нужен, и его отсутствие не видно. Настоящие чтение
 * и запись занимают время, и окно между нажатием и замком существует ровно столько.
 *
 * Считаются не все обращения, а те, что пришли **в закрытую дверь**: они и есть попытки начать
 * второе дело, пока идёт первое. То, что случится после [release], — уже продолжение первого,
 * включая вложенные транзакции сценариев.
 */
class Held {

    private val open = MutableStateFlow(false)

    /** Сколько работ пришло, пока дверь была закрыта. */
    var waiting: Int = 0
        private set

    suspend fun pass() {
        if (!open.value) waiting++
        open.first { it }
    }

    /** Закрыть дверь заново и начать счёт с нуля: у проверки бывает два ожидания подряд. */
    fun hold() {
        open.value = false
        waiting = 0
    }

    fun release() {
        open.value = true
    }
}

/** Транзакция, которую проверка держит: [Held.waiting] называет, сколько дел начали. */
class HeldTransactions(val door: Held = Held()) : Transactions {

    override suspend fun <T> run(block: suspend () -> T): T {
        door.pass()
        return block()
    }
}

/** Словарь, чтение которого проверка держит: между нажатием и записью стоит именно оно. */
class HeldVocabulary(
    val door: Held = Held(),
    private val real: VocabularyStorageRepository = FakeVocabulary()
) : VocabularyStorageRepository by real {

    override suspend fun snapshot(): Vocabulary {
        door.pass()
        return real.snapshot()
    }
}

/** Хранилище, первое чтение которого проверка держит: до него экран ещё ничего не знает. */
class HeldPackages(
    private val real: PackageReadings,
    val door: Held = Held()
) : PackageReadings by real {

    override fun observe(id: Uuid): Flow<PackageProjection?> = flow {
        door.pass()
        emitAll(real.observe(id))
    }
}

/**
 * Полки, первое чтение которых проверка держит: до него карточка ещё не знает места коробки.
 *
 * Отвечает **только** на тот вопрос, о котором проверка: остальное — крик с именем метода, иначе
 * карточка, сменившая способ читать полку, зеленела бы на чужом ответе.
 */
class HeldMedKits(
    private val real: MedKitStorageRepository,
    val door: Held = Held()
) : MedKitStorageRepository {

    override fun observe(id: Uuid, today: LocalDate): Flow<MedKitProjection?> = flow {
        door.pass()
        emitAll(real.observe(id, today))
    }

    override fun observeAll(today: LocalDate): Flow<List<MedKitProjection>> = flow { notAsked("observeAll") }

    override suspend fun find(id: Uuid): MedKit? = notAsked("find")

    override fun observeSyncedAt(id: Uuid): Flow<Instant?> = flow { notAsked("observeSyncedAt") }

    override suspend fun add(medKit: MedKit) = notAsked("add")

    override suspend fun describe(medKitId: Uuid, name: String, location: String?): Boolean = notAsked("describe")

    override suspend fun delete(id: Uuid): Boolean = notAsked("delete")

    override suspend fun mark(medKitId: Uuid, status: MedKitStatus): Boolean = notAsked("mark")

    override suspend fun applyServerParticipants(id: Uuid, participantCount: Long, syncedAt: Instant) =
        notAsked("applyServerParticipants")

    override suspend fun published(): List<Uuid> = notAsked("published")
    override suspend fun loseAccess(medKitId: Uuid, at: Instant) = notAsked("loseAccess")

    private fun notAsked(method: String): Nothing =
        error("карточка читает место одним чтением полки, а спросила «$method» — модель разъехалась с проверкой")
}
