package com.kert0n.medapp.app.navigation

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.HiltTestActivity
import com.kert0n.medapp.domain.value.Dose
import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.value.Quantity
import com.kert0n.medapp.fixture.SHARED_KIT
import com.kert0n.medapp.fixture.TABLETS
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.settle
import com.kert0n.medapp.fixture.storySetting
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.network.pack.PackageSyncState
import com.kert0n.medapp.network.server.ResourceVersion
import com.kert0n.medapp.queue.RefusalReason
import com.kert0n.medapp.queue.SyncCommand
import com.kert0n.medapp.queue.SyncOperationStatus
import com.kert0n.medapp.queue.medkit.MedKitSyncCommand
import com.kert0n.medapp.queue.pack.PackageSyncCommand
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.medkit.toStorageEntity
import com.kert0n.medapp.storage.server.SyncOperationStorageEntity
import com.kert0n.medapp.storage.server.toStorageEntity
import com.kert0n.medapp.ui.theme.MedAppTheme
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.time.Instant
import javax.inject.Inject
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **Истории очереди** (`docs/истории.md`, U6): Тамара, Игорь, Валентина, Юрий, Эдуард — по
 * человеку на каждый исход, который сервер может выдать, и на каждое состояние, в котором строка
 * может застрять. Отказ и статус — не абстракции, а то, что человек однажды увидит на «Опциях».
 *
 * Деловые состояния заводятся **переходами самой строки** (`settle`), как их завело бы хранение:
 * своей SQL-двери у проверки для них нет, и состояния, которого приложение не выражает, она
 * поставить не может.
 *
 * Исключение одно — у Эдуарда: нечитаемую строку приложение не создаёт вовсе, она остаётся от
 * чужой версии формата после обновления, и завести её можно только записью мимо переходов. Тем и
 * проверяется не то, как её сделать, а то, как приложение с ней живёт.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class QueueTroublesStoryTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<HiltTestActivity>()

    @Inject
    lateinit var database: MedAppDatabase

    private val WAIT = 10_000L
    private val at: Instant = Instant.parse("2026-09-17T09:00:00Z")
    private val shelf = medKit(id = SHARED_KIT, name = "Семейная", publication = MedKit.Publication.PUBLISHED, participantCount = 2)
    private val ibuprofen = Uuid.random()
    private val syrup = Uuid.random()

    @Before
    fun setUp() {
        hilt.inject()
        runBlocking {
            database.storySetting()
            database.medKits().upsert(shelf.toStorageEntity())
            box(ibuprofen, "Ибупрофен", tablets("10"))
            box(syrup, "Сироп", tablets("200"))
        }
        compose.setContent { MedAppTheme { MedAppShell() } }
    }

    private suspend fun box(id: Uuid, name: String, quantity: Quantity) {
        database.packageRepository().add(
            pack(id = id, name = name, medKit = shelf.ref, quantity = quantity, form = TABLET_FORM),
            PackageSyncState(id, ResourceVersion(1), ResourceVersion(1), syncedAt = at)
        )
    }

    /** Ставит строку очереди и доводит её до состояния, в котором человек её увидит. */
    private fun queued(
        command: SyncCommand,
        status: SyncOperationStatus,
        reason: RefusalReason? = null
    ): Uuid = runBlocking {
        val id = Uuid.random()
        database.syncOperations().enqueue(id, command, at)
        if (status != SyncOperationStatus.PENDING || reason != null) {
            database.syncOperations().settle(id, status, at = at, refusalReason = reason)
        }
        id
    }

    private fun openSyncStatus() {
        compose.onNodeWithText("Опции").performClick()
        compose.waitUntil(WAIT) { shown("Синхронизация") }
        compose.onAllNodesWithText("Синхронизация").onFirst().performClick()
        compose.waitUntil(WAIT) { shown("Всё доехало") || shown("Разобрал") || shown("Отправится, когда будет связь") }
    }

    private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    /**
     * **Тамара — ответ потерялся по дороге.** Расход стоит в очереди и ждёт связи: исход
     * неизвестен, и приложение не решает за сервер. Человек видит одну строку, а не два списания.
     */
    @Test
    fun tamarasAnswerIsLostAndTheIntakeWaitsInsteadOfDoubling() {
        queued(PackageSyncCommand.Consume(ibuprofen, Dose(tablets("1")), Uuid.random()), SyncOperationStatus.PENDING)

        openSyncStatus()

        compose.onNodeWithText("Приём: «Ибупрофен»").assertIsDisplayed()
        compose.onNodeWithText("Отправится, когда будет связь").assertIsDisplayed()
    }

    /**
     * **Игорь — отказ по остатку и отказ по версии.** Два отказа стоят рядом разными фразами, и
     * оба предлагают пересчёт: спор в обоих о том, что в коробке на самом деле.
     */
    @Test
    fun igorSeesTwoRefusalsToldApartAndBothLeadToRecounting() {
        queued(PackageSyncCommand.Delete(ibuprofen), SyncOperationStatus.REFUSED, RefusalReason.INSUFFICIENT)
        queued(PackageSyncCommand.Delete(syrup), SyncOperationStatus.REFUSED, RefusalReason.CONFLICT)

        openSyncStatus()

        compose.onNodeWithText("На сервере осталось меньше, чем вы списали").assertIsDisplayed()
        compose.onNodeWithText("Лекарство изменили раньше вас").assertIsDisplayed()
        compose.onAllNodesWithText("Пересчитать остаток").assertCountEquals(2)
    }

    /**
     * **Валентина — единица сменилась под руками.** Приложение не пересчитывает миллилитры в
     * ложки само: сколько это, знает человек.
     */
    @Test
    fun valentinasUnitChangedAndTheAppDoesNotGuess() {
        queued(PackageSyncCommand.Delete(syrup), SyncOperationStatus.REFUSED, RefusalReason.UNIT_CHANGED)

        openSyncStatus()

        compose.onNodeWithText("У лекарства сменилась единица измерения").assertIsDisplayed()
    }

    /**
     * **Юрий — цепочка рухнула следом за первым.** Публикация отвергнута, зависимые закрылись
     * вместе с ней, и обе строки названы своими словами.
     */
    @Test
    fun yurysChainFallsWithThePublicationAndSaysSo() {
        queued(MedKitSyncCommand.Publish(SHARED_KIT), SyncOperationStatus.REFUSED, RefusalReason.INVALID)
        queued(PackageSyncCommand.Create(ibuprofen, SHARED_KIT), SyncOperationStatus.REFUSED, RefusalReason.SUPERSEDED)

        openSyncStatus()

        compose.onNodeWithText("Решение об аптечке: «Семейная»").assertIsDisplayed()
        compose.onNodeWithText("Сервер не принял данные").assertIsDisplayed()
        compose.onNodeWithText("Отменено вместе с тем, от чего зависело").assertIsDisplayed()
    }

    /**
     * **Раиса — её выгнали из общей полки.** Утрата доступа решения не ждёт: строка закрыта, и
     * разбирать в ней нечего — на экране её нет.
     */
    @Test
    fun raisaLosesAccessAndHasNothingToDecide() {
        queued(PackageSyncCommand.Delete(ibuprofen), SyncOperationStatus.ACCESS_LOST)

        openSyncStatus()

        compose.onNodeWithText("Всё доехало").assertIsDisplayed()
    }

    /**
     * **Эдуард — строку нечем прочитать.** Она стоит с причиной и без имени — оно лежало внутри
     * неё, — и «Разобрал» убирает её с экрана.
     */
    @Test
    fun eduardsRowCannotBeReadAndIsDismissed() {
        // Строка чужой версии формата: её поля новый код не понимает — ровно то, что бывает
        // после обновления приложения.
        runBlocking {
            val stored = database.syncOperations()
                .enqueue(Uuid.random(), PackageSyncCommand.Delete(ibuprofen), at).toStorageEntity()
            database.syncOperations().update(
                SyncOperationStorageEntity(
                    id = stored.id, kind = stored.kind, payload = stored.payload, payloadVersion = 99,
                    sequence = stored.sequence, status = stored.status, attempts = stored.attempts,
                    createdAt = stored.createdAt, packageId = stored.packageId
                )
            )
        }

        openSyncStatus()

        compose.onNodeWithText("Эту строку нечем прочитать").assertIsDisplayed()
        compose.onNodeWithText("Разобрал").performClick()
        compose.waitUntil(WAIT) { shown("Всё доехало") }
    }
}
