package com.kert0n.medapp.ui.intake

import androidx.annotation.StringRes
import com.kert0n.medapp.R
import com.kert0n.medapp.domain.intake.IntakeRejected

/**
 * Слова отказа в приёме. Причина — значение, и текст к ней живёт в `R.string.*` (PLAN H1); место
 * одно на все экраны приёма: лист разового и карточка пункта отвечают человеку одинаково, потому
 * что отказывает им один и тот же домен.
 */
@get:StringRes
internal val IntakeRejected.Reason.text: Int
    get() = when (this) {
        IntakeRejected.Reason.INSUFFICIENT -> R.string.intake_not_enough
        IntakeRejected.Reason.PACKAGE_UNUSABLE -> R.string.intake_package_unusable
        IntakeRejected.Reason.UNIT_MISMATCH -> R.string.intake_unit_mismatch
        IntakeRejected.Reason.EPISODE_CLOSED -> R.string.intake_episode_closed
        IntakeRejected.Reason.PACKAGE_NOT_A_SOURCE -> R.string.intake_not_a_source
    }
