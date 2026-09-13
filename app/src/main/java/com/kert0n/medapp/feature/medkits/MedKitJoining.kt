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
 * полки: он читает полный снимок, и тот приносит полку, которой у нас не было (E4). **Какая из
 * появившихся полок — та самая, не угадывается:** снимок приносит все полки, которых у нас нет, и
 * связать одну из них с этим кодом нечем. Поэтому исход — «вы уже в полке», и полка видна в списке.
 */
class MedKitJoining @Inject constructor(
    private val snapshots: SnapshotApplier
) {

    suspend fun join(key: InvitationKey): Outcome = when (val joining = snapshots.join(key)) {
        is SnapshotApplier.Joining.Joined -> Outcome.Joined(joining.medKitId)
        SnapshotApplier.Joining.InvitationInvalid -> Outcome.InvitationInvalid
        is SnapshotApplier.Joining.Refused -> Outcome.Unavailable(joining.reason)
        SnapshotApplier.Joining.AlreadyMember -> {
            snapshots.refresh()
            Outcome.AlreadyMember
        }
        // Ответа нет: вступили — снимок принесёт полку, а повтор тем же кодом ответит «уже вступили»;
        // не вступили — повтор вступит. В обоих случаях человеку повторить.
        SnapshotApplier.Joining.OutcomeUnknown -> {
            snapshots.refresh()
            Outcome.Unavailable(Unavailability.SERVER_SILENT)
        }
    }

    /**
     * Чем кончилось. Вступили — экран открывает полку; уже в ней — говорит об этом и показывает
     * список, где полка уже лежит; код недействителен — единственный текст «попросите новый код»
     * (B6); сервера нет — причина и повтор.
     */
    sealed interface Outcome {

        data class Joined(val medKitId: Uuid) : Outcome

        data object AlreadyMember : Outcome

        data object InvitationInvalid : Outcome

        data class Unavailable(val reason: Unavailability) : Outcome
    }
}
