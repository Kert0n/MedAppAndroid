package com.kert0n.medapp

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * У показа и у будильника **один** владелец, и это держит проверка, а не внимание (PLAN D8).
 *
 * Разделение простое: `ReminderAlarms` знает, **как** разбудить, и не знает, о чём; `Notifier`
 * знает, **чем** показать, и не знает, когда; `ReminderOutbox` знает, **что** наступило, и
 * единственный зовёт обоих. Стоит второму месту начать будить или показывать — и два владельца
 * начнут делить ответственность: один снимет будильник, который поставил другой, или покажет то,
 * что первый уже пометил сказанным.
 *
 * Довод тот же, что у [LayerBoundariesTest]: договор, который стерегут глазами, живёт до первого
 * невнимательного дня. Нарушение называется файлом, поэтому искать его не приходится.
 */
class NotificationOwnershipTest {

    /** Кому позволено упоминать порт: объявление, реализация, проводка графа — и один владелец. */
    private val mayCall: Map<String, Set<String>> = mapOf(
        "ReminderAlarms" to setOf(
            "domain/notification/ReminderAlarms.kt",
            "platform/notifications/AlarmManagerReminders.kt",
            "di/NotificationModule.kt",
            "feature/notification/ReminderOutbox.kt",
            // Состояние разрешений читает `canBeExact` — ответ владельца будильников, а не второй
            // ответ на тот же вопрос; будить и снимать оно не умеет.
            "platform/settings/AndroidDevicePermissions.kt"
        ),
        "Notifier" to setOf(
            "domain/notification/Notifier.kt",
            "platform/notifications/SystemNotifier.kt",
            "di/NotificationModule.kt",
            "feature/notification/ReminderOutbox.kt",
            // Ответ из шторки гасит свою карточку сразу: человек нажал, и ждать прохода нечего.
            "app/notifications/NotificationActionReceiver.kt"
        )
    )

    /**
     * Кому позволено **звать сверку**. Обещанное следует из состояния, и повод сверить его —
     * изменившиеся основания (`NotificationUpkeep`), смена дня (`DailyRound`) и решение человека о
     * настройках (`SettingsChanging`). Сценарий, позвавший сверку руками «после своей правки»,
     * оставил бы дыру остальным путям записи — снимку, ответу на команду, соседнему сценарию.
     */
    private val mayReconcile: Set<String> = setOf(
        "feature/notification/NotificationUpkeep.kt",
        "feature/notification/DailyRound.kt",
        "feature/settings/SettingsChanging.kt"
    )

    private val sources: File = listOf(
        File("src/main/java/com/kert0n/medapp"),
        File("app/src/main/java/com/kert0n/medapp")
    ).firstOrNull { it.isDirectory } ?: error("исходники не найдены: проверка прошла бы впустую")

    /** Проверка, которая не должна пройти впустую: владельцы на месте и их файлы не пусты. */
    @Test
    fun theOwnersAreWhereWeThinkTheyAre() {
        for (allowed in mayCall.values.flatten().toSet()) {
            val file = File(sources, allowed)
            assertTrue("нет файла $allowed — проверка сторожила бы пустоту", file.isFile && file.length() > 0)
        }
    }

    @Test
    fun onlyTheNamedReasonsCallTheReconciliation() {
        for (allowed in mayReconcile) {
            val file = File(sources, allowed)
            assertTrue("нет файла $allowed — проверка сторожила бы пустоту", file.isFile && file.length() > 0)
        }
        val callers = sources.walkTopDown()
            .filter { it.extension == "kt" }
            .filter { file -> file.readText().contains(Regex("\\.reconcile\\s*\\(")) }
            .map { it.relativeTo(sources).invariantSeparatorsPath }
            .toSortedSet()
        assertEquals("сверку зовут мимо названных поводов", mayReconcile.toSortedSet(), callers)
    }

    @Test
    fun onlyOneOwnerWakesTheSystemAndShows() {
        val offenders = mayCall.mapValues { (port, allowed) ->
            sources.walkTopDown()
                .filter { it.extension == "kt" }
                .filter { file -> file.readText().contains(Regex("\\b$port\\b")) }
                .map { it.relativeTo(sources).invariantSeparatorsPath }
                .filterNot { it in allowed }
                .sorted()
                .toList()
        }.filterValues { it.isNotEmpty() }

        assertEquals(
            "порт уведомлений зовут мимо владельца — у показа и будильника должен быть один хозяин",
            emptyMap<String, List<String>>(),
            offenders
        )
    }
}
