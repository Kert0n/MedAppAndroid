package com.kert0n.medapp.queue

import com.kert0n.medapp.domain.Unavailability
import com.kert0n.medapp.domain.medkit.InvitationKey
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.uuid.Uuid

/**
 * Читает у сервера, что нам доступно, и кладёт это к себе (PLAN E4). Дешёвой сверки не существует —
 * состав полки и число участников чужой расход не двигают (B6), — поэтому спрашивается всё сразу:
 * `GET /v1/users/me` отвечает не списком изменений, а **утверждением о целом**: вот полки, которые
 * я вижу, и вот всё, что на них лежит. Поэтому полка, которой у нас нет, в нём — штатный случай:
 * нас позвали, а ответ на вступление потерялся, — и заводится она здесь же.
 *
 * Вступление — тот же снимок, только одной полки: сервер отвечает на него полкой с содержимым, и
 * разрешается и ложится она тем же путём ([join]). Утверждения о целом в нём нет, и пропажи оно не
 * объявляет.
 *
 * Сеть между транзакциями, а не внутри (F5): сначала реестр читается и собирается в домен
 * ([Register]), и только собранное ложится одной записью. Промах словаря дочитывается один раз за
 * чтение; строка, которой не помог и свежий словарь, пропускается с названной причиной, а не
 * роняет всё чтение. Какие полки ответ заводит и что пропало, решается здесь — по тому, что было
 * у нас до чтения.
 */
@Singleton
class SnapshotApplier @Inject constructor(
    private val register: Register,
    private val storage: SnapshotStorage,
    private val clock: Clock
) {

    suspend fun refresh(): Outcome {
        val at = clock.instant()
        // Спрашивается до сети: что человек заведёт или уберёт, пока снимок летит, ответ не знает.
        val knew = storage.serverKnows()
        // Полку, которой у нас не было, снимок заводит: сервер назвал её нашей — вступили, а ответ
        // на вступление потерялся. Была и пропала, пока снимок летел, — её убрали, не заводим.
        val read = when (val answer = register.whole { named -> named - knew.heldMedKits }) {
            is Register.Whole.Answered -> answer.read
            is Register.Whole.Refused -> return Outcome.Refused(answer.reason)
        }
        // Чего в снимке нет, к тому доступа больше нет. Считается это по названным номерам, а не
        // по собранным: коробка, которую не удалось собрать, названа сервером и не пропала.
        val snapshot = ServerSnapshot(
            participants = read.participants,
            packages = read.packages,
            goneMedKits = knew.medKits - read.participants.keys,
            gonePackages = knew.packages - read.namedPackages,
            arrivedMedKits = read.arrived,
            heldPackages = knew.heldPackages
        )
        storage.lay(snapshot, at)
        return Outcome.Applied(medKits = read.participants.size, packages = read.packages.size, skipped = read.skipped)
    }

    /**
     * Вступление по ключу приглашения: сервер отвечает полкой с содержимым, и она ложится одной
     * записью — полка «Общей аптечкой» (C0), коробки со своими записями. Что делать, когда ответа
     * нет или сервер говорит «уже вступили», решает сценарий: механизм отвечает только тем, что
     * услышал.
     */
    suspend fun join(key: InvitationKey): Joining {
        val at = clock.instant()
        val knew = storage.serverKnows()
        val (medKitId, read) = when (val answer = register.join(key) { joined -> setOf(joined) - knew.heldMedKits }) {
            is Register.Joined.Answered -> answer.medKitId to answer.read
            Register.Joined.AlreadyMember -> return Joining.AlreadyMember
            Register.Joined.InvitationInvalid -> return Joining.InvitationInvalid
            Register.Joined.OutcomeUnknown -> return Joining.OutcomeUnknown
            is Register.Joined.Refused -> return Joining.Refused(answer.reason)
        }
        val snapshot = ServerSnapshot(
            participants = read.participants,
            packages = read.packages,
            goneMedKits = emptySet(),
            gonePackages = emptySet(),
            arrivedMedKits = read.arrived,
            heldPackages = knew.heldPackages
        )
        storage.lay(snapshot, at)
        return Joining.Joined(medKitId)
    }

    /**
     * Чем кончилось чтение. Различает поведение экрана состояния синхронизации: прочитали — видно
     * время последнего успешного обновления, и [Applied.skipped] говорит, что легло не всё; не
     * прочитали — названа причина, а кэш остаётся прежним (PLAN E4, H3 №28).
     */
    sealed interface Outcome {

        data class Applied(val medKits: Int, val packages: Int, val skipped: List<String>) : Outcome

        data class Refused(val reason: Unavailability) : Outcome
    }

    /** Что сервер ответил на вступление — ровно те случаи, которые сценарий разбирает по-разному. */
    sealed interface Joining {

        /** Вступили: полка [medKitId] с содержимым уже лежит у нас. */
        data class Joined(val medKitId: Uuid) : Joining

        /** 409: мы уже в этой полке — возможно, прошлый ответ потерялся. Номера полки сервер не даёт. */
        data object AlreadyMember : Joining

        /** Ключ неизвестен, истёк или пригласивший вышел: для нас это одно (B6). */
        data object InvitationInvalid : Joining

        /** Запрос уходил, а ответа нет: вступление могло состояться. */
        data object OutcomeUnknown : Joining

        /** Сервера сейчас нет — связи, ответа или нашей учётки. */
        data class Refused(val reason: Unavailability) : Joining
    }
}
