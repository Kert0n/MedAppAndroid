package com.kert0n.medapp.fixture

import com.kert0n.medapp.domain.value.Vocabulary
import com.kert0n.medapp.queue.Transactions
import com.kert0n.medapp.storage.value.VocabularyStorageRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

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
