package com.kert0n.medapp.feature.packages

import com.kert0n.medapp.domain.course.PackageFollowing
import com.kert0n.medapp.domain.pack.PackageFacts
import com.kert0n.medapp.domain.pack.PackageStatus
import com.kert0n.medapp.feature.packages.PackageRecords
import com.kert0n.medapp.feature.readThisTransaction
import com.kert0n.medapp.queue.Laying
import com.kert0n.medapp.queue.QueueService
import com.kert0n.medapp.queue.Transactions
import java.time.Clock
import javax.inject.Inject
import kotlin.uuid.Uuid

/**
 * Человек правит сведения о коробке (ТЗ 4.1.1.3.3; PLAN D3). Редактор загружает `PackageFacts`
 * целиком и сохраняет целиком: `null` в поле — сведения нет. Личное — срок, заметка, цена, даты,
 * подсказка дозы — остаётся на устройстве всегда (C0). Общее — описание препарата — на полке,
 * отвечающей серверу, уезжает командой `Describe(before, after)`: своими полями поверх того, что у
 * сервера окажется к отправке (C1 «Действие над общей пачкой — разница»), а коробка до ответа
 * помечена `CHANGING`. Правка, не вышедшая за границу публикации, команды не ставит:
 * `before.shared == after.shared` — и на провод нечего везти.
 *
 * Переход применяется к коробке, прочитанной той же транзакцией (F5): остаток, брони и обвязку
 * синхронизации правка не трогает. Единица неизменна (C1) и в сведения не входит.
 *
 * Очистить форму у серверной коробки нельзя: `null` на проводе значит «не трогать», а `""` не
 * UUID (D3). Такая правка отвергается целиком, и экран объясняет ограничение — иначе неудалённая
 * серверная форма выдавалась бы за очищенную.
 */
class PackageDescribing @Inject constructor(
    private val packages: PackageRecords,
    private val following: PackageFollowing,
    private val queue: QueueService,
    private val transactions: Transactions,
    private val clock: Clock
) {

    suspend fun describe(packageId: Uuid, facts: PackageFacts): Outcome = transactions.run {
        val pkg = packages.find(packageId) ?: return@run Outcome.GONE
        if (!pkg.status.allowsUse) return@run Outcome.UNUSABLE
        // Личная правка серверу не едет, и поручения у неё нет: «есть ли поручение» и «уехало ли
        // изменение» — один вопрос, и отвечает на него очередь.
        val laying = queue.description(pkg, facts.shared)
        if (laying == Laying.Refused) return@run Outcome.FORM_CLEAR_UNSUPPORTED
        val now = clock.instant()
        queue.change(pkg.medKit, laying.errands, now) {
            packages.describe(pkg.id, facts).readThisTransaction("пачка")
            if (laying is Laying.Awaiting) {
                packages.mark(pkg.id, PackageStatus.CHANGING, by = laying.by).readThisTransaction("пачка")
            }
            true
        }
        // Коробка изменилась — курс следует за ней той же дверью: другая форма отключает источник (PLAN D5).
        following.follow(pkg.id, now)
        Outcome.SAVED
    }

    /**
     * Чем кончилось. Записано — экран закрывает редактор; коробки нет — закрывает молча; коробка
     * ждёт удаления или выхода — трогать её нельзя; форму серверной коробки очистить нечем —
     * экран объясняет ограничение, и правка не записана (PLAN D3, E1).
     */
    enum class Outcome { SAVED, GONE, UNUSABLE, FORM_CLEAR_UNSUPPORTED }
}
