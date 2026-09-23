package com.kert0n.medapp.queue

import com.kert0n.medapp.network.value.VocabularyMiss
import kotlin.uuid.Uuid

/**
 * Что дала попытка собрать операцию из строки очереди. Случая три, и с каждым делают своё:
 * собранную отправляют; строку, которой не хватило словаря, дочитывают и собирают снова — человек
 * ей не нужен; строку, которую чтением не вылечить, работник пропускает и называет — очередь не
 * роняется из-за одной строки, но и молча её не теряет, а решать о ней приходится человеку
 * (PLAN F4, D8).
 *
 * Тождество строки известно во всех случаях: [id] хранится колонкой и разбора не требует, а без
 * него о повреждённой операции нечего было бы сказать.
 */
sealed interface StoredSyncOperation {

    val id: Uuid

    /**
     * Состояние отправки — у всех трёх: оно лежит в колонках и читается без словаря, поэтому
     * строка, которую нечем прочитать, закрывается тем же переходом, что и собранная (C1
     * «Переходы операции — у типа»).
     */
    val state: SyncOperationState

    /**
     * Ждёт решения человека: отвергнутое сервером или нечитаемое само не разрешится (PLAN D8,
     * H3 №28). Строка, которой не хватило словаря, — не повод: её дочитает работник.
     */
    val needsDecision: Boolean
        get() = when (this) {
            is Readable -> operation.status == SyncOperationStatus.REFUSED
            is Stale -> false
            is Unreadable -> true
        }

    data class Readable(val operation: SyncOperation) : StoredSyncOperation {
        override val id: Uuid get() = operation.id
        override val state: SyncOperationState get() = operation.state
    }

    /** Снимок словаря старее строки: дочитать словарь — и строка прочитается. Решать нечего. */
    data class Stale(override val id: Uuid, val miss: VocabularyMiss, override val state: SyncOperationState) : StoredSyncOperation

    /**
     * Чужая версия payload, неизвестный вид, повреждённые поля: чтением не лечится. [reason] —
     * словами, для журнала и для экрана.
     */
    data class Unreadable(override val id: Uuid, val reason: String, override val state: SyncOperationState) : StoredSyncOperation
}

/**
 * Чем закрыть незакрытую операцию, когда полки у сервера для нас больше нет — учётку заменили
 * (PLAN G2): «отправлять некуда», тем же переходом, что у 404. У собранной — со следствиями её
 * команды: унесённая домой коробка остаётся у человека. У несобранной следствий нет — дочитывать
 * словарь ради строки, которой некуда ехать, незачем.
 */
fun StoredSyncOperation.abandoned(): Settlement = when (this) {
    is StoredSyncOperation.Readable -> Delivery.AccessLost.settlement(operation.command)
    is StoredSyncOperation.Stale, is StoredSyncOperation.Unreadable -> Settlement(Settlement.Transition.Close.AccessLost)
}
