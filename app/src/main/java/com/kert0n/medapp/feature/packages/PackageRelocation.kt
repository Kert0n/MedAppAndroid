package com.kert0n.medapp.feature.packages

import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.medkit.MedKitRef
import com.kert0n.medapp.domain.pack.Package
import com.kert0n.medapp.domain.pack.PackageStatus
import com.kert0n.medapp.queue.QueueService
import com.kert0n.medapp.queue.QueuedCommand
import com.kert0n.medapp.queue.Transactions
import com.kert0n.medapp.queue.pack.PackageSyncCommand
import com.kert0n.medapp.storage.course.CourseStorageRepository
import com.kert0n.medapp.storage.medkit.MedKitStorageRepository
import com.kert0n.medapp.storage.pack.PackageAdjustment
import com.kert0n.medapp.storage.pack.PackageStorageRepository
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import kotlin.uuid.Uuid

/**
 * Человек переставил коробку на другую полку (PLAN E6). Коробка та же, запись та же, курс её не
 * теряет: к обеим полкам допущен тот же человек. Меняется только место — доменным `moveTo`. Что
 * ещё нужно сделать, решает граница публикации:
 *
 * - местная → местная: ничего;
 * - общая → общая: серверу — `Move`, и коробка ждёт его ответа на прежней полке: новое место
 *   принесёт снимок. Свою бронь сервер сохраняет, потому что мы видим цель;
 * - местная → общая: коробка становится известна серверу — `Create` в целевой аптечке, а
 *   выделение курса, если оно есть, едет следом бронью `SetClaim`, иначе на сервере его бы не
 *   было. До ответа обвязка пуста и броней нет — первое подтверждённое число даст снимок ответа
 *   (E1); расход, поставленный позже, идёт после `Create` по номеру;
 * - общая → местная: «унёс домой». Коробка сразу на моей полке, брони сняты, а серверу —
 *   `Withdraw`: снять её по версии. Для остальных она исчезает, публиковать мою полку незачем.
 *   Отказ сервера возвращает коробку на полку, откуда её взяли.
 *
 * Изменение, ушедшее серверу, помечает коробку статусом `CHANGING`: пользоваться ею можно, а
 * снимает пометку закрытие последней её команды (PLAN E1).
 */
class PackageRelocation @Inject constructor(
    private val packages: PackageStorageRepository,
    private val medKits: MedKitStorageRepository,
    private val courses: CourseStorageRepository,
    private val queue: QueueService,
    private val transactions: Transactions,
    private val clock: Clock
) {

    suspend fun move(packageId: Uuid, targetMedKitId: Uuid): Outcome = transactions.run {
        val pkg = packages.find(packageId) ?: return@run Outcome.GONE
        if (!pkg.status.allowsUse) return@run Outcome.UNUSABLE
        val target = medKits.find(targetMedKitId) ?: return@run Outcome.TARGET_GONE
        if (target.id == pkg.medKit.id) return@run Outcome.TARGET_IS_THE_SAME
        // В полку, о которой уже принято решение, не кладут: она вот-вот уйдёт, и коробка ушла бы
        // с ней, ничего человеку не сказав (PLAN E1, E6).
        if (!target.status.allowsUse) return@run Outcome.TARGET_BUSY
        relocate(pkg, target, clock.instant())
    }

    /**
     * Шаг внутри чужой транзакции: одна коробка на другую полку со всем, что нужно серверу.
     * Аптечка, которую разбирают целиком, зовёт его по каждой местной коробке; свою общую она
     * переставляет одной командой аптечки и зовёт [place].
     */
    internal suspend fun relocate(
        pkg: Package,
        target: MedKit,
        at: Instant,
        originSurvives: Boolean = true
    ): Outcome {
        val from = pkg.medKit
        val to = target.ref
        return when {
            from.answersToServer && !to.answersToServer -> {
                queue.change(from, listOf(withdrawal(pkg)), at) {
                    carryHome(pkg, to, at)
                    true
                }
                Outcome.MOVED
            }
            // Коробка на общей полке: переставляет её сервер, и до его ответа она остаётся там,
            // где лежит. Иначе отказ по версии оставил бы её на чужой полке (PLAN E1, E6). Новое
            // место придёт снимком ответа — он же истина по этой коробке.
            from.answersToServer -> {
                // Переставляют с полки, где коробка лежит: там её команды и ждут своей очереди.
                queue.change(from, listOf(command(PackageSyncCommand.Move(pkg.id, to.id))), at) {
                    packages.mark(pkg.id, PackageStatus.CHANGING)
                }
                Outcome.MARKED
            }
            // Своя коробка на общую полку: сервер о ней ещё не знает, спорить не с кем, и место
            // меняется сразу. Серверу она едет созданием — вместе с выделением курса.
            to.answersToServer -> {
                place(pkg, to, at)
                publish(pkg, to, at, originSurvives)
                Outcome.MOVED
            }
            else -> {
                place(pkg, to, at)
                Outcome.MOVED
            }
        }
    }

    /** Команда «унёс домой» — отдельно от записи: разбор полки ставит полку зависимой от неё. */
    internal fun withdrawal(pkg: Package): QueuedCommand = command(PackageSyncCommand.Withdraw(pkg.id, pkg.medKit.id, pkg.quantity))

    /**
     * Локальная половина «унёс домой»: коробка на моей полке, чужих броней у местной коробки нет,
     * а пометка держится до ответа сервера (PLAN E1, E6).
     */
    internal suspend fun carryHome(pkg: Package, to: MedKitRef, at: Instant) {
        place(pkg, to, at)
        packages.saveClaims(pkg.id, null)
        check(packages.mark(pkg.id, PackageStatus.CHANGING)) { "пачка прочитана этой же транзакцией" }
    }

    /** Только место: переход пачки к прочитанному состоянию, без команд. */
    internal suspend fun place(pkg: Package, to: MedKitRef, at: Instant) {
        check(packages.adjust(PackageAdjustment.Transfer(pkg.id, to), at = at)) { "пачка прочитана этой же транзакцией" }
    }

    /** Местная коробка на общей полке: рассказать о ней серверу, а с ней — о выделении курса. */
    private suspend fun publish(pkg: Package, to: MedKitRef, at: Instant, originSurvives: Boolean) {
        queue.change(to, announcement(pkg, to, originSurvives = originSurvives), at) {
            packages.mark(pkg.id, PackageStatus.CHANGING)
        }
    }

    /**
     * Чем коробка становится известна серверу: созданием в полке [to] и, если курс её держит,
     * выделением — бронью следом, зависимой от создания. Иначе на сервере выделения не было бы
     * вовсе.
     *
     * Зовут это двое, и событие у коробки одно и то же: её переносят на общую полку — или полка под
     * ней сама становится общей (`feature/medkits/MedKitPublishing`). Во втором случае [after]
     * называет команду публикации: класть коробку некуда, пока полки у сервера нет (PLAN E5, E6).
     *
     * [originSurvives] `false` — полку, с которой коробку принесли, разбирают прямо сейчас: адреса
     * возврата у отказа не будет, и обещать его нечем. Тогда отказ сервера оставляет коробку там,
     * куда её положили, а сводит это с сервером снимок (PLAN E4, E6).
     */
    internal suspend fun announcement(
        pkg: Package,
        to: MedKitRef,
        after: Set<Uuid> = emptySet(),
        originSurvives: Boolean = true
    ): List<QueuedCommand> {
        val create = QueuedCommand(
            Uuid.random(),
            // Откуда коробку принесли: не вышло — она вернётся туда. Если её никуда не несли, а
            // общей стала полка под ней, возвращать некуда (PLAN E6).
            PackageSyncCommand.Create(
                pkg.id,
                to.id,
                pkg.quantity,
                pkg.facts.shared,
                pkg.medKit.id.takeIf { originSurvives && it != to.id }
            ),
            dependsOn = after
        )
        val claim = courses.courseHolding(pkg.id)
            ?.let { courses.findPlan(it) }
            ?.allocatedOf(pkg.ref)
            ?.takeUnless { it.isZero }
            ?.let { QueuedCommand(Uuid.random(), PackageSyncCommand.SetClaim(pkg.id, it), dependsOn = setOf(create.id)) }
        return listOfNotNull(create, claim)
    }

    private fun command(command: PackageSyncCommand) = QueuedCommand(Uuid.random(), command)

    /**
     * Чем кончилось. Переставили — экран показывает новую полку; пометили — коробка остаётся на
     * прежней и ждёт ответа сервера; коробки уже нет — закрывает молча; цели нет — просит выбрать
     * другую; та же полка — говорит об этом; коробка ждёт удаления или выхода — трогать её нельзя;
     * целевая полка сама ждёт ответа — класть в неё рано (PLAN E1, E6).
     */
    enum class Outcome { MOVED, MARKED, GONE, UNUSABLE, TARGET_GONE, TARGET_IS_THE_SAME, TARGET_BUSY }
}
