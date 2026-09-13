package com.kert0n.medapp.feature.medkits

import com.kert0n.medapp.domain.Unavailability
import com.kert0n.medapp.domain.medkit.InvitationKey
import com.kert0n.medapp.queue.SnapshotApplier
import javax.inject.Inject
import kotlin.uuid.Uuid

/**
 * Человек вступает в чужую полку по коду приглашения (ТЗ 4.1.1.11, PLAN C0). Действие требует
 * связи (C3): без сервера полки не существует, и в очередь его не поставить. Когда оно удалось,
 * полка уже лежит у нас вместе с тем, что на ней, — «Общей аптечкой», которую человек назовёт сам.
 *
 * **Ответ может потеряться, и это обычное дело.** Сервер вступил, а до нас ответ не дошёл; повтор
 * тем же кодом отвечает «уже вступили» — без номера полки. Сценарий не оставляет человека без
 * полки: он читает полный снимок, а тот приносит полку, которой у нас не было (E4). Появилась
 * ровно одна — это она, и вступление состоялось.
 */
class MedKitJoining @Inject constructor(
    private val snapshots: SnapshotApplier
) {

    suspend fun join(key: InvitationKey): Outcome = when (val joining = snapshots.join(key)) {
        is SnapshotApplier.Joining.Joined -> Outcome.Joined(joining.medKitId)
        SnapshotApplier.Joining.InvitationInvalid -> Outcome.InvitationInvalid
        is SnapshotApplier.Joining.Refused -> Outcome.Unavailable(joining.reason)
        SnapshotApplier.Joining.AlreadyMember -> recovered(otherwise = Outcome.AlreadyMember)
        // Ответа нет: либо вступили, и снимок это покажет, либо нет — и тогда повторить стоит позже.
        SnapshotApplier.Joining.OutcomeUnknown -> recovered(otherwise = Outcome.Unavailable(Unavailability.SERVER_SILENT))
    }

    /** Вступление, чей ответ мы не получили, проявляется в снимке новой полкой. */
    private suspend fun recovered(otherwise: Outcome): Outcome = when (val read = snapshots.refresh()) {
        is SnapshotApplier.Outcome.Applied -> read.arrived.singleOrNull()?.let { Outcome.Joined(it) } ?: otherwise
        is SnapshotApplier.Outcome.Refused -> otherwise
    }

    /**
     * Чем кончилось. Вступили — экран открывает полку; уже в ней — говорит об этом и показывает
     * список; код недействителен — единственный текст «попросите новый код» (B6); сервера нет —
     * причина и повтор.
     */
    sealed interface Outcome {

        data class Joined(val medKitId: Uuid) : Outcome

        data object AlreadyMember : Outcome

        data object InvitationInvalid : Outcome

        data class Unavailable(val reason: Unavailability) : Outcome
    }
}
