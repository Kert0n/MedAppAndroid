package com.kert0n.medapp.network.medkit

import com.kert0n.medapp.domain.medkit.InvitationKey
import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.medkit.MedKitInvitations
import com.kert0n.medapp.network.account.asUnavailability
import com.kert0n.medapp.network.server.ApiFailure
import com.kert0n.medapp.network.server.ApiResult
import com.kert0n.medapp.network.server.MedAppApi
import javax.inject.Inject

/**
 * Выдача приглашения на этом сервере: коды ответа за границу сети не уходят (PLAN B5, H1). Потерянный
 * ответ ничем не опасен — ключ, которого мы не получили, никто не покажет, а новый выдаётся
 * повтором.
 */
class ServerMedKitInvitations @Inject constructor(
    private val api: MedAppApi
) : MedKitInvitations {

    override suspend fun issue(medKit: MedKit): MedKitInvitations.Issue =
        when (val answer = api.createInvitation(medKit.id)) {
            is ApiResult.Success -> MedKitInvitations.Issue.Issued(InvitationKey(answer.value.key))
            is ApiResult.Failure -> when (val failure = answer.failure) {
                ApiFailure.NotFound -> MedKitInvitations.Issue.NotAccessible
                else -> MedKitInvitations.Issue.Unavailable(failure.asUnavailability())
            }
        }
}
