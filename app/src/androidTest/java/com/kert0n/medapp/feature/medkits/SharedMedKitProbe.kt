package com.kert0n.medapp.feature.medkits

import kotlinx.coroutines.flow.first
import com.kert0n.medapp.feature.course.SourceEditing
import com.kert0n.medapp.fixture.confirmed
import com.kert0n.medapp.domain.medkit.InvitationKey
import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.pack.PackageStatus
import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.value.DosageForm
import com.kert0n.medapp.domain.value.Dose
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.feature.course.CourseActivation
import com.kert0n.medapp.feature.course.CourseCalendar
import com.kert0n.medapp.feature.course.CourseDrafting
import com.kert0n.medapp.domain.value.Quantity
import com.kert0n.medapp.domain.value.QuantityUnit
import com.kert0n.medapp.feature.course.CourseClosing
import com.kert0n.medapp.feature.intake.IntakeConfirmation
import com.kert0n.medapp.fixture.MOSCOW
import com.kert0n.medapp.fixture.schedule
import com.kert0n.medapp.fixture.ProbeAccounts
import com.kert0n.medapp.fixture.FakeNotifier
import com.kert0n.medapp.fixture.FakeReminders
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.courseRepository
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.intakeRepository
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.medKitRepository
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.queueService
import com.kert0n.medapp.fixture.queueStorage
import com.kert0n.medapp.fixture.snapshotStorage
import com.kert0n.medapp.fixture.transactions
import com.kert0n.medapp.network.medkit.MembershipPostNetworkDTO
import com.kert0n.medapp.network.medkit.ServerMedKitInvitations
import com.kert0n.medapp.network.server.ApiFailure
import com.kert0n.medapp.network.server.ApiResult
import com.kert0n.medapp.network.server.MedAppApi
import com.kert0n.medapp.network.value.VocabularyResolver
import com.kert0n.medapp.network.value.toDosageForm
import com.kert0n.medapp.network.value.toQuantityUnit
import com.kert0n.medapp.queue.PackageSnapshotResolver
import com.kert0n.medapp.queue.QueueHttpTransport
import com.kert0n.medapp.queue.QueueWorker
import com.kert0n.medapp.queue.SnapshotApplier
import com.kert0n.medapp.queue.StoredSyncOperation
import com.kert0n.medapp.queue.SyncCommand
import com.kert0n.medapp.queue.SyncOperationStatus
import com.kert0n.medapp.queue.intake.IntakeAccounting
import com.kert0n.medapp.feature.packages.PackageRelocation
import com.kert0n.medapp.feature.packages.PackageRemoval
import com.kert0n.medapp.storage.medkit.toStorageEntity as toMedKitStorageEntity
import com.kert0n.medapp.storage.value.VocabularyRoomRepository
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Двое делят аптечку — по-настоящему, против боевого сервера (AGENTS «Связь с сервером»). Анна и
 * Борис — два устройства в одном процессе: у каждого своя база, свой клиент со своей пробной
 * учёткой и своя очередь, собранные из тех же частей, что у приложения. Так условия задаются
 * точно — кто что успел доставить и в каком порядке, — а не гонкой двух эмуляторов.
 *
 * Общая завязка у трёх случаев одна: Анна публикует полку с коробкой, Борис вступает, лечится из
 * этой коробки, и один его приём уже на сервере. Потом Анна убирает полку — к себе в местную, к
 * себе в общую или в общую на двоих, — и проверяется, что сделали обе очереди и оба лечения.
 *
 * Вступление Бориса кладёт полку и её коробки в его базу **здесь, в пробе**: в приложении пока
 * нет сценария, который читает общие полки с сервера. Всё остальное идёт путями приложения.
 *
 * Включается только `-Pprobe`, как и [com.kert0n.medapp.network.server.ContractProbe]. Синтетические
 * полки проба удаляет за собой.
 */
class SharedMedKitProbe {

    companion object {
        private fun <T> success(result: ApiResult<T>): T = when (result) {
            is ApiResult.Success -> result.value
            is ApiResult.Failure -> throw AssertionError("ожидался успех: $result")
        }

        private fun failure(result: ApiResult<*>): ApiFailure =
            (result as? ApiResult.Failure)?.failure ?: throw AssertionError("ожидался отказ: $result")
    }

    private lateinit var anna: Device
    private lateinit var boris: Device
    private lateinit var unit: QuantityUnit
    private lateinit var form: DosageForm
    private val shelves = mutableListOf<Uuid>()

    @Before
    fun open(): Unit = runBlocking {
        // Клиенты общие на весь прогон: пропуск выдаётся один раз на пользователя (ProbeAccounts).
        val skipReason = ProbeAccounts.skipReason
        assumeTrue(skipReason.orEmpty(), skipReason == null)
        anna = Device(requireNotNull(ProbeAccounts.anna))
        boris = Device(requireNotNull(ProbeAccounts.boris))
        success(anna.vocabulary.refresh())
        success(boris.vocabulary.refresh())
        unit = success(anna.api.quantityUnits()).first().toQuantityUnit()
        form = success(anna.api.formTypes()).first().toDosageForm()
    }

    @After
    fun close(): Unit = runBlocking {
        if (ProbeAccounts.skipReason != null) return@runBlocking
        // Полку, из которой Анна вышла, она удалить не может: сервер отвечает ей «нет такой», а у
        // Бориса полка жива. Поэтому убирают за собой оба, и верит проба не отказу на удаление —
        // `NotFound` не говорит, нет полки или нет доступа, — а тому, что полки не видит никто.
        val left = shelves.filter { shelf ->
            anna.api.deleteMedKit(shelf)
            boris.api.deleteMedKit(shelf)
            listOf(anna, boris).any { device ->
                val seen = device.api.medKit(shelf)
                seen !is ApiResult.Failure || seen.failure != ApiFailure.NotFound
            }
        }
        anna.database.close()
        boris.database.close()
        if (left.isNotEmpty()) throw AssertionError("синтетические полки остались на боевом сервере: $left")
    }

    private fun pills(amount: String) = Quantity(BigDecimal(amount), unit)

    private fun twoPills() = Dose(pills("2"))

    /** Полка, коробка и лечение Бориса из неё; первый его приём уже доставлен. */
    private class Shared(val shelf: Uuid, val box: Uuid, val intakes: List<Uuid>)

    private suspend fun sharedShelfWithBorisTreated(): Shared {
        val shelf = anna.localShelf("Общая")
        val box = anna.addBox(shelf, "20")
        anna.publish(shelf)
        boris.join(anna.invite(shelf))
        val intakes = boris.treatFrom(box)

        boris.confirm(intakes[0], box)
        boris.drain()

        assertEquals(IntakeAccounting.REMOTE_APPLIED, boris.accountingOf(intakes[0]))
        assertAmount("18", serverAmount(box))
        assertNotNull("бронь Бориса легла вместе с расходом", success(boris.api.packageSnapshot(box)).claims.mine)
        return Shared(shelf, box, intakes)
    }

    /**
     * Анна забрала полку домой, в свою местную. Сервер коробку забыл и полку удалил у всех; у Анны
     * коробка дома, обычная и с тем, что в ней осталось на самом деле, — приём Бориса, доставленный
     * раньше, учтён. Борис узнаёт об этом своим следующим приёмом: коробки у него больше нет,
     * последний виденный остаток уходит в историю, лечение теряет источник и продолжается.
     */
    @Test
    fun annaTakesTheShelfHomeIntoHerLocalOne(): Unit = runBlocking {
        val shared = sharedShelfWithBorisTreated()
        val home = anna.localShelf("Дом")

        assertEquals(MedKitRemoval.Outcome.MARKED, anna.scenarios().medKitRemoval.remove(shared.shelf, MedKitRemoval.Fate.MoveTo(home)))
        anna.drain()

        assertEquals(listOf(SyncOperationStatus.APPLIED), anna.statuses().distinct())
        val carried = requireNotNull(anna.packages.find(shared.box))
        assertEquals(home, carried.medKit.id)
        assertEquals(PackageStatus.ACTIVE, carried.status)
        assertAmount("18", carried.quantity.amount)
        assertNull(anna.database.medKits().find(shared.shelf))
        assertEquals(ApiFailure.NotFound, failure(anna.api.packageSnapshot(shared.box)))

        boris.confirm(shared.intakes[1], shared.box)
        boris.drain()

        assertEquals(IntakeAccounting.REMOTE_REFUSED, boris.accountingOf(shared.intakes[1]))
        assertBorisLostTheBox(shared.box)
    }

    /**
     * Анна переставила полку в свою общую, где Бориса нет. Сервер переставил коробку сам, а бронь
     * Бориса снял: бронь держит тот, кто коробку видит. У Анны коробка на новой полке и обычная. Борис полки не видит: приём
     * кончается утратой доступа, лечение теряет источник.
     */
    @Test
    fun annaMovesTheShelfIntoHerOwnSharedOne(): Unit = runBlocking {
        val shared = sharedShelfWithBorisTreated()
        val own = anna.localShelf("Моя общая")
        anna.publish(own)

        assertEquals(MedKitRemoval.Outcome.MARKED, anna.scenarios().medKitRemoval.remove(shared.shelf, MedKitRemoval.Fate.MoveTo(own)))
        anna.drain()

        assertEquals(listOf(SyncOperationStatus.APPLIED), anna.statuses().distinct())
        val moved = requireNotNull(anna.packages.find(shared.box))
        assertEquals(own, moved.medKit.id)
        assertEquals(PackageStatus.ACTIVE, moved.status)
        assertNull(anna.database.medKits().find(shared.shelf))
        val onServer = success(anna.api.packageSnapshot(shared.box))
        assertEquals(own, onServer.pack.medKitId)
        // Бронь держит тот, кто коробку видит: Бориса на новой полке нет, и сервер его бронь снял.
        assertEquals("бронь Бориса снята вместе с доступом", 0, BigDecimal(onServer.claims.total).signum())

        boris.confirm(shared.intakes[1], shared.box)
        boris.drain()

        assertEquals(IntakeAccounting.REMOTE_REFUSED, boris.accountingOf(shared.intakes[1]))
        assertBorisLostTheBox(shared.box)
    }

    /**
     * Анна переставила полку в другую общую, где они оба. Коробка перепрыгнула на полку, которую
     * Борис знает: его приём доставляется, коробка у него на новой полке, лечение её держит, бронь
     * его.
     */
    @Test
    fun annaMovesTheShelfIntoAnotherOneTheyShare(): Unit = runBlocking {
        val shared = sharedShelfWithBorisTreated()
        val dacha = anna.localShelf("Дача")
        anna.publish(dacha)
        boris.join(anna.invite(dacha))

        assertEquals(MedKitRemoval.Outcome.MARKED, anna.scenarios().medKitRemoval.remove(shared.shelf, MedKitRemoval.Fate.MoveTo(dacha)))
        anna.drain()

        assertEquals(listOf(SyncOperationStatus.APPLIED), anna.statuses().distinct())
        assertEquals(dacha, requireNotNull(anna.packages.find(shared.box)).medKit.id)

        boris.confirm(shared.intakes[1], shared.box)
        boris.drain()

        assertEquals(listOf(SyncOperationStatus.APPLIED), boris.statuses().distinct())
        assertEquals(IntakeAccounting.REMOTE_APPLIED, boris.accountingOf(shared.intakes[1]))
        val jumped = requireNotNull(boris.packages.find(shared.box))
        assertEquals(dacha, jumped.medKit.id)
        assertAmount("16", jumped.quantity.amount)
        assertEquals(listOf(shared.box), boris.database.courses().sourcePackagesOf(boris.course))
        assertNotNull(success(boris.api.packageSnapshot(shared.box)).claims.mine)
    }

    /**
     * Анна переставила **одну коробку** в общую полку, которую Борис тоже видит. Сервер сохраняет
     * его бронь — она держится на том, что он видит коробку, — и его приём доставляется как ни в
     * чём не бывало. Полка, с которой коробку забрали, у обоих остаётся.
     */
    @Test
    fun annaMovesTheBoxIntoAShelfBorisAlsoSees(): Unit = runBlocking {
        val shared = sharedShelfWithBorisTreated()
        val dacha = anna.localShelf("Дача")
        anna.publish(dacha)
        boris.join(anna.invite(dacha))

        assertEquals(PackageRelocation.Outcome.MARKED, anna.scenarios().packageRelocation.move(shared.box, dacha))
        anna.drain()

        assertEquals(listOf(SyncOperationStatus.APPLIED), anna.statuses().distinct())
        val moved = requireNotNull(anna.packages.find(shared.box))
        assertEquals(dacha, moved.medKit.id)
        assertEquals(PackageStatus.ACTIVE, moved.status)
        // Бронь спрашивается у самого Бориса: `mine` в снимке — доля того, кто его читает.
        assertNotNull("бронь Бориса цела: он видит цель", success(boris.api.packageSnapshot(shared.box)).claims.mine)
        assertNotNull("полка Анны на месте", anna.database.medKits().find(shared.shelf))

        boris.confirm(shared.intakes[1], shared.box)
        boris.drain()

        assertEquals(IntakeAccounting.REMOTE_APPLIED, boris.accountingOf(shared.intakes[1]))
        assertEquals(dacha, requireNotNull(boris.packages.find(shared.box)).medKit.id)
        assertEquals(listOf(shared.box), boris.database.courses().sourcePackagesOf(boris.course))
    }

    /**
     * Анна переставила одну коробку в свою общую полку, где Бориса нет. Для него коробка исчезла:
     * сервер снял его бронь вместе с доступом, следующий приём кончается утратой доступа, а лечение
     * теряет источник и продолжается.
     */
    @Test
    fun annaMovesTheBoxIntoAShelfBorisDoesNotSee(): Unit = runBlocking {
        val shared = sharedShelfWithBorisTreated()
        val own = anna.localShelf("Моя общая")
        anna.publish(own)

        assertEquals(PackageRelocation.Outcome.MARKED, anna.scenarios().packageRelocation.move(shared.box, own))
        anna.drain()

        assertEquals(listOf(SyncOperationStatus.APPLIED), anna.statuses().distinct())
        assertEquals(own, requireNotNull(anna.packages.find(shared.box)).medKit.id)
        assertEquals(
            "бронь Бориса снята вместе с доступом", 0,
            BigDecimal(success(anna.api.packageSnapshot(shared.box)).claims.total).signum()
        )

        boris.confirm(shared.intakes[1], shared.box)
        boris.drain()

        assertEquals(IntakeAccounting.REMOTE_REFUSED, boris.accountingOf(shared.intakes[1]))
        assertBorisLostTheBox(shared.box)
    }

    /**
     * Анна унесла **одну коробку** домой. Она сразу у неё, местная и с тем, что в ней осталось на
     * самом деле: приём Бориса, доставленный раньше, учтён. Сервер коробку забыл, полка осталась —
     * Борис узнаёт об этом своим следующим приёмом.
     */
    @Test
    fun annaCarriesTheBoxHome(): Unit = runBlocking {
        val shared = sharedShelfWithBorisTreated()
        val home = anna.localShelf("Дом")

        assertEquals(PackageRelocation.Outcome.MOVED, anna.scenarios().packageRelocation.move(shared.box, home))
        anna.drain()

        assertEquals(listOf(SyncOperationStatus.APPLIED), anna.statuses().distinct())
        val carried = requireNotNull(anna.packages.find(shared.box))
        assertEquals(home, carried.medKit.id)
        assertEquals(PackageStatus.ACTIVE, carried.status)
        assertAmount("18", carried.quantity.amount)
        assertEquals(ApiFailure.NotFound, failure(anna.api.packageSnapshot(shared.box)))
        assertNotNull("полка осталась у всех", anna.database.medKits().find(shared.shelf))

        boris.confirm(shared.intakes[1], shared.box)
        boris.drain()

        assertEquals(IntakeAccounting.REMOTE_REFUSED, boris.accountingOf(shared.intakes[1]))
        assertBorisLostTheBox(shared.box)
    }

    /**
     * Анна выбросила **одну коробку** из общей полки. До ответа сервера коробка у неё цела и
     * помечена — иначе отказ уничтожил бы то, что у Бориса живо, — а ответ уносит её со следом
     * утилизации. У Бориса это утрата доступа, и лечение теряет источник.
     */
    @Test
    fun annaThrowsTheBoxAway(): Unit = runBlocking {
        val shared = sharedShelfWithBorisTreated()

        assertEquals(PackageRemoval.Outcome.MARKED, anna.scenarios().packageRemoval.remove(shared.box))
        assertEquals(PackageStatus.REMOVING, requireNotNull(anna.packages.find(shared.box)).status)
        anna.drain()

        assertEquals(listOf(SyncOperationStatus.APPLIED), anna.statuses().distinct())
        assertNull(anna.packages.find(shared.box))
        assertEquals(ApiFailure.NotFound, failure(anna.api.packageSnapshot(shared.box)))

        boris.confirm(shared.intakes[1], shared.box)
        boris.drain()

        assertEquals(IntakeAccounting.REMOTE_REFUSED, boris.accountingOf(shared.intakes[1]))
        assertBorisLostTheBox(shared.box)
    }

    /**
     * Анна оставила лекарства остальным и вышла из полки. У неё коробки потеряны, а полки нет в
     * списке; у Бориса полка и коробка целы, и лечение идёт дальше как шло.
     */
    @Test
    fun annaLeavesTheShelfToTheOthers(): Unit = runBlocking {
        val shared = sharedShelfWithBorisTreated()
        val coverageBefore = boris.database.courseRepository().observeCoverage(boris.course).first()

        assertEquals(
            MedKitRemoval.Outcome.MARKED,
            anna.scenarios().medKitRemoval.remove(shared.shelf, MedKitRemoval.Fate.LeaveToOthers)
        )
        assertEquals(PackageStatus.LOST, requireNotNull(anna.packages.find(shared.box)).status)
        anna.drain()

        assertEquals(listOf(SyncOperationStatus.APPLIED), anna.statuses().distinct())
        assertNull(anna.database.medKits().find(shared.shelf))
        assertNull(anna.packages.find(shared.box))
        // Выход Анны обеспечения Бориса не трогает: оно то же, что до её выхода, — и после его сверки.
        boris.refresh()
        assertEquals(coverageBefore, boris.database.courseRepository().observeCoverage(boris.course).first())

        boris.confirm(shared.intakes[1], shared.box)
        boris.drain()

        assertEquals(IntakeAccounting.REMOTE_APPLIED, boris.accountingOf(shared.intakes[1]))
        // Спрашивает Борис: вышедшая Анна коробку уже не видит, и это тоже часть ожидаемого.
        assertAmount("16", BigDecimal(success(boris.api.packageSnapshot(shared.box)).pack.amount))
        assertEquals(ApiFailure.NotFound, failure(anna.api.packageSnapshot(shared.box)))
        assertEquals(listOf(shared.box), boris.database.courses().sourcePackagesOf(boris.course))
    }

    /**
     * Борис выходит из полки Анны: его лечение остаётся идти — теряется только источник с
     * покинутой полки, вторая коробка дома держится, приёмы на месте (PLAN C1 «Курсы при выходе»).
     */
    @Test
    fun borisLeavesTheShelfAndKeepsHisCourse(): Unit = runBlocking {
        val shared = sharedShelfWithBorisTreated()
        val home = boris.addBox(boris.localShelf("Дом Бориса"), "10")
        boris.attachSecondSource(home)

        assertEquals(MedKitRemoval.Outcome.MARKED, boris.scenarios().medKitRemoval.remove(shared.shelf, MedKitRemoval.Fate.LeaveToOthers))
        boris.drain()

        assertEquals(listOf(SyncOperationStatus.APPLIED), boris.statuses().distinct())
        assertNull(boris.database.medKits().find(shared.shelf))
        assertNull(boris.packages.find(shared.box))
        assertEquals(listOf(home), boris.database.courses().sourcePackagesOf(boris.course))
        assertTrue(requireNotNull(boris.database.courses().findRecord(boris.course)).toDomain(boris.vocabulary.snapshot()).isOpen)
        assertEquals(IntakeAccounting.REMOTE_APPLIED, boris.accountingOf(shared.intakes[0]))
        // У Анны коробка на месте, с тем, что Борис успел принять.
        assertAmount("18", serverAmount(shared.box))
    }

    /** Борис унёс коробку домой до выхода: источник остаётся, выход его не касается. */
    @Test
    fun borisCarriesTheBoxHomeBeforeLeaving(): Unit = runBlocking {
        val shared = sharedShelfWithBorisTreated()
        val home = boris.localShelf("Дом Бориса")

        assertEquals(PackageRelocation.Outcome.MOVED, boris.scenarios().packageRelocation.move(shared.box, home))
        boris.drain()
        assertEquals(MedKitRemoval.Outcome.MARKED, boris.scenarios().medKitRemoval.remove(shared.shelf, MedKitRemoval.Fate.LeaveToOthers))
        boris.drain()

        assertEquals(listOf(SyncOperationStatus.APPLIED), boris.statuses().distinct())
        val carried = requireNotNull(boris.packages.find(shared.box))
        assertEquals(home, carried.medKit.id)
        assertEquals(listOf(shared.box), boris.database.courses().sourcePackagesOf(boris.course))
        assertEquals(ApiFailure.NotFound, failure(anna.api.packageSnapshot(shared.box)))
    }

    /**
     * Анна выбросила полку целиком, вместе с лекарствами. Полка исчезает у всех: у Анны остаются
     * следы утилизации, у Бориса — утрата доступа и лечение без источника.
     */
    @Test
    fun annaThrowsTheWholeShelfAway(): Unit = runBlocking {
        val shared = sharedShelfWithBorisTreated()

        assertEquals(
            MedKitRemoval.Outcome.MARKED,
            anna.scenarios().medKitRemoval.remove(shared.shelf, MedKitRemoval.Fate.ThrowAway)
        )
        assertEquals(PackageStatus.REMOVING, requireNotNull(anna.packages.find(shared.box)).status)
        anna.drain()

        assertEquals(listOf(SyncOperationStatus.APPLIED), anna.statuses().distinct())
        assertNull(anna.database.medKits().find(shared.shelf))
        assertNull(anna.packages.find(shared.box))
        assertEquals(ApiFailure.NotFound, failure(anna.api.medKit(shared.shelf)))

        boris.confirm(shared.intakes[1], shared.box)
        boris.drain()

        assertEquals(IntakeAccounting.REMOTE_REFUSED, boris.accountingOf(shared.intakes[1]))
        assertBorisLostTheBox(shared.box)
    }

    /**
     * Борис подтвердил приём офлайн **до** того, как Анна переставила полку, а доставил **после**.
     * Приём не подвешивается и не теряется: коробка перепрыгнула на общую полку, которую он знает,
     * расход применён, лечение держит коробку.
     */
    @Test
    fun borisConfirmsOfflineBeforeTheMoveAndDeliversAfterIt(): Unit = runBlocking {
        val shared = sharedShelfWithBorisTreated()
        val dacha = anna.localShelf("Дача")
        anna.publish(dacha)
        boris.join(anna.invite(dacha))

        boris.confirm(shared.intakes[1], shared.box)
        assertEquals(MedKitRemoval.Outcome.MARKED, anna.scenarios().medKitRemoval.remove(shared.shelf, MedKitRemoval.Fate.MoveTo(dacha)))
        anna.drain()
        boris.drain()

        assertEquals(listOf(SyncOperationStatus.APPLIED), anna.statuses().distinct())
        assertEquals(listOf(SyncOperationStatus.APPLIED), boris.statuses().distinct())
        assertEquals(IntakeAccounting.REMOTE_APPLIED, boris.accountingOf(shared.intakes[1]))
        val jumped = requireNotNull(boris.packages.find(shared.box))
        assertEquals(dacha, jumped.medKit.id)
        assertAmount("16", jumped.quantity.amount)
        assertAmount("16", serverAmount(shared.box))
        assertEquals(listOf(shared.box), boris.database.courses().sourcePackagesOf(boris.course))
    }

    /**
     * У Анны свой приём из общей коробки ещё не доставлен, когда она уносит полку домой. Полка ждёт
     * его по номеру: сначала расход, потом унос, потом удаление полки. Дома у Анны то, что осталось
     * на самом деле, — минус приём Бориса и её собственный; её лечение держит коробку.
     */
    @Test
    fun annasEarlierIntakeIsDeliveredBeforeSheCarriesTheShelfHome(): Unit = runBlocking {
        val shared = sharedShelfWithBorisTreated()
        val home = anna.localShelf("Дом")
        val annas = anna.treatFrom(shared.box)
        anna.confirm(annas[0], shared.box)

        assertEquals(MedKitRemoval.Outcome.MARKED, anna.scenarios().medKitRemoval.remove(shared.shelf, MedKitRemoval.Fate.MoveTo(home)))
        anna.drain()

        val delivered = anna.operations()
        val words = anna.vocabulary.snapshot()
        val stuck = anna.database.syncOperations().all()
            .map { (it.toDomain(words) as StoredSyncOperation.Readable).operation }
            .filter { it.status != SyncOperationStatus.APPLIED }
        assertTrue(
            "не доставлено: " + stuck.joinToString { "${it.command} ${it.status} «${it.lastError}» до ${it.notBefore}" },
            stuck.isEmpty()
        )
        // Порядок полки: расход, поставленный раньше, уезжает раньше уноса — иначе сервер снял бы
        // коробку с полки, не узнав о нём.
        val order = delivered.map { it.first }
        val consumed = order.indexOfFirst { it is com.kert0n.medapp.queue.pack.PackageSyncCommand.Consume }
        val withdrawn = order.indexOfFirst { it is com.kert0n.medapp.queue.pack.PackageSyncCommand.Withdraw }
        assertTrue("расход доставлен раньше уноса: $order", consumed in 0 until withdrawn)
        assertEquals(IntakeAccounting.REMOTE_APPLIED, anna.accountingOf(annas[0]))
        val carried = requireNotNull(anna.packages.find(shared.box))
        assertEquals(home, carried.medKit.id)
        assertEquals(PackageStatus.ACTIVE, carried.status)
        assertAmount("16", carried.quantity.amount)
        assertEquals(listOf(shared.box), anna.database.courses().sourcePackagesOf(anna.course))
    }

    /**
     * Анна переставила полку в общую, куда Борис вступил **только на сервере**: у него на устройстве
     * этой полки нет. Снимок называет незнакомую полку — это не «подождать», а утрата доступа:
     * операция закрыта, а не отложена навсегда; коробки у Бориса нет, лечение без источника (E6).
     * Следующий полный снимок приносит Борису и полку, и коробку на ней: сервер назвал полку нашей,
     * и у нас её не было (E4).
     */
    @Test
    fun theBoxJumpsIntoAShelfBorisDoesNotHaveOnHisDevice(): Unit = runBlocking {
        val shared = sharedShelfWithBorisTreated()
        val dacha = anna.localShelf("Дача")
        anna.publish(dacha)
        success(boris.api.joinMedKit(MembershipPostNetworkDTO(anna.invite(dacha))))

        assertEquals(MedKitRemoval.Outcome.MARKED, anna.scenarios().medKitRemoval.remove(shared.shelf, MedKitRemoval.Fate.MoveTo(dacha)))
        anna.drain()
        boris.confirm(shared.intakes[1], shared.box)
        boris.drain()

        assertTrue("операции Бориса закрыты, а не ждут", boris.statuses().none { it == SyncOperationStatus.PENDING || it == SyncOperationStatus.ANSWERED })
        assertEquals(IntakeAccounting.REMOTE_REFUSED, boris.accountingOf(shared.intakes[1]))
        assertBorisLostTheBox(shared.box)

        // У Бориса на сервере могут быть и другие полки, а база у пробы всякий раз свежая: снимок
        // приносит все, каких у него нет, и дача среди них.
        boris.refresh()
        assertEquals("Общая аптечка", requireNotNull(boris.database.medKits().find(dacha)).name)
        assertEquals(dacha, requireNotNull(boris.packages.find(shared.box)).medKit.id)
    }

    /** Коробки у Бориса нет, лечение без источника, но идёт. */
    private suspend fun assertBorisLostTheBox(box: Uuid) {
        assertNull(boris.packages.find(box))
        assertEquals(emptyList<Uuid>(), boris.database.courses().sourcePackagesOf(boris.course))
        assertNotNull(boris.database.courses().findRecord(boris.course))
    }

    private suspend fun serverAmount(box: Uuid): BigDecimal = BigDecimal(success(anna.api.packageSnapshot(box)).pack.amount)

    private fun assertAmount(expected: String, actual: BigDecimal) =
        assertEquals("ожидалось $expected, а не $actual", 0, BigDecimal(expected).compareTo(actual))

    /** Устройство одного человека: своя база, свой клиент, своя очередь — те же части, что у приложения. */
    private inner class Device(val api: MedAppApi) {
        val database = inMemoryDatabase()
        private val clock = Clock.systemUTC()
        private val transactions = database.transactions()
        val vocabulary = VocabularyResolver(VocabularyRoomRepository(database.vocabulary()), api)
        private val snapshots = PackageSnapshotResolver(vocabulary, database.queueStorage())
        private val reading = SnapshotApplier(api, database.snapshotStorage(), vocabulary, snapshots, clock)
        private val joining = MedKitJoining(reading)
        private val worker = QueueWorker(database.queueStorage(), QueueHttpTransport(api), vocabulary, snapshots, clock)
        val packages = database.packageRepository()
        private val courses = database.courseRepository()
        private val queue = database.queueService()
        private val medKits = database.medKitRepository()
        private val invitation = MedKitInvitation(medKits, ServerMedKitInvitations(api), reading, Duration.ofMinutes(60), clock)
        private val relocation = PackageRelocation(packages, medKits, courses, queue, transactions, clock)
        private val publishing = MedKitPublishing(medKits, packages, relocation, queue, transactions, clock)
        private val reminderStore = com.kert0n.medapp.storage.notification.ReminderRoomRepository(database, database.reminders())
        private val withdrawal = com.kert0n.medapp.feature.notification.ReminderWithdrawal(reminderStore)
        private val promising = com.kert0n.medapp.feature.notification.ReminderPromising(reminderStore)
        private val confirmation = IntakeConfirmation(
            database.intakeRepository(), courses, packages, transactions, queue, CourseClosing(courses, packages, queue, withdrawal),
            CourseCalendar(database.intakeRepository(), packages, promising, withdrawal), withdrawal, clock
        )

        fun scenarios() = Scenarios(database, clock.instant())

        suspend fun localShelf(name: String): Uuid {
            val id = Uuid.random()
            database.medKits().upsert(medKit(id = id, name = name).toMedKitStorageEntity())
            shelves += id
            return id
        }

        suspend fun addBox(shelf: Uuid, amount: String): Uuid {
            val id = Uuid.random()
            packages.add(pack(id = id, medKit = medKit(id = shelf).ref, quantity = pills(amount), form = form))
            return id
        }

        /**
         * Публикация при связи — решение и один проход очереди: полка становится общей вместе со
         * своими коробками, и незакрытых команд после неё не остаётся (PLAN E5).
         */
        suspend fun publish(shelf: Uuid) {
            assertEquals(MedKitPublishing.Outcome.PUBLISHING, publishing.publish(shelf))
            drain()
            val published = requireNotNull(medKits.find(shelf))
            assertEquals(MedKit.Publication.PUBLISHED, published.publication)
            assertTrue("полка не доведена: ${published.status}", published.acceptsInvitations)
        }

        /**
         * Вступление — сценарием приложения: полка приходит «Общей аптечкой» вместе с коробками, и
         * класть её руками пробе больше нечем (PLAN C0).
         */
        suspend fun join(invitation: String) {
            val outcome = joining.join(InvitationKey(invitation))
            val joined = (outcome as? MedKitJoining.Outcome.Joined)?.medKitId
                ?: throw AssertionError("вступление не состоялось: $outcome")
            assertEquals("Общая аптечка", requireNotNull(database.medKits().find(joined)).name)
        }

        /** Приглашение сценарием приложения: звать можно только в полку, уехавшую целиком (PLAN D2). */
        suspend fun invite(shelf: Uuid): String {
            val outcome = invitation.invite(shelf)
            return (outcome as? MedKitInvitation.Outcome.Invited)?.invitation?.key?.value
                ?: throw AssertionError("приглашение не выдано: $outcome")
        }

        /** Полный снимок, как его читает приложение: легло всё, что названо. */
        suspend fun refresh() {
            val outcome = reading.refresh()
            val applied = outcome as? SnapshotApplier.Outcome.Applied ?: throw AssertionError("снимок не прочитан: $outcome")
            assertEquals("пропуски снимка: ${applied.skipped}", emptyList<String>(), applied.skipped)
        }

        /** Лечение этого устройства: номер эпизода, когда оно начато. */
        lateinit var course: Uuid

        /**
         * Лечение из коробки сценариями приложения — черновик и начало: пять доз по две штуки
         * выделено, раз в день с сегодняшнего дня. Бронь уезжает командой начала, а три ближайших
         * плановых пункта строит календарь.
         */
        suspend fun treatFrom(box: Uuid): List<Uuid> {
            val scenarios = scenarios()
            val created = scenarios.courseDrafting.create("Лечение из коробки")
            val today = LocalDate.now(MOSCOW)
            val edited = scenarios.courseDrafting.edit(
                created.id,
                created.revision,
                listOf(
                    CourseDrafting.Edit.SetDose(twoPills()),
                    CourseDrafting.Edit.SetForm(form),
                    CourseDrafting.Edit.SetSchedule(schedule(start = today, zone = MOSCOW)),
                    CourseDrafting.Edit.SetTotalDoses(Doses(7)),
                    CourseDrafting.Edit.Attach(box, Doses(5))
                )
            )
            val draft = (edited as? CourseDrafting.Outcome.Saved)?.draft ?: throw AssertionError("черновик не сохранён: $edited")
            val started = scenarios.courseActivation.activate(draft.id, draft.revision)
            assertTrue("лечение не началось: $started", started is CourseActivation.Outcome.Started)
            course = draft.id
            return database.intakeRepository().ofCourse(course).filterIsInstance<CourseIntake>()
                .sortedBy { it.plannedAt }.take(3).map { it.id }
        }

        /** Вторая коробка в лечение, последней в расходе, с одной дозой. */
        suspend fun attachSecondSource(box: Uuid) {
            val plan = requireNotNull(courses.findPlan(course))
            val sources = plan.sources.map { SourceEditing.Source(it.pkg.id, it.allocatedDoses) } + SourceEditing.Source(box, Doses(1))
            val outcome = scenarios().sourceEditing.save(course, plan.revision, sources)
            assertTrue("источник не подключён: $outcome", outcome is SourceEditing.Outcome.Saved)
        }

        suspend fun confirm(intake: Uuid, box: Uuid) {
            confirmation.confirm(intake, box, twoPills(), clock.instant()).confirmed()
        }

        /** Проход очереди при связи; сбой шага — провал пробы, а не тихий повтор. */
        suspend fun drain() {
            val report = worker.drain()
            assertEquals("сбои прохода: ${report.failed}", emptyList<QueueWorker.Report.Failure>(), report.failed)
            assertEquals("нечитаемые строки: ${report.skipped}", 0, report.skipped.size)
        }

        suspend fun statuses(): List<SyncOperationStatus> = operations().map { it.second }

        suspend fun operations(): List<Pair<SyncCommand, SyncOperationStatus>> {
            val words = vocabulary.snapshot()
            return database.syncOperations().all()
                .map { (it.toDomain(words) as StoredSyncOperation.Readable).operation }
                .map { it.command to it.status }
        }

        suspend fun accountingOf(intake: Uuid): IntakeAccounting =
            requireNotNull(database.intakes().findEntity(intake)).accounting
    }
}
