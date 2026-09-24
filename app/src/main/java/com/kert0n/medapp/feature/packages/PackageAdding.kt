package com.kert0n.medapp.feature.packages

import com.kert0n.medapp.domain.pack.Package
import com.kert0n.medapp.domain.pack.PackageFacts
import com.kert0n.medapp.domain.value.Quantity
import com.kert0n.medapp.feature.medkits.MedKitRecords
import com.kert0n.medapp.feature.packages.PackageRecords
import com.kert0n.medapp.queue.Laying
import com.kert0n.medapp.queue.QueueService
import com.kert0n.medapp.queue.Transactions
import java.time.Clock
import javax.inject.Inject
import kotlin.uuid.Uuid

/**
 * Человек кладёт купленную коробку на полку (ТЗ 4.1.1.3.1; PLAN D3). Коробка существует с момента
 * записи: запись о ней, живая строка и детали ложатся одной транзакцией, `addedAt` — этот момент.
 * Что коробка не бывает пустой, знает сама пачка; сценарий этого не повторяет.
 *
 * Полке, которая отвечает серверу (D2), коробку везёт `Create` — той же транзакцией, — и до ответа
 * коробка помечена `CHANGING`: пользоваться ею можно, а первое подтверждённое число принесёт ответ
 * на её создание (E1). Броней у новой коробки нет: курс её ещё не держит. На полку, о которой
 * принято решение — уборка или публикация, — не кладут: она вот-вот уйдёт или уже рассказала
 * серверу о своём содержимом без этой коробки (E5, E6).
 */
class PackageAdding @Inject constructor(
    private val packages: PackageRecords,
    private val medKits: MedKitRecords,
    private val queue: QueueService,
    private val transactions: Transactions,
    private val clock: Clock
) {

    suspend fun add(medKitId: Uuid, facts: PackageFacts, quantity: Quantity, templateId: Uuid? = null): Outcome =
        transactions.run {
            val medKit = medKits.find(medKitId) ?: return@run Outcome.MedKitGone
            // В полку, о которой принято решение, не кладут: уборка унесла бы коробку с собой, а
            // публикация уже пересчитала своё содержимое и об этой коробке серверу не расскажет
            // (PLAN E1, E5).
            if (!medKit.decidable) return@run Outcome.MedKitBusy
            val now = clock.instant()
            val pkg = Package(
                id = Uuid.random(),
                medKit = medKit.ref,
                facts = facts,
                quantity = quantity,
                addedAt = now,
                templateId = templateId
            )
            val laying = queue.adding(pkg)
            val announced = if (laying is Laying.Awaiting) pkg.markChanging(laying.by) else pkg
            queue.change(medKit.ref, laying.errands, now) {
                packages.add(announced)
                true
            }
            Outcome.Added(pkg.id)
        }

    /**
     * Чем кончилось. Записано — экран открывает карточку по [Added.packageId]; полки нет — просит
     * выбрать другую; полка ждёт ответа на уборку — класть в неё рано (PLAN E1, E6).
     */
    sealed interface Outcome {
        data class Added(val packageId: Uuid) : Outcome
        data object MedKitGone : Outcome
        data object MedKitBusy : Outcome
    }
}
