package com.kert0n.medapp.queue

import com.kert0n.medapp.domain.course.Course
import com.kert0n.medapp.domain.medkit.MedKitRef
import com.kert0n.medapp.domain.pack.Package
import com.kert0n.medapp.domain.pack.PackageSharedFacts
import com.kert0n.medapp.domain.value.Dose
import com.kert0n.medapp.domain.value.Quantity
import com.kert0n.medapp.queue.intake.IntakeAccounting
import com.kert0n.medapp.queue.intake.IntakeSyncState
import com.kert0n.medapp.queue.medkit.MedKitSyncCommand
import com.kert0n.medapp.queue.pack.PackageSyncCommand
import com.kert0n.medapp.queue.pack.claimChangesSince
import java.time.Instant
import javax.inject.Inject
import kotlin.uuid.Uuid

/**
 * Держит пару «изменение и его команда»: изменение проходит через хранилище, команда встаёт в
 * очередь — одной транзакцией, которую служба открывает сама (PLAN F5). Репозитории про очередь
 * не знают, а сервер узнаёт обо всём, что случилось, когда есть связь: поставленную команду
 * забирает [QueueOutbox] после коммита, и просить об отправке никому не нужно.
 *
 * Местной аптечке команд не ставится: на сервере её нет, и везти туда нечего (PLAN E1). Полке,
 * которая к серверу только едет, ставится **объявление** — то, чем она и её содержимое станут ему
 * известны, — и то, что этим объявлением держится: создание коробки вместе с бронью её выделения
 * (PLAN E5, E6). Изменение известного туда не ставится: менять у сервера нечего, и учтено оно уже
 * местно.
 *
 * **Что сказать реестру о переходе, решает служба же**: сценарий называет переход, а служба
 * отвечает поручениями и тем, как он ляжет ([Laying], [Spending], [Carrying], [Clearing]) — сразу
 * или ждать сервера. Отвечает ли полка серверу и знает ли он коробку, сценарий не спрашивает.
 */
class QueueService @Inject constructor(
    private val transactions: Transactions,
    private val storage: QueueStorage
) {

    /**
     * [change] — запись изменения; `false` значит «писать было некуда», и команды тогда тоже не
     * ставятся: расход, которого не записали, серверу не везут. Ждать отправки человеку незачем —
     * изменение уже записано, и от сети его исход не зависит (PLAN E4).
     */
    suspend fun change(
        medKit: MedKitRef,
        commands: List<QueuedCommand>,
        at: Instant,
        change: suspend () -> Boolean
    ): Boolean = transactions.run {
        val applied = change()
        if (applied) {
            // Полка изменения и есть полка его команд: порядок внутри неё держит очередь (PLAN E3).
            for (command in deliverableTo(medKit, commands)) storage.enqueue(command, medKit.id, at)
        }
        applied
    }

    /**
     * Что из поставленного уезжает этой полке. Полке, отвечающей серверу, — всё. Полке, которая к
     * нему только едет, — объявления и то, что за ними стоит: бронь выделения держится своим
     * созданием и без него ничего не значит, а порядок внутри пары уже назван зависимостью.
     */
    private fun deliverableTo(medKit: MedKitRef, commands: List<QueuedCommand>): List<QueuedCommand> {
        if (medKit.answersToServer) return commands
        if (!medKit.acceptsCommands) return emptyList()
        val announced = HashSet<Uuid>()
        return commands.filter { queued ->
            val announces = queued.command.announcesToServer || queued.dependsOn.any { it in announced }
            if (announces) announced += queued.id
            announces
        }
    }

    /**
     * Знает ли реестр коробку. Вопрос из двух половин, и задаётся он одним местом на всех: её
     * полка отвечает серверу (D2), и сервер её уже создал — есть серверная версия (PLAN E6).
     */
    suspend fun onServer(packageId: Uuid): Boolean =
        storage.knownPackage(packageId)?.let { it.pack.medKit.answersToServer && it.sync.isOnServer } ?: false

    /** Новая коробка: у общей полки она ждёт своего создания, у едущей к серверу — едет с ней. */
    fun adding(pkg: Package): Laying {
        val create = errand(PackageSyncCommand.Create(pkg.id, pkg.medKit.id))
        return if (pkg.medKit.answersToServer) Laying.Awaiting(listOf(create)) else Laying.Now(listOf(create))
    }

    /** Пересчёт или списание: у коробки, которую знает сервер, остаток правит он (PLAN E3). */
    suspend fun adjustment(pkg: Package, seen: Quantity, actual: Quantity): Laying =
        if (onServer(pkg.id)) Laying.Awaiting(listOf(errand(PackageSyncCommand.CorrectStock(pkg.id, seen = seen, actual = actual))))
        else Laying.Now()

    /**
     * Правка сведений. Серверу едет только общая часть, и только у коробки, которую он знает;
     * стереть форму реестр не умеет (PLAN D3).
     */
    suspend fun description(pkg: Package, after: PackageSharedFacts): Laying {
        val before = pkg.facts.shared
        if (before == after || !onServer(pkg.id)) return Laying.Now()
        if (before.form != null && after.form == null) return Laying.Refused
        return Laying.Awaiting(listOf(errand(PackageSyncCommand.Describe(pkg.id, before, after))))
    }

    /** Убрать коробку: с общей полки её убирает сервер. */
    fun removal(pkg: Package): Laying =
        if (pkg.medKit.answersToServer) Laying.Awaiting(listOf(errand(PackageSyncCommand.Delete(pkg.id)))) else Laying.Now()

    /** Перенести коробку на полку [to]. */
    fun carrying(pkg: Package, to: MedKitRef): Carrying {
        val from = pkg.medKit
        return when {
            from.answersToServer && !to.answersToServer -> Carrying.Home(withdrawal(pkg))
            // Переставляют с полки, где коробка лежит: там её команды и ждут своей очереди.
            from.answersToServer -> Carrying.ByServer(Laying.Awaiting(listOf(errand(PackageSyncCommand.Move(pkg.id, to.id)))))
            to.answersToServer -> Carrying.Announced
            else -> Carrying.Local
        }
    }

    /** Сервер забывает коробку, которую уносят с общей полки на свою. */
    fun withdrawal(pkg: Package): Laying.Awaiting =
        Laying.Awaiting(listOf(errand(PackageSyncCommand.Withdraw(pkg.id, pkg.medKit.id, pkg.quantity))))

    /**
     * Коробка становится известна серверу на полке [to]: созданием и бронью выделения [claim],
     * которая держится этим созданием (PLAN E5, E6). [originSurvives] — полка, откуда коробку
     * принесли, остаётся, и не вышло — коробка вернётся туда.
     */
    fun announcement(
        pkg: Package,
        to: MedKitRef,
        claim: Quantity?,
        after: Set<Uuid> = emptySet(),
        originSurvives: Boolean = true
    ): Laying.Awaiting {
        val create = QueuedCommand(
            Uuid.random(),
            PackageSyncCommand.Create(pkg.id, to.id, pkg.medKit.id.takeIf { originSurvives && it != to.id }),
            dependsOn = after
        )
        val claimed = claim?.takeUnless { it.isZero }
            ?.let { QueuedCommand(Uuid.random(), PackageSyncCommand.SetClaim(pkg.id, it), dependsOn = setOf(create.id)) }
        return Laying.Awaiting(listOfNotNull(create, claimed))
    }

    /** Публикация полки: сама полка, а за ней — объявление каждой её коробки с бронью выделения. */
    fun publication(publishing: MedKitRef, contents: Map<Package, Quantity?>): Publication {
        val publish = errand(MedKitSyncCommand.Publish(publishing.id))
        return Publication(
            publish,
            contents.mapValues { (pkg, claim) -> announcement(pkg, publishing, claim, after = setOf(publish.id)) }
        )
    }

    class Publication(val publish: QueuedCommand, val announcements: Map<Package, Laying.Awaiting>) {
        val errands: List<QueuedCommand> get() = listOf(publish) + announcements.values.flatMap { it.errands }
    }

    /** Выйти из общей полки: коробки остаются остальным, а у нас ждут ответа под этим поручением. */
    fun leaving(medKit: MedKitRef): Laying.Awaiting = Laying.Awaiting(listOf(errand(MedKitSyncCommand.Leave(medKit.id))))

    /** Убрать полку, перенеся содержимое на [target] или выбросив его. */
    fun clearing(medKit: MedKitRef, target: MedKitRef?): Clearing = when {
        medKit.answersToServer && target != null && !target.answersToServer -> Clearing.Home(target)
        medKit.answersToServer -> Clearing.ByServer(Laying.Awaiting(listOf(errand(MedKitSyncCommand.Delete(medKit.id, target?.id)))))
        else -> Clearing.Local
    }

    /** Унести содержимое общей полки на свою: сначала каждая коробка забывается сервером, потом полка. */
    fun homecoming(medKit: MedKitRef, contents: List<Package>): Homecoming {
        val withdrawals = contents.associateWith { withdrawal(it) }
        val delete = QueuedCommand(
            Uuid.random(),
            MedKitSyncCommand.Delete(medKit.id),
            dependsOn = withdrawals.values.mapTo(HashSet()) { it.by }
        )
        return Homecoming(withdrawals, delete)
    }

    class Homecoming(val withdrawals: Map<Package, Laying.Awaiting>, val delete: QueuedCommand) {
        val errands: List<QueuedCommand> get() = withdrawals.values.flatMap { it.errands } + delete
    }

    /** Где ляжет расход из коробки [pkg]. */
    suspend fun spending(pkg: Package): Spending = if (onServer(pkg.id)) Spending.REMOTE else Spending.LOCAL

    /**
     * Расход приёма [intakeId] и то, что о нём знает очередь. Местный расход не везут: сервер о
     * коробке не знает — расскажет о ней её создание (E6). Общий уезжает с бронью после расхода
     * [claimAfter]; кончилась бронь — снимается зависимым от расхода поручением (PLAN D5, E2).
     */
    fun consumption(spending: Spending, intakeId: Uuid, pkg: Package, amount: Dose, claimAfter: Quantity?): Consumption {
        if (spending == Spending.LOCAL) return Consumption(emptyList(), IntakeSyncState(intakeId, IntakeAccounting.LOCAL_APPLIED))
        val consume = errand(PackageSyncCommand.Consume(pkg.id, amount, intakeId, claimAfter))
        val release = QueuedCommand(Uuid.random(), PackageSyncCommand.ReleaseClaim(pkg.id), dependsOn = setOf(consume.id))
            .takeIf { claimAfter?.isZero == true }
        return Consumption(listOfNotNull(consume, release), IntakeSyncState(intakeId, IntakeAccounting.PENDING, consume.id))
    }

    class Consumption(val errands: List<QueuedCommand>, val sync: IntakeSyncState)

    /**
     * Брони, изменившиеся между двумя состояниями лечения, — по коробке. Правило разницы одно
     * ([claimChangesSince]); [except] — коробка, чьё снятие уже уехало зависимым от расхода.
     */
    fun claims(before: Course, after: Course, except: Uuid? = null): Map<Uuid, List<QueuedCommand>> =
        after.claimChangesSince(before)
            .filter { it.packageId != except }
            .groupBy({ it.packageId }, { errand(it) })

    private fun errand(command: SyncCommand) = QueuedCommand(Uuid.random(), command)
}
