package com.kert0n.medapp.domain.medkit

import com.kert0n.medapp.domain.value.requireOptionalText
import com.kert0n.medapp.domain.value.requireText
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * Аптечка — место хранения упаковок. Сущность: переименованная аптечка — та же аптечка, равенство
 * по [id]. Название и место хранения остаются на устройстве; сервер знает только существование,
 * участие и число участников (PLAN C0, E5).
 */
class MedKit(
    val id: Uuid,                   // придуман клиентом; он же серверный
    val name: String,               // 1..200, только на устройстве
    val location: String?,          // ≤300, место хранения; только на устройстве
    val publication: Publication,
    val participantCount: Long,     // 1 у локальной, иначе userCount с сервера
    val createdAt: Instant,
    val status: MedKitStatus = MedKitStatus.ACTIVE
) {

    init {
        requireText(name, NAME_MAX_LENGTH, "MedKit.name")
        requireOptionalText(location, LOCATION_MAX_LENGTH, "MedKit.location")
        require(participantCount >= 1) { "участник всегда есть хотя бы один — я сам" }
        if (publication == Publication.LOCAL) {
            require(participantCount == 1L) { "у локальной аптечки других участников нет" }
        }
    }

    val isShared: Boolean get() = participantCount > 1

    /**
     * Кому отвечает всё, что в ней лежит: опубликованная аптечка стоит у сервера, и изменения её
     * пачек едут командами; местная и ещё не заведённая сервером существуют только у нас, и
     * изменения их пачек записаны, как только записаны (PLAN E1, E5). Одно место для этого вопроса
     * на всех, кто его задаёт.
     */
    val answersToServer: Boolean get() = answersToServer(publication, status)

    /** Есть ли куда доставлять её команды: она у сервера или едет к нему (PLAN E5). */
    val acceptsCommands: Boolean get() = acceptsCommands(publication, status)

    /** Как аптечку видит чужой агрегат: тождество, публикация и пометка, без переходов. */
    val ref: MedKitRef get() = MedKitRef(id, publication, status)

    /**
     * Как аптечку видит экран: величина, наружу уходит она, а не сущность. Что на полке лежит,
     * знает не она, а тот, кто читал коробки, — приносит аргументом (PLAN D2).
     */
    fun projection(contents: MedKitContents): MedKitProjection = MedKitProjection(
        id = id,
        name = name,
        location = location,
        publication = publication,
        participantCount = participantCount,
        createdAt = createdAt,
        isShared = isShared,
        acceptsInvitations = acceptsInvitations,
        contents = contents,
        status = status
    )

    /**
     * Отдельно от [isShared]: аптечка, из которой ушли все, кроме меня, остаётся серверной и
     * приглашения выдаёт. Приглашать в местную некуда — на сервере её нет (PLAN D2).
     *
     * Отдельно и от [answersToServer]: **половины аптечки не бывает**. Пока публикация не доведена
     * до конца — полка уехала, а часть её коробок ещё нет, — приглашённый увидел бы не ту полку,
     * поэтому приглашений она не выдаёт. При связи это доли секунды (PLAN E1, E5).
     */
    val acceptsInvitations: Boolean get() = publication == Publication.PUBLISHED && status == MedKitStatus.ACTIVE

    /**
     * Сервер завёл аптечку: теперь она существует и у него. Обратной дороги нет (E5). Пометка при
     * этом остаётся: решение «сделать полку общей» доведено не тогда, когда согласился сервер, а
     * когда уехало и её содержимое, — снимает пометку [settled].
     */
    fun published(): MedKit {
        requireLocal()
        check(status == MedKitStatus.PUBLISHING) { "на сервере оказывается публикуемая аптечка, а не $status" }
        return MedKit(
            id = id,
            name = name,
            location = location,
            publication = Publication.PUBLISHED,
            participantCount = participantCount,
            createdAt = createdAt,
            status = status
        )
    }

    /** Меняет личные сведения, сохраняя тождество, публикацию и пометку аптечки. */
    fun describe(name: String, location: String?): MedKit {
        check(status.allowsUse) { "аптечка помечена ($status): её не правят до ответа полки" }
        return changed(name = name, location = location)
    }

    /**
     * Человек решил сделать полку общей. Дальше это везёт очередь: сама полка — своей командой,
     * содержимое — обычными командами пачек. Одно решение об аптечке за раз: помеченную второй раз
     * не публикуют и не убирают (PLAN E5).
     */
    fun markPublishing(): MedKit {
        requireLocal()
        requireDecidable()
        return changed(status = MedKitStatus.PUBLISHING)
    }

    /**
     * Человек убирает аптечку из своего списка — вынося содержимое, выбрасывая его или оставляя
     * остальным. До ответа аптечка видна, но выведена из оборота (PLAN E6).
     */
    fun markRemoving(): MedKit {
        requireDecidable()
        return changed(status = MedKitStatus.REMOVING)
    }

    /** На сервер аптечка попадает один раз: обратной дороги нет (E5). */
    private fun requireLocal() {
        check(publication == Publication.LOCAL) { "аптечка уже на сервере" }
    }

    /** Одно решение об аптечке за раз: помеченную второй раз не публикуют и не убирают (PLAN E5). */
    private fun requireDecidable() {
        check(status.allowsDecision) { "об аптечке уже принято решение: $status" }
    }

    /** Полка ответила, а решать больше нечего: пометка снимается. */
    fun settled(): MedKit = changed(status = MedKitStatus.ACTIVE)

    private fun changed(
        name: String = this.name,
        location: String? = this.location,
        status: MedKitStatus = this.status
    ): MedKit = MedKit(
        id = id,
        name = name,
        location = location,
        publication = publication,
        participantCount = participantCount,
        createdAt = createdAt,
        status = status
    )

    /** Тождество — [id]: переименованная аптечка остаётся той же аптечкой. */
    override fun equals(other: Any?): Boolean =
        this === other || (other is MedKit && other.id == id)

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "MedKit(id=$id, name=$name, publication=$publication)"

    /**
     * Где аптечка существует. Состояний два: на сервере она есть либо её там нет. Незавершённое
     * решение сюда не попадает — оно живёт пометкой ([MedKitStatus]), и потому «начали
     * публиковать» третьим состоянием не становится (PLAN D2, E5).
     */
    enum class Publication {
        LOCAL,        // на сервере не существует
        PUBLISHED     // существует на сервере
    }

    companion object {
        const val NAME_MAX_LENGTH = 200
        const val LOCATION_MAX_LENGTH = 300

        /**
         * Кому отвечает лежащее в аптечке. Серверу отвечает содержимое только той полки, которую
         * сервер завёл: пока он о ней не слышал, спорить не с кем, версий нет, и изменения её
         * коробок записываются числом сразу, как в местной (PLAN E1, E5).
         *
         * Публикуемая полка сюда не входит — и это решение владельца 2026-09-13: прежде она
         * считалась отвечающей серверу, и расход, поставленный командой на полку, которой у
         * сервера так и не появилось, уезжал сам, получал 404 и кончал коробку утратой доступа.
         */
        fun answersToServer(publication: Publication, status: MedKitStatus): Boolean =
            publication == Publication.PUBLISHED

        /**
         * Есть ли куда доставлять команды этой полки: она либо уже у сервера, либо едет к нему по
         * решению о публикации. Вопрос очереди, а не учёта: до ответа на публикацию к серверу едет
         * только то, чем полка и её содержимое станут ему известны (PLAN E5).
         */
        fun acceptsCommands(publication: Publication, status: MedKitStatus): Boolean =
            publication == Publication.PUBLISHED || status == MedKitStatus.PUBLISHING
    }
}
