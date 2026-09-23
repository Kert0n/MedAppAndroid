package com.kert0n.medapp.queue

import com.kert0n.medapp.domain.Unavailability
import com.kert0n.medapp.domain.medkit.InvitationKey
import com.kert0n.medapp.queue.pack.PackageSnapshot
import kotlin.uuid.Uuid

/**
 * Чтение общего реестра — сводка, а не поручение: что нам доступно, одна полка, одна коробка,
 * вступление по ключу (PLAN E4). Порт объявлен очередью на её языке; исполняет сеть — читает,
 * собирает коробки в домен одним заходом словаря и переводит отказы в [Unavailability]. Какие
 * полки ответ заводит, решает очередь: она знает, что было у нас до чтения.
 */
interface Register {

    /**
     * Утверждение о целом: все полки, которые нам видны, и всё, что на них лежит. [arriving] —
     * какие из названных полок этот же ответ заводит у нас: коробкам на них есть куда лечь.
     */
    suspend fun whole(arriving: (named: Set<Uuid>) -> Set<Uuid>): Whole

    /** Список полок без содержимого: где мы ещё есть и сколько в них людей. */
    suspend fun shelves(): Shelves

    /** Одна коробка. */
    suspend fun pack(packageId: Uuid): Pack

    /** Вступление по ключу: сервер отвечает полкой с содержимым. */
    suspend fun join(key: InvitationKey, arriving: (medKitId: Uuid) -> Set<Uuid>): Joined

    /** Прочитанные полки: [participants] — сколько людей в каждой, [packages] — собранные коробки. */
    class Read(
        val participants: Map<Uuid, Long>,
        packages: List<PackageSnapshot>,
        namedPackages: Set<Uuid>,
        arrived: Set<Uuid>,
        skipped: List<String>
    ) {
        val packages: List<PackageSnapshot> = packages.toList()

        /** Названные сервером коробки — и собранные, и нет: не собранная не пропала. */
        val namedPackages: Set<Uuid> = namedPackages.toSet()

        val arrived: Set<Uuid> = arrived.toSet()

        /** Что не легло и почему — словами для журнала. */
        val skipped: List<String> = skipped.toList()
    }

    sealed interface Whole {
        class Answered(val read: Read) : Whole
        data class Refused(val reason: Unavailability) : Whole
    }

    sealed interface Shelves {
        data class Answered(val participants: Map<Uuid, Long>) : Shelves
        data class Refused(val reason: Unavailability) : Shelves
    }

    sealed interface Pack {
        data class Answered(val snapshot: PackageSnapshot) : Pack

        /** Коробки больше нет: выбросили, кончилась или унесли туда, где нас нет. */
        data object Gone : Pack

        /** Коробка на полке, которой у нас нет. */
        data object Elsewhere : Pack

        /** Собрать нечем; [stop] — словарь не дочитался из-за связи. */
        data class Unresolved(val stop: Boolean) : Pack

        data class Refused(val reason: Unavailability) : Pack
    }

    sealed interface Joined {
        class Answered(val medKitId: Uuid, val read: Read) : Joined
        data object AlreadyMember : Joined
        data object InvitationInvalid : Joined
        data object OutcomeUnknown : Joined
        data class Refused(val reason: Unavailability) : Joined
    }
}
