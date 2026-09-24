package com.kert0n.medapp.network.delivery

import com.kert0n.medapp.network.server.ApiFailure
import com.kert0n.medapp.queue.DeliveryStatus
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Курьер переводит провод на язык поручения: каждый отказ сервера — в статус, по которому очередь
 * решает, что делать дальше. Кодов HTTP за пределы сети не уходит; сам курьер ничего не решает —
 * повторы, пересборку и закрытие проверяет `QueueWorkerTest` через этого же курьера.
 */
class MedAppCourierTest {

    @Test
    fun everyRefusalOfTheWireBecomesAStatusOfTheErrand() {
        val cases = mapOf(
            ApiFailure.Invalid(emptyList()) to DeliveryStatus.Invalid,
            ApiFailure.Unauthorized to DeliveryStatus.NoPass,
            ApiFailure.RegistrationRefused to DeliveryStatus.NoPass,
            ApiFailure.NotFound to DeliveryStatus.Absent,
            ApiFailure.Conflict to DeliveryStatus.Taken,
            ApiFailure.PreconditionFailed to DeliveryStatus.Outdated,
            ApiFailure.PreconditionRequired to DeliveryStatus.VersionMissing,
            ApiFailure.TooManyRequests(30.seconds) to DeliveryStatus.Throttled(30.seconds),
            ApiFailure.TooManyRequests(null) to DeliveryStatus.Throttled(null),
            ApiFailure.Unavailable to DeliveryStatus.Unreachable,
            ApiFailure.OutcomeUnknown to DeliveryStatus.OutcomeUnknown,
            ApiFailure.Protocol("html") to DeliveryStatus.Garbled("html")
        )

        for ((failure, status) in cases) assertEquals("$failure", status, failure.asStatus())
    }
}
