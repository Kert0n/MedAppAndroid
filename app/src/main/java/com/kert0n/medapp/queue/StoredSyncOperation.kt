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

    data class Readable(val operation: SyncOperation) : StoredSyncOperation {
        override val id: Uuid get() = operation.id
    }

    /** Снимок словаря старее строки: дочитать словарь — и строка прочитается. Решать нечего. */
    data class Stale(override val id: Uuid, val miss: VocabularyMiss) : StoredSyncOperation

    /**
     * Чужая версия payload, неизвестный вид, повреждённые поля: чтением не лечится. [reason] —
     * словами, для журнала и для экрана.
     */
    data class Unreadable(override val id: Uuid, val reason: String) : StoredSyncOperation
}
