package com.kert0n.medapp.platform.notifications

import com.kert0n.medapp.domain.notification.NotificationOpening
import com.kert0n.medapp.domain.notification.NotificationAction
import com.kert0n.medapp.domain.notification.NotificationTarget
import java.io.File
import java.time.LocalDate
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Цель уведомления после записи в extras читается той же самой — для **каждого** случая
 * `NotificationTarget`: писатель и читатель живут в одном месте, и разойтись им негде (разбор #29).
 * Набор случаев сверяется с исходником типа: новая цель без образца здесь — провал, а не пробел.
 */
class NotificationTargetExtrasTest {

    private val samples: List<NotificationTarget> = listOf(
        NotificationTarget.Intake(Uuid.parse("00000000-0000-4000-8000-000000000001")),
        NotificationTarget.PackageCard(Uuid.parse("00000000-0000-4000-8000-000000000002")),
        NotificationTarget.CourseSources(Uuid.parse("00000000-0000-4000-8000-000000000003")),
        NotificationTarget.DayPlan(LocalDate.of(2027, 3, 10)),
        NotificationTarget.SyncStatus
    )

    @Test
    fun everyTargetSurvivesTheRoundTrip() {
        for (target in samples) {
            assertEquals(NotificationOpening(target, null), NotificationTargetExtras.decode(NotificationTargetExtras.encode(target)))
        }
    }

    /** Кнопка «Принял» — то же намерение с действием: действие читается, а незнакомое не роняет цель. */
    @Test
    fun theActionTravelsWithTheTargetAndAStrangeOneIsIgnored() {
        val intake = samples.first()
        for (action in NotificationAction.entries) {
            assertEquals(NotificationOpening(intake, action), NotificationTargetExtras.decode(NotificationTargetExtras.encode(intake, action)))
        }
        val strange = NotificationTargetExtras.encode(intake) + ("notification_action" to "DANCE")
        assertEquals(NotificationOpening(intake, null), NotificationTargetExtras.decode(strange))
    }

    @Test
    fun theSamplesCoverEveryCaseOfTheType() {
        val source = listOf(
            File("src/main/java/com/kert0n/medapp/domain/notification/NotificationTarget.kt"),
            File("app/src/main/java/com/kert0n/medapp/domain/notification/NotificationTarget.kt")
        ).first { it.isFile }.readText()
        val declared = Regex("""(?:data class|data object|class|object)\s+(\w+)[^\n]*:\s*NotificationTarget""")
            .findAll(source).map { it.groupValues[1] }.toSet()
        val sampled = samples.map { it::class.simpleName!! }.toSet()
        assertEquals("у цели нет образца в проверке round-trip", declared, sampled)
    }

    @Test
    fun aMissingOrForeignTargetReadsAsNothing() {
        assertNull(NotificationTargetExtras.decode(emptyMap()))
        assertNull(NotificationTargetExtras.decode(mapOf("notification_target" to "INTAKE")))
        assertNull(NotificationTargetExtras.decode(mapOf("notification_target" to "INTAKE", "notification_target_id" to "не uuid")))
        assertNull(NotificationTargetExtras.decode(mapOf("notification_target" to "SOMETHING_ELSE")))
    }
}
