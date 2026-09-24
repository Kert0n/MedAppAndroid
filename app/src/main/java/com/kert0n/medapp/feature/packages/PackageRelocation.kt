package com.kert0n.medapp.feature.packages

import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.medkit.MedKitRef
import com.kert0n.medapp.domain.pack.Package
import com.kert0n.medapp.domain.pack.PackageStatus
import com.kert0n.medapp.domain.value.Quantity
import com.kert0n.medapp.feature.course.CourseRecords
import com.kert0n.medapp.feature.medkits.MedKitRecords
import com.kert0n.medapp.feature.packages.PackageAdjustment
import com.kert0n.medapp.feature.packages.PackageRecords
import com.kert0n.medapp.feature.readThisTransaction
import com.kert0n.medapp.queue.Carrying
import com.kert0n.medapp.queue.QueueService
import com.kert0n.medapp.queue.Transactions
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
    private val packages: PackageRecords,
    private val medKits: MedKitRecords,
    private val courses: CourseRecords,
    private val queue: QueueService,
    private val transactions: Transactions,
    private val clock: Clock
) {

    suspend fun move(packageId: Uuid, targetMedKitId: Uuid): Outcome = transactions.run {
        val pkg = packages.find(packageId) ?: return@run Outcome.GONE
        if (!pkg.status.allowsUse) return@run Outcome.UNUSABLE
        // С полки, о которой принято решение, не переносят: её публикация уже назвала серверу своё
        // содержимое, и коробка, ушедшая из-под неё, оказалась бы у сервера мимо своего создания —
        // или ушла бы вместе с уборкой. Человек либо ждёт ответа, либо решает о полке заново (E5).
        if (!pkg.medKit.status.allowsDecision) return@run Outcome.ORIGIN_BUSY
        val target = medKits.find(targetMedKitId) ?: return@run Outcome.TARGET_GONE
        if (target.id == pkg.medKit.id) return@run Outcome.TARGET_IS_THE_SAME
        // В полку, о которой уже принято решение, не кладут: она вот-вот уйдёт или уже рассказала
        // серверу о своём содержимом, ничего человеку не сказав (PLAN E1, E5, E6).
        if (!target.status.allowsDecision) return@run Outcome.TARGET_BUSY
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
        return when (val carrying = queue.carrying(pkg, to)) {
            is Carrying.Home -> {
                queue.change(from, carrying.laying.errands, at) {
                    carryHome(pkg, to, at, by = carrying.laying.by)
                    true
                }
                Outcome.MOVED
            }
            // Коробка на общей полке: переставляет её сервер, и до его ответа она остаётся там,
            // где лежит. Иначе отказ по версии оставил бы её на чужой полке (PLAN E1, E6). Новое
            // место придёт снимком ответа — он же истина по этой коробке.
            is Carrying.ByServer -> {
                queue.change(from, carrying.laying.errands, at) {
                    packages.mark(pkg.id, PackageStatus.CHANGING, by = carrying.laying.by)
                }
                Outcome.MARKED
            }
            // Своя коробка на общую полку: сервер о ней ещё не знает, спорить не с кем, и место
            // меняется сразу. Серверу она едет созданием — вместе с выделением курса.
            Carrying.Announced -> {
                place(pkg, to, at)
                publish(pkg, to, at, originSurvives)
                Outcome.MOVED
            }
            Carrying.Local -> {
                place(pkg, to, at)
                Outcome.MOVED
            }
        }
    }

    internal suspend fun carryHome(pkg: Package, to: MedKitRef, at: Instant, by: Uuid) {
        place(pkg, to, at)
        packages.saveClaims(pkg.id, null)
        packages.mark(pkg.id, PackageStatus.CHANGING, by = by).readThisTransaction("пачка")
    }

    internal suspend fun place(pkg: Package, to: MedKitRef, at: Instant) {
        packages.adjust(PackageAdjustment.Transfer(pkg.id, to), at = at).readThisTransaction("пачка")
    }

    private suspend fun publish(pkg: Package, to: MedKitRef, at: Instant, originSurvives: Boolean) {
        // Откуда коробку принесли: не вышло — она вернётся туда (PLAN E6).
        val announcement = queue.announcement(pkg, to, claimOf(pkg), originSurvives = originSurvives)
        queue.change(to, announcement.errands, at) {
            // Пометку держит создание: им коробка и становится известна серверу (PLAN E6).
            packages.mark(pkg.id, PackageStatus.CHANGING, by = announcement.by)
        }
    }

    /** Выделение курса, который держит коробку, — бронь, которая едет вместе с её созданием. */
    internal suspend fun claimOf(pkg: Package): Quantity? =
        courses.courseHolding(pkg.id)?.let { courses.findPlan(it) }?.allocatedOf(pkg.ref)

    /**
     * Чем кончилось. Переставили — экран показывает новую полку; пометили — коробка остаётся на
     * прежней и ждёт ответа сервера; коробки уже нет — закрывает молча; цели нет — просит выбрать
     * другую; та же полка — говорит об этом; коробка ждёт удаления или выхода — трогать её нельзя;
     * целевая полка сама ждёт ответа — класть в неё рано; полка, с которой несут, ждёт ответа на
     * своё решение — переносить из неё рано (PLAN E1, E5, E6).
     */
    enum class Outcome {
        MOVED, MARKED, GONE, UNUSABLE, ORIGIN_BUSY, TARGET_GONE, TARGET_IS_THE_SAME, TARGET_BUSY
    }
}
