package com.kert0n.medapp.feature.medkits

import com.kert0n.medapp.domain.medkit.MedKitStatus
import com.kert0n.medapp.domain.pack.PackageStatus
import com.kert0n.medapp.feature.packages.PackageRelocation
import com.kert0n.medapp.queue.QueueService
import com.kert0n.medapp.queue.QueuedCommand
import com.kert0n.medapp.queue.Transactions
import com.kert0n.medapp.queue.medkit.MedKitSyncCommand
import com.kert0n.medapp.queue.readThisTransaction
import com.kert0n.medapp.storage.medkit.MedKitStorageRepository
import com.kert0n.medapp.storage.pack.PackageStorageRepository
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
    private val medKits: MedKitStorageRepository,
    private val packages: PackageStorageRepository,
    private val relocation: PackageRelocation,
    private val queue: QueueService,
    private val transactions: Transactions,
    private val clock: Clock
) {

    suspend fun publish(medKitId: Uuid): Outcome = transactions.run {
        val medKit = medKits.find(medKitId) ?: return@run Outcome.MED_KIT_GONE
        if (!medKit.status.allowsDecision) return@run Outcome.BUSY
        if (medKit.answersToServer) return@run Outcome.ALREADY_SHARED
        val now = clock.instant()
        val publishing = medKit.markPublishing()
        val publish = QueuedCommand(Uuid.random(), MedKitSyncCommand.Publish(medKitId))
        // Полка публикуемая уже отвечает серверу, поэтому команды её содержимого ставятся так же,
        // как у общей: адресат у них — она сама (PLAN E1).
        val contents = packages.contentsOf(medKitId)
        val announcements = contents.associateWith { relocation.announcement(it, publishing.ref, after = setOf(publish.id)) }
        queue.change(publishing.ref, listOf(publish) + announcements.values.flatMap { it.commands }, now) {
            medKits.mark(medKitId, MedKitStatus.PUBLISHING).readThisTransaction("аптечка")
            // Пометку каждой коробки держит её собственное создание: полка отвечает за себя, а
            // коробка — за то, чем она станет известна серверу (PLAN E1, E5).
            for ((pkg, announcement) in announcements) {
                packages.mark(pkg.id, PackageStatus.CHANGING, by = announcement.create.id).readThisTransaction("пачка")
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
