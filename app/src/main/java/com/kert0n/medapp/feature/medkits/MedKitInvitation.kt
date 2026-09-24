package com.kert0n.medapp.feature.medkits

import com.kert0n.medapp.di.InvitationTerm
import com.kert0n.medapp.domain.Unavailability
import com.kert0n.medapp.domain.medkit.Invitation
import com.kert0n.medapp.domain.medkit.MedKitInvitations
import com.kert0n.medapp.queue.SnapshotApplier
import java.time.Clock
import java.time.Duration
import javax.inject.Inject
import kotlin.uuid.Uuid

/**
 * Человек зовёт в свою общую полку (ТЗ 4.1.1.11). Звать можно только в полку, которая **уехала
 * целиком**: половины полки не бывает, и приглашённый не должен увидеть её без части лекарств
 * (PLAN D2, E5). Местную звать некуда — на сервере её нет; помеченную — рано: о ней ещё не доведено
 * решение. При связи публикация доводится за доли секунды, и второй случай человек почти не видит.
 *
 * Действие требует связи (C3). Срок ключа сервер не называет, поэтому приглашение несёт честную
 * **оценку** от момента выдачи (B6).
 *
 * Сервер говорит, что полки нам не видно, — значит, её убрали, пока мы не смотрели: сценарий
 * читает полный снимок, и полка уходит из списка сразу, а не при следующем фоновом заходе (E4).
 */
class MedKitInvitation @Inject constructor(
    private val medKits: MedKitRecords,
    private val invitations: MedKitInvitations,
    private val snapshots: SnapshotApplier,
    @InvitationTerm private val term: Duration,
    private val clock: Clock
) {

    suspend fun invite(medKitId: Uuid): Outcome {
        val medKit = medKits.find(medKitId) ?: return Outcome.MedKitGone
        // Полка, которая к серверу только едет, местной не считается: решение о ней уже принято, и
        // человеку остаётся дождаться ответа, а не публиковать её заново (PLAN E5).
        if (!medKit.acceptsCommands) return Outcome.NotShared
        if (!medKit.acceptsInvitations) return Outcome.Busy
        return when (val issue = invitations.issue(medKit)) {
            is MedKitInvitations.Issue.Issued -> Outcome.Invited(Invitation(issue.key, clock.instant(), term))
            // Полка ушла из списка, только если снимок лёг; не лёг — она ещё видна, и сказать «её нет»
            // значило бы разойтись со списком.
            MedKitInvitations.Issue.NotAccessible -> when (val read = snapshots.refresh()) {
                is SnapshotApplier.Outcome.Applied -> Outcome.MedKitGone
                is SnapshotApplier.Outcome.Refused -> Outcome.Unavailable(read.reason)
            }
            is MedKitInvitations.Issue.Unavailable -> Outcome.Unavailable(issue.reason)
        }
    }

    /**
     * Чем кончилось. Позвали — экран показывает QR и код, код уже в буфере (C1); полки нет или она
     * нам больше не видна — закрыть; полка местная — предложить опубликовать; о полке ещё не доведено
     * решение — подождать; сервера нет — причина и повтор.
     */
    sealed interface Outcome {

        data class Invited(val invitation: Invitation) : Outcome

        data object MedKitGone : Outcome

        data object NotShared : Outcome

        data object Busy : Outcome

        data class Unavailable(val reason: Unavailability) : Outcome
    }
}
