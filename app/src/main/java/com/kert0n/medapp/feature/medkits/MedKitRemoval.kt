package com.kert0n.medapp.feature.medkits

import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.medkit.MedKitStatus
import com.kert0n.medapp.domain.pack.PackageStatus
import com.kert0n.medapp.feature.packages.PackageRecords
import com.kert0n.medapp.feature.packages.PackageRelocation
import com.kert0n.medapp.feature.packages.PackageRemoval
import com.kert0n.medapp.feature.readThisTransaction
import com.kert0n.medapp.queue.Clearing
import com.kert0n.medapp.queue.QueueService
import com.kert0n.medapp.queue.Transactions
import java.time.Clock
import javax.inject.Inject
import kotlin.uuid.Uuid

/**
 * Человек убирает полку (ТЗ 4.1.1.2.3, 4.1.1.11.3). Для него это **одно действие** — полки больше
 * нет в его списке, — а различается только судьба коробок ([Fate]): выбросить вместе с полкой,
 * перенести на другую полку или оставить остальным и выйти. История — записи о коробках и приёмы —
 * остаётся (PLAN E6, D3).
 *
 * **Оставить остальным** — выход из общей полки: полка и коробки живут у других, у нас коробки
 * помечены потерянными до ответа сервера, а ответ их кончает утратой доступа. Своя полка
 * существует только у нас — оставлять её некому.
 *
 * **Своя полка существует только у нас**, поэтому решение и есть подтверждение: она разбирается по
 * коробкам, каждая проходит свой доменный путь — выбрасывания ([PackageRemoval]) или переезда
 * ([PackageRelocation]), — и строка полки уходит следом. Всё одной транзакцией: полки без
 * содержимого и содержимого без полки не бывает ни на миг (F5).
 *
 * **Общей полкой распоряжается сервер.** Ему уходит одна команда аптечки
 * (`DELETE /v1/med-kits/{id}?targetMedKitId=`), а до ответа **ничего не трогается**: коробки целы,
 * полка на месте, и человеку видно, что они помечены. Иначе отказ сервера уничтожил бы у нас то,
 * что у других участников живо, и вернуть это было бы нечем. Разбирает полку подтверждение —
 * эффектом очереди, там же, где закрывается операция.
 *
 * Общую полку забирают домой, на местную, по коробкам: каждая сразу у человека, серверу —
 * «унёс домой», а полка уходит у всех следом и **зависит** от них. Не вышло с коробкой — она
 * возвращается на полку, и полка остаётся: иначе сервер выбросил бы её вместе с полкой (PLAN E6).
 */
class MedKitRemoval @Inject constructor(
    private val medKits: MedKitRecords,
    private val packages: PackageRecords,
    private val removal: PackageRemoval,
    private val relocation: PackageRelocation,
    private val queue: QueueService,
    private val transactions: Transactions,
    private val clock: Clock
) {

    suspend fun remove(medKitId: Uuid, fate: Fate): Outcome = transactions.run {
        val medKit = medKits.find(medKitId) ?: return@run Outcome.MED_KIT_GONE
        if (!medKit.decidable) return@run Outcome.BUSY
        val now = clock.instant()
        if (fate == Fate.LeaveToOthers) {
            if (!medKit.mayBeLeftToOthers) return@run Outcome.NOT_SHARED
            val leave = queue.leaving(medKit.ref)
            queue.change(medKit.ref, leave.errands, now) {
                // Коробки остаются остальным, а у нас до ответа только видны. Ждущую своего решения
                // не трогаем — её отпустит её же команда (PLAN E1, E6). Пометки, поставленные
                // здесь, принадлежат выходу: снимет их его ответ, а не первая доехавшая команда.
                for (pkg in packages.contentsOf(medKitId)) {
                    if (pkg.usable) {
                        packages.mark(pkg.id, PackageStatus.LOST, by = leave.by).readThisTransaction("пачка")
                    }
                }
                medKits.mark(medKitId, MedKitStatus.REMOVING)
            }
            return@run Outcome.MARKED
        }
        val target = (fate as? Fate.MoveTo)?.let { medKits.find(it.medKitId) ?: return@run Outcome.TARGET_GONE }
        when (target?.refusesContentsFrom(medKit.ref)) {
            MedKit.ReceivingRefusal.SAME_SHELF -> return@run Outcome.TARGET_IS_THE_SAME
            MedKit.ReceivingRefusal.BUSY -> return@run Outcome.TARGET_BUSY
            null -> Unit
        }
        when (val clearing = queue.clearing(medKit.ref, target?.ref)) {
            is Clearing.Home -> {
                val contents = packages.contentsOf(medKitId)
                // Коробку, которая ждёт своего ответа, унести нечем, а полка уйдёт у всех — и унесёт
                // её с собой. Лучше подождать ответа по коробке, чем выбросить её молча (PLAN E6).
                if (contents.any { !it.usable }) return@run Outcome.CONTENTS_BUSY
                val homecoming = queue.homecoming(medKit.ref, contents)
                queue.change(medKit.ref, homecoming.errands, now) {
                    for ((pkg, withdrawal) in homecoming.withdrawals) {
                        relocation.carryHome(pkg, clearing.target, now, by = withdrawal.by)
                    }
                    medKits.mark(medKitId, MedKitStatus.REMOVING)
                }
                return@run Outcome.MARKED
            }
            is Clearing.ByServer -> {
                // Коробки выбрасываемой полки выведены из оборота, переносимые — только помечены: ими
                // пользуются, пока сервер переставляет. Ждущую своего решения коробку не трогаем — её
                // отпустит её же команда (PLAN E1, E6).
                val fate = if (target == null) PackageStatus.REMOVING else PackageStatus.CHANGING
                queue.change(medKit.ref, clearing.laying.errands, now) {
                    for (pkg in packages.contentsOf(medKitId)) {
                        if (pkg.usable) {
                            packages.mark(pkg.id, fate, by = clearing.laying.by).readThisTransaction("пачка")
                        }
                    }
                    medKits.mark(medKitId, MedKitStatus.REMOVING)
                }
                return@run Outcome.MARKED
            }
            Clearing.Local -> Unit
        }
        for (pkg in packages.contentsOf(medKitId)) {
            if (target == null) {
                removal.discard(pkg, now)
            } else {
                // Полки, с которой несут, сейчас не станет: возвращать на неё при отказе сервера
                // будет некуда, и обещать возврат нечем (PLAN E6).
                val moved = relocation.relocate(pkg, target, now, originSurvives = false)
                check(moved == PackageRelocation.Outcome.MOVED) { "местная коробка переезжает сразу, а не $moved" }
            }
        }
        medKits.delete(medKitId).readThisTransaction("аптечка")
        Outcome.REMOVED
    }

    /** Судьба коробок убираемой полки — единственное, чем случаи уборки отличаются для человека. */
    sealed interface Fate {

        /** Выбросить вместе с полкой: у всех, если полка общая. */
        data object ThrowAway : Fate

        /** Полный перенос на полку [medKitId] — «забрал аптечку домой» или в другую общую. */
        data class MoveTo(val medKitId: Uuid) : Fate

        /** Оставить остальным и выйти: коробки живут у других, у нас они потеряны. */
        data object LeaveToOthers : Fate
    }

    /**
     * Чем кончилось. Случаи различает поведение экрана: убрали — уходим со списка; пометили —
     * полка остаётся на месте и ждёт согласия сервера; аптечки уже нет — закрываем молча; некуда
     * переносить — просим выбрать другую; та же — говорим об этом; полка уже ждёт другого решения —
     * ждём его ответа; оставить остальным местную полку нельзя — остальных нет; цель сама ждёт
     * ответа или одна из коробок ждёт своего — просим подождать (PLAN E1, E6).
     */
    enum class Outcome {
        REMOVED,
        MARKED,
        MED_KIT_GONE,
        BUSY,
        TARGET_GONE,
        TARGET_IS_THE_SAME,
        TARGET_BUSY,
        CONTENTS_BUSY,
        NOT_SHARED
    }
}
