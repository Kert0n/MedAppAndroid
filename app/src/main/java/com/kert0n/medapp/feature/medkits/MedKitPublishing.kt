package com.kert0n.medapp.feature.medkits

import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.medkit.MedKitStatus
import com.kert0n.medapp.domain.pack.PackageStatus
import com.kert0n.medapp.feature.packages.PackageRecords
import com.kert0n.medapp.feature.packages.PackageRelocation
import com.kert0n.medapp.feature.readThisTransaction
import com.kert0n.medapp.queue.QueueService
import com.kert0n.medapp.queue.Transactions
import java.time.Clock
import javax.inject.Inject
import kotlin.uuid.Uuid

/**
 * Человек делает свою полку общей (ТЗ 4.1.1.11, PLAN E5). Это **решение**, а не обряд при связи:
 * оно случается сразу и целиком у нас, а серверу его везёт очередь — теми же средствами, какими
 * едет всё остальное. Полка уезжает своей командой, её коробки — обычными командами пачек,
 * зависимыми от неё: класть коробку некуда, пока полки у сервера нет.
 *
 * **Половины полки не бывает** (PLAN D2). Ответ сервера на саму полку делает её общей, но решение
 * доведено только тогда, когда уехало и содержимое: до тех пор полка помечена `PUBLISHING` и
 * приглашений не выдаёт — иначе приглашённый увидел бы полку без половины лекарств. При связи всё
 * это укладывается в один проход очереди, и пометку человек видит мгновение.
 *
 * Отката нет и не нужно: до ответа сервера там ничего нашего не появилось, отказ оставляет полку
 * местной, а повтор безопасен по идентификаторам — занятый номер сервер объясняет сам (PLAN E3).
 */
class MedKitPublishing @Inject constructor(
    private val medKits: MedKitRecords,
    private val packages: PackageRecords,
    private val relocation: PackageRelocation,
    private val queue: QueueService,
    private val transactions: Transactions,
    private val clock: Clock
) {

    suspend fun publish(medKitId: Uuid): Outcome = transactions.run {
        val medKit = medKits.find(medKitId) ?: return@run Outcome.MED_KIT_GONE
        when (medKit.refusesPublication()) {
            MedKit.PublicationRefusal.BUSY -> return@run Outcome.BUSY
            MedKit.PublicationRefusal.ALREADY_SHARED -> return@run Outcome.ALREADY_SHARED
            null -> Unit
        }
        val now = clock.instant()
        val publishing = medKit.markPublishing()
        // Полка публикуемая уже отвечает серверу, поэтому поручения её содержимого ставятся так же,
        // как у общей: адресат у них — она сама (PLAN E1).
        val contents = packages.contentsOf(medKitId).associateWith { relocation.claimOf(it) }
        val publication = queue.publication(publishing.ref, contents)
        val announcements = publication.announcements
        queue.change(publishing.ref, publication.errands, now) {
            medKits.mark(medKitId, MedKitStatus.PUBLISHING).readThisTransaction("аптечка")
            // Пометку каждой коробки держит её собственное создание: полка отвечает за себя, а
            // коробка — за то, чем она станет известна серверу (PLAN E1, E5).
            for ((pkg, announcement) in announcements) {
                packages.mark(pkg.id, PackageStatus.CHANGING, by = announcement.by).readThisTransaction("пачка")
            }
            true
        }
        Outcome.PUBLISHING
    }

    /**
     * Чем кончилось. Приняли — экран показывает полку общей и ждущей; аптечки уже нет — закрываем
     * молча; она и так общая — публиковать нечего; о ней уже принято другое решение — ждём его
     * ответа (PLAN E1, E5).
     */
    enum class Outcome {
        PUBLISHING,
        MED_KIT_GONE,
        ALREADY_SHARED,
        BUSY
    }
}
