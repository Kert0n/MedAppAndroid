package com.kert0n.medapp.queue.pack

import com.kert0n.medapp.domain.pack.Claims
import com.kert0n.medapp.domain.pack.PackageSharedFacts
import com.kert0n.medapp.fixture.EARLIER
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.INTAKE
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.SHARED_KIT
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.millilitres
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.network.delivery.toPreparedRequest
import com.kert0n.medapp.queue.Expected
import com.kert0n.medapp.queue.Preparation
import com.kert0n.medapp.queue.RefusalReason
import com.kert0n.medapp.queue.ResourceVersion
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Команда становится запросом один раз, с предусловиями пачки на этот момент (PLAN E2). */
class PackageSyncCommandPreparationTest {

    private val sync = PackageSyncState(PACK, version = ResourceVersion(3), claimsVersion = ResourceVersion(5))

    private fun PackageSyncCommand.prepared(
        mine: com.kert0n.medapp.domain.value.Quantity? = null,
        known: PackageSharedFacts? = null
    ) = toPreparedRequest(INTAKE, sync, confirmed = tablets("20"), mine = mine, at = EARLIER, known = known)

    /** Внеплановый расход — тот же `sync` без блока брони: номер есть, повтор сервер применит один раз. */
    @Test
    fun consumeOutsideACourseIsASyncWithoutAReservationBlock() {
        val request = PackageSyncCommand.Consume(PACK, dose("3"), INTAKE).prepared()
        assertEquals("PUT", request.method)
        assertEquals("/v1/drugs/$PACK/sync/$INTAKE", request.path)
        assertTrue(request.body!!.contains("\"drugVersion\":3"))
        assertFalse(request.body.contains("reservation"))
        assertEquals(ResourceVersion(3), request.drugVersion)
        assertEquals(tablets("20"), request.quantityBefore)
        assertEquals(EARLIER, request.preparedAt)
    }

    /** «Подумали» по свежей пачке: чужая единица — отказ до провода, желаемое уже так — применено. */
    @Test
    fun preparationRefusesAForeignUnitAndRecognisesWhatIsAlreadySo() {
        val syrup = pack(quantity = millilitres("100"))
        assertEquals(
            Preparation.Refuse(RefusalReason.UNIT_CHANGED),
            PackageSyncCommand.Consume(PACK, dose("3"), INTAKE).prepare(syrup, sync)
        )
        // Бронь и пересчёт тоже везут голое число: сервер прочёл бы таблетки миллилитрами.
        assertEquals(
            Preparation.Refuse(RefusalReason.UNIT_CHANGED),
            PackageSyncCommand.SetClaim(PACK, tablets("6")).prepare(syrup, sync)
        )
        assertEquals(
            Preparation.Refuse(RefusalReason.UNIT_CHANGED),
            PackageSyncCommand.CorrectStock(PACK, tablets("20"), tablets("17")).prepare(syrup, sync)
        )
        val claimed = pack(quantity = tablets("20"), claims = Claims(BigDecimal("6"), BigDecimal("6")))
        assertEquals(Preparation.AlreadyApplied, PackageSyncCommand.SetClaim(PACK, tablets("6")).prepare(claimed, sync))
        assertTrue(PackageSyncCommand.SetClaim(PACK, tablets("7")).prepare(claimed, sync) is Preparation.Send)
        val unclaimed = pack(quantity = tablets("20"), claims = Claims(BigDecimal("2"), null))
        assertEquals(Preparation.AlreadyApplied, PackageSyncCommand.ReleaseClaim(PACK).prepare(unclaimed, sync))
        assertTrue(PackageSyncCommand.ReleaseClaim(PACK).prepare(claimed, sync) is Preparation.Send)
    }

    @Test
    fun courseConsumeIsASyncUnderTheOperationIdWithBothNumbers() {
        val request = PackageSyncCommand.Consume(PACK, dose("3"), INTAKE, claimAfter = tablets("4")).prepared()
        assertEquals("PUT", request.method)
        assertEquals("/v1/drugs/$PACK/sync/$INTAKE", request.path)
        assertTrue(request.body!!.contains("\"consumed\":\"3\""))
        assertTrue(request.body.contains("\"reservation\":{\"amount\":\"4\",\"version\":5}"))
    }

    @Test
    fun zeroClaimAfterSendsNoReservationBlock() {
        val request = PackageSyncCommand.Consume(PACK, dose("3"), INTAKE, claimAfter = tablets("0")).prepared()
        assertFalse(requireNotNull(request.body).contains("reservation"))
    }

    /**
     * Пересчёт кладёт разницу поверх прочитанного числа (C1): видел 20, назвал 17, а прочитано 20
     * — уходит 17; прочитано 10 — уходит 7; прочитано 3 — итог ноль, и это удаление; прочитано 2 —
     * ниже нуля, соотнести нельзя, подготовка отказывает.
     */
    @Test
    fun recountLaysItsDifferenceOverTheFreshNumber() {
        val recount = PackageSyncCommand.CorrectStock(PACK, seen = tablets("20"), actual = tablets("17"))
        val asRead = recount.prepared()
        assertEquals("PATCH", asRead.method)
        assertTrue(asRead.body!!.contains("\"quantity\":\"17"))
        val overTen = recount.toPreparedRequest(INTAKE, sync, confirmed = tablets("10"), mine = null, at = EARLIER)
        assertTrue(overTen.body!!.contains("\"quantity\":\"7"))
        assertEquals(tablets("10"), overTen.quantityBefore)
        val toZero = recount.toPreparedRequest(INTAKE, sync, confirmed = tablets("3"), mine = null, at = EARLIER)
        assertEquals("DELETE", toZero.method)
        assertEquals(mapOf("version" to "3"), toZero.query)
        assertNull(toZero.body)
        assertEquals(
            Preparation.Refuse(RefusalReason.CONFLICT),
            recount.prepare(pack(quantity = tablets("2")), sync)
        )
        assertTrue(recount.prepare(pack(quantity = tablets("3")), sync) is Preparation.Send)
    }

    /**
     * Правка сведений везёт только свои поля поверх прочитанных (C1): переименованное соседом имя
     * остаётся его, категория человека ложится; сосед сделал то же — посылать нечего; то же поле
     * изменено соседом иначе — соотнести нельзя.
     */
    @Test
    fun describingSendsOnlyItsOwnFieldsOverWhatWasRead() {
        val facts = PackageSharedFacts("Парацетамол", TABLET_FORM)
        val describe = PackageSyncCommand.Describe(PACK, facts, facts.copy(category = "жар"))
        val renamed = pack(name = "Панадол", form = TABLET_FORM)
        val preparation = describe.prepare(renamed, sync) as Preparation.Send
        val request = describe.toPreparedRequest(INTAKE, sync, preparation.confirmed, preparation.mine, EARLIER, preparation.known)
        assertTrue(request.body!!.contains("\"category\":\"жар\""))
        assertFalse(request.body.contains("name"))
        assertEquals(
            Preparation.AlreadyApplied,
            describe.prepare(pack(category = "жар", form = TABLET_FORM), sync)
        )
        assertEquals(
            Preparation.Refuse(RefusalReason.CONFLICT),
            describe.prepare(pack(category = "боль", form = TABLET_FORM), sync)
        )
    }

    @Test
    fun claimIsDeclaredWhenThereIsNoneAndChangedWhenThereIs() {
        val declared = PackageSyncCommand.SetClaim(PACK, tablets("6")).prepared(mine = null)
        assertEquals("POST" to "/v1/reservations", declared.method to declared.path)
        val changed = PackageSyncCommand.SetClaim(PACK, tablets("6")).prepared(mine = tablets("4"))
        assertEquals("PATCH" to "/v1/reservations/$PACK", changed.method to changed.path)
        assertEquals(tablets("4"), changed.mineBefore)
    }

    @Test
    fun theRestNameTheirPathsAndVersions() {
        assertEquals(
            "PUT /v1/med-kits/$SHARED_KIT/drugs/$PACK",
            PackageSyncCommand.Move(PACK, SHARED_KIT).prepared().let { "${it.method} ${it.path}" }
        )
        assertEquals("DELETE", PackageSyncCommand.Delete(PACK).prepared().method)
        val withdraw = PackageSyncCommand.Withdraw(PACK, SHARED_KIT, tablets("20")).prepared()
        assertEquals("DELETE /v1/drugs/$PACK", "${withdraw.method} ${withdraw.path}")
        assertEquals(mapOf("version" to "3"), withdraw.query)
        assertEquals("DELETE /v1/reservations/$PACK", PackageSyncCommand.ReleaseClaim(PACK).prepared().let { "${it.method} ${it.path}" })
        val facts = PackageSharedFacts("Парацетамол", TABLET_FORM)
        // Создание собирается по прочитанной коробке: число и сведения приходят аргументами (E6).
        val create = PackageSyncCommand.Create(PACK, HOME_KIT).prepared(known = facts)
        assertEquals("POST /v1/med-kits/$HOME_KIT/drugs", "${create.method} ${create.path}")
        assertTrue(create.body!!.contains("\"quantity\":\"20\""))
        assertTrue(create.body.contains("\"name\":\"Парацетамол\""))
        val describe = PackageSyncCommand.Describe(PACK, facts, facts.copy(category = "жар")).prepared(known = facts)
        assertEquals("PATCH", describe.method)
        assertTrue(describe.body!!.contains("\"category\":\"жар\""))
        assertFalse(describe.body.contains("name"))
    }

    /** Форма ответа — по контракту операции; «пачки нет» ждут расход и пересчёт, дошедшие до нуля (PLAN B4, B5). */
    @Test
    fun eachCommandNamesTheShapeOfItsAnswer() {
        assertEquals(Expected.SNAPSHOT_OR_GONE, PackageSyncCommand.Consume(PACK, dose("3"), INTAKE).expects)
        assertEquals(Expected.SNAPSHOT_OR_GONE, PackageSyncCommand.CorrectStock(PACK, tablets("20"), tablets("5")).expects)
        assertEquals(Expected.CLAIM, PackageSyncCommand.SetClaim(PACK, tablets("6")).expects)
        assertEquals(Expected.NOTHING, PackageSyncCommand.Delete(PACK).expects)
        assertEquals(Expected.SNAPSHOT, PackageSyncCommand.Create(PACK, HOME_KIT).expects)
    }
}
