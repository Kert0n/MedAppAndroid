package com.kert0n.medapp.feature.medkits

import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.medkit.MedKitContents
import com.kert0n.medapp.domain.medkit.MedKitProjection
import com.kert0n.medapp.queue.Transactions
import com.kert0n.medapp.storage.medkit.MedKitStorageRepository
import java.time.Clock
import javax.inject.Inject
import kotlin.uuid.Uuid

/**
 * Человек заводит полку и правит её название и место хранения (ТЗ 4.1.1.1, 4.1.1.2; PLAN D2).
 * Заведённая полка — местная, с одним участником; общей её делает отдельное решение
 * ([MedKitPublishing]).
 *
 * Команд серверу здесь нет ни у своей полки, ни у общей: название и место хранения остаются на
 * устройстве (C0), и правка общей полки — такая же местная запись, как правка своей. Правка идёт
 * **названными полями** к полке, прочитанной той же транзакцией (F5): экран, загрузивший полку до
 * чужой пометки или до ответа сервера о публикации, переименованием их не переписывает.
 */
class MedKitKeeping @Inject constructor(
    private val medKits: MedKitStorageRepository,
    private val transactions: Transactions,
    private val clock: Clock
) {

    /** Новая местная полка. Допустимость названия и места — правило самой аптечки (D2). */
    suspend fun create(name: String, location: String? = null): MedKitProjection = transactions.run {
        val medKit = MedKit(
            id = Uuid.random(),
            name = name,
            location = location,
            publication = MedKit.Publication.LOCAL,
            participantCount = 1,
            createdAt = clock.instant()
        )
        medKits.add(medKit)
        // Новая полка пуста, и это правда о ней, а не умолчание.
        medKit.projection(MedKitContents.EMPTY)
    }

    /**
     * Название и место хранения — к полке, какая она в базе сейчас. Помеченную уборкой не правят:
     * человек уже решил её судьбу, и второе решение поверх первого некуда деть (PLAN E1);
     * публикуемую правят — решение о ней это не меняет.
     */
    suspend fun describe(medKitId: Uuid, name: String, location: String?): Outcome = transactions.run {
        val medKit = medKits.find(medKitId) ?: return@run Outcome.GONE
        if (!medKit.status.allowsUse) return@run Outcome.BUSY
        check(medKits.describe(medKitId, name, location)) { "аптечка прочитана этой же транзакцией" }
        Outcome.SAVED
    }

    /**
     * Чем кончилось. Записано — экран закрывает правку; полки нет — закрывает молча; полка ждёт
     * ответа на уборку — правка отвергнута, и человеку это показано.
     */
    enum class Outcome { SAVED, GONE, BUSY }
}
