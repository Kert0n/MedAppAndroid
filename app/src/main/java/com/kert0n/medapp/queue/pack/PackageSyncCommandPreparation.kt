package com.kert0n.medapp.queue.pack

import com.kert0n.medapp.domain.pack.Package
import com.kert0n.medapp.domain.value.Quantity
import com.kert0n.medapp.queue.Preparation
import com.kert0n.medapp.queue.RefusalReason

/**
 * «Подумали» перед отправкой: команда смотрит на пачку, какой её знает устройство после свежего
 * чтения, и решает — уходит запрос, закрывается отказ или желаемое уже так (PLAN E2, E3).
 * Число в единице, которой пачку больше не считают, на провод не идёт — ни расход, ни бронь, ни
 * пересчёт ([PackageSyncCommand.measuredIn]). Бронь, равная желаемой, и снятие отсутствующей
 * брони — уже так.
 *
 * Здесь же действие человека сводится с тем, что сделали соседи (C1 «Действие над общей пачкой —
 * разница»): пересчёт кладёт разницу поверх прочитанного числа, правка сведений — только свои
 * поля поверх прочитанных. Соотнести нельзя — итог ниже нуля, то же поле изменено соседом иначе —
 * отказ `CONFLICT`; сведения уже такие — уже так. Всё остальное уходит — с тем подтверждённым
 * остатком, бронью и сведениями, по которым посылку соберёт курьер.
 */
fun PackageSyncCommand.prepare(pkg: Package, sync: PackageSyncState): Preparation {
    val mine = pkg.claims?.mine?.let { Quantity(it, pkg.quantity.unit) }
    val unit = measuredIn
    return when {
        unit != null && unit != pkg.quantity.unit -> Preparation.Refuse(RefusalReason.UNIT_CHANGED)
        this is PackageSyncCommand.SetClaim && mine == amount -> Preparation.AlreadyApplied
        this is PackageSyncCommand.ReleaseClaim && mine == null && sync.claimsVersion != null -> Preparation.AlreadyApplied
        this is PackageSyncCommand.CorrectStock && conflictsWith(pkg.quantity) -> Preparation.Refuse(RefusalReason.CONFLICT)
        // Создание рассказывает серверу о коробке, какая она сейчас: до ответа она местная, и
        // сделанное с ней после решения уже в этом числе и в этих сведениях (PLAN E6).
        this is PackageSyncCommand.Create -> Preparation.Send(confirmed = pkg.quantity, mine = mine, known = pkg.facts.shared)
        this is PackageSyncCommand.Describe -> when (val merged = onto(pkg.facts.shared)) {
            null -> Preparation.Refuse(RefusalReason.CONFLICT)
            pkg.facts.shared -> Preparation.AlreadyApplied
            else -> Preparation.Send(confirmed = pkg.quantity, mine = mine, known = pkg.facts.shared)
        }
        else -> Preparation.Send(confirmed = pkg.quantity, mine = mine)
    }
}
