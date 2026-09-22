package com.kert0n.medapp.network.server

import com.kert0n.medapp.network.account.AccountCredentials
import com.kert0n.medapp.network.medkit.MedKitPostNetworkDTO
import com.kert0n.medapp.network.medkit.MembershipPostNetworkDTO
import com.kert0n.medapp.network.pack.ClaimPatchNetworkDTO
import com.kert0n.medapp.network.pack.ClaimPostNetworkDTO
import com.kert0n.medapp.network.pack.PackagePatchNetworkDTO
import com.kert0n.medapp.network.pack.PackagePostNetworkDTO
import com.kert0n.medapp.network.pack.PackageSnapshotNetworkDTO
import com.kert0n.medapp.network.pack.PackageSyncNetworkDTO
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import com.kert0n.medapp.domain.value.Dose
import com.kert0n.medapp.domain.value.Quantity
import com.kert0n.medapp.domain.value.QuantityUnit
import com.kert0n.medapp.queue.PreparedRequest
import com.kert0n.medapp.queue.pack.PackageSyncCommand
import com.kert0n.medapp.network.pack.PackageSyncState
import com.kert0n.medapp.queue.pack.toPreparedRequest
import com.kert0n.medapp.fixture.ProbeAccounts
import java.math.BigDecimal
import java.time.Instant

/**
 * Проба контракта против боевого сервера (PLAN PR 5, AGENTS «Связь с сервером»): тот же клиент,
 * что у приложения, два пробных пользователя из `local.properties` и синтетические аптечки,
 * которые прогон удаляет за собой. Проверяются не только успехи, но и отказы: 409 на повтор
 * создания, 428 и 412 на предусловия, 404 на чужое, пустые тела. Ключей и пропусков в
 * сообщениях нет — их прячут `toString` сетевых форм.
 *
 * Включается только `-Pprobe`, иначе пропускается. Пропуск выдаётся один раз на пользователя за
 * прогон: сервер считает выдачи с адреса.
 */
class ContractProbe {

    companion object {
        private lateinit var owner: MedAppApi
        private lateinit var guest: MedAppApi
        private lateinit var anonymous: MedAppApi
        private lateinit var ownerAccount: AccountCredentials
        private lateinit var unit: Uuid

        /** Почему проба не идёт; `null` — идёт. Пропуск виден в отчёте у каждого теста. */
        private var skipReason: String? = null

        /** Клиенты общие на весь прогон проб: пропуск выдаётся один раз на пользователя ([ProbeAccounts]). */
        @BeforeClass
        @JvmStatic
        fun connect() {
            skipReason = ProbeAccounts.skipReason
            if (skipReason != null) return
            ownerAccount = requireNotNull(ProbeAccounts.annaAccount)
            owner = requireNotNull(ProbeAccounts.anna)
            guest = requireNotNull(ProbeAccounts.boris)
            anonymous = requireNotNull(ProbeAccounts.anonymous)
            // Первое же чтение говорит, готов ли сервер вообще разговаривать. 429 — не провал
            // пробы: сервер считает обращения с адреса и о контракте ничего не сказал. Проба
            // откладывается с названной причиной, а не краснеет пятнадцатью строками подряд.
            when (val units = runBlocking { owner.quantityUnits() }) {
                is ApiResult.Success -> unit = units.value.first().id
                is ApiResult.Failure -> {
                    if (units.failure !is ApiFailure.TooManyRequests) {
                        throw AssertionError("проба не смогла начать: $units")
                    }
                    skipReason = "боевой сервер считает обращения с адреса — проба отложена"
                }
            }
        }

        /** Готовый запрос очереди — примитивами, как его и шлёт `QueueHttpTransport`. */
        private suspend fun MedAppApi.send(request: PreparedRequest) =
            send(request.method, request.path, request.query, request.body)

        private fun <T> success(result: ApiResult<T>): T = when (result) {
            is ApiResult.Success -> result.value
            is ApiResult.Failure -> throw AssertionError("ожидался успех: $result")
        }
    }

    private val kits = mutableListOf<Uuid>()

    @Before
    fun requireProbe() {
        assumeTrue(skipReason.orEmpty(), skipReason == null)
    }

    /**
     * Уборка обязана удаться: синтетическая аптечка, оставшаяся на боевом сервере, — мусор,
     * который никто больше не найдёт. `404` уборке не мешает: аптечки уже нет.
     */
    @After
    fun removeSyntheticKits() = runBlocking {
        val left = kits.filter { kit ->
            val result = owner.deleteMedKit(kit)
            result is ApiResult.Failure && result.failure != ApiFailure.NotFound
        }
        if (left.isNotEmpty()) {
            throw AssertionError("синтетические аптечки остались на боевом сервере: $left")
        }
    }

    private fun failure(result: ApiResult<*>): ApiFailure =
        (result as? ApiResult.Failure)?.failure ?: throw AssertionError("ожидался отказ: $result")

    /**
     * Отказ **по существу**: 429 — это не «пароль не принят», а «сервер о пароле не говорил».
     * Он считает попытки входа с адреса, и счёт этот живёт дольше минуты — переждать его в
     * проверке нечем, а повторять попытки значит его же и кормить. Поэтому проба не повторяет и
     * не выдумывает: она откладывается с названной причиной, и в отчёте это пропуск, а не
     * красная строка о работе, которой сервер не делал.
     */
    private suspend fun judged(call: suspend () -> ApiResult<*>): ApiFailure {
        val failure = failure(call())
        assumeTrue(
            "боевой сервер считает попытки входа с адреса — проба отложена",
            failure !is ApiFailure.TooManyRequests
        )
        return failure
    }

    private suspend fun newKit(): Uuid {
        val id = Uuid.random()
        success(owner.createMedKit(MedKitPostNetworkDTO(id)))
        kits += id
        return id
    }

    private fun packagePost(amount: String = "10") = PackagePostNetworkDTO(
        id = Uuid.random(),
        name = "Проба контракта",
        amount = amount,
        unitId = unit,
        formId = null,
        category = null,
        manufacturer = null,
        country = null,
        description = "синтетическая пачка пробы контракта"
    )

    private suspend fun newPackage(kit: Uuid, amount: String = "10"): PackageSnapshotNetworkDTO =
        success(owner.createPackage(kit, packagePost(amount)))

    @Test
    fun commonAndOtherServerFormsSurvivePackageRoundTrip() = runBlocking {
        val forms = success(owner.formTypes())
        assertEquals(18, forms.size)
        val kit = newKit()
        for (name in listOf("таблетки", "другие")) {
            val serverForm = forms.single { it.name == name }
            val post = packagePost().copy(formId = serverForm.id)
            val created = success(owner.createPackage(kit, post))
            assertEquals(serverForm.id, created.pack.formId)
            assertEquals(serverForm.id, success(owner.packageSnapshot(post.id)).pack.formId)
        }
    }

    /**
     * Токен сборки — единственное, чем сервер отличает наше приложение от чужого клиента. Прими
     * он чужой, учётка завелась бы у любого, и выданный пропуск открыл бы чужие полки.
     */
    @Test
    fun foreignRegistrationTokenIsRefusedWithoutAnAccount() = runBlocking {
        // Токен сборки проверяется первым: придуманные данные до учётки не доходят.
        val invented = AccountCredentials.random()
        assertEquals(ApiFailure.RegistrationRefused, failure(anonymous.register(invented, "not-the-build-token")))
        assertEquals(ApiFailure.Unauthorized, judged { anonymous.token(invented) })
    }

    /**
     * Пароль — всё, что стоит между чужим и аптечкой человека: имя учётки известно, а ключ нет.
     * Выдай сервер пропуск по неверному паролю — и чужой читает и тратит чужие коробки.
     */
    @Test
    fun wrongPasswordIsNotAccepted() = runBlocking {
        val wrong = AccountCredentials(ownerAccount.login, "not-the-password")
        assertEquals(ApiFailure.Unauthorized, judged { anonymous.token(wrong) })
    }

    @Test
    fun whatTheAccountSeesIsReadable() = runBlocking {
        assertTrue(success(owner.quantityUnits()).isNotEmpty())
        success(owner.formTypes())
        success(owner.snapshot())
        success(owner.medKits())
        success(owner.claims())
        success(owner.searchTemplates("аспирин", 5))
        Unit
    }

    @Test
    fun creationByClientIdentifierIsNotRepeated() = runBlocking {
        val kit = newKit()
        assertEquals(ApiFailure.Conflict, failure(owner.createMedKit(MedKitPostNetworkDTO(kit))))

        val post = packagePost()
        success(owner.createPackage(kit, post))
        assertEquals(ApiFailure.Conflict, failure(owner.createPackage(kit, post)))
    }

    @Test
    fun commandsActOnlyOnTheVersionTheyName() = runBlocking {
        val pack = newPackage(newKit()).pack
        val edit = PackagePatchNetworkDTO(description = "правка пробы")

        assertEquals(ApiFailure.PreconditionRequired, failure(owner.patchPackage(pack.id, edit)))
        val stale = edit.copy(version = ResourceVersion(pack.version.number + 1))
        assertEquals(ApiFailure.PreconditionFailed, failure(owner.patchPackage(pack.id, stale)))

        val patched = success(owner.patchPackage(pack.id, edit.copy(version = pack.version))).pack
        assertEquals("правка пробы", patched.description)
        assertTrue(patched.version > pack.version)
    }

    @Test
    fun strangerCannotTellAForeignKitFromNothing() = runBlocking {
        val kit = newKit()
        val pack = newPackage(kit).pack

        assertEquals(ApiFailure.NotFound, failure(guest.packageSnapshot(pack.id)))
        assertEquals(ApiFailure.NotFound, failure(guest.medKit(kit)))
    }

    @Test
    fun invitationLetsTheSecondUserInOnce() = runBlocking {
        val kit = newKit()
        newPackage(kit)
        val invitation = success(owner.createInvitation(kit))

        val joined = success(guest.joinMedKit(MembershipPostNetworkDTO(invitation.key)))
        assertEquals(2L, joined.participantCount)
        assertEquals(1, joined.packages.size)
        assertEquals(ApiFailure.Conflict, failure(guest.joinMedKit(MembershipPostNetworkDTO(invitation.key))))
        assertEquals(Unit, success(guest.leaveMedKit(kit)))
    }

    @Test
    fun claimIsDeclaredOnceThenChangedAndRemoved() = runBlocking {
        val snapshot = newPackage(newKit())
        val packageId = snapshot.pack.id

        success(owner.createClaim(ClaimPostNetworkDTO(packageId, "3", snapshot.claims.version)))
        val declared = success(owner.packageSnapshot(packageId)).claims
        assertEquals("3.000000", declared.mine)
        assertEquals(
            ApiFailure.Conflict,
            failure(owner.createClaim(ClaimPostNetworkDTO(packageId, "3", declared.version)))
        )

        success(owner.patchClaim(packageId, ClaimPatchNetworkDTO("2", declared.version)))
        val changed = success(owner.packageSnapshot(packageId)).claims
        assertEquals("2.000000", changed.mine)
        assertEquals(Unit, success(owner.deleteClaim(packageId, changed.version)))
        assertNull(success(owner.packageSnapshot(packageId)).claims.mine)
    }

    @Test
    fun consumptionAnswersWithTheSnapshotUntilThePackageIsGone() = runBlocking {
        val pack = newPackage(newKit(), amount = "10").pack

        val left = requireNotNull(
            success(owner.synchronise(pack.id, Uuid.random(), PackageSyncNetworkDTO("4", pack.version)))
        ) { "после частичного расхода пачка остаётся" }.pack
        assertEquals("6.000000", left.amount)

        // Пачка кончилась: сервер уничтожил её и ответил нулём байтов, а повтор того же расхода
        // отвечает уже 404 — пачки нет (PLAN B4). На этом стоит закрытие расхода применённым.
        val last = PackageSyncNetworkDTO("6", left.version)
        val lastId = Uuid.random()
        assertNull(success(owner.synchronise(pack.id, lastId, last)))
        assertEquals(ApiFailure.NotFound, failure(owner.synchronise(pack.id, lastId, last)))
    }

    @Test
    fun offlineChangesAreAppliedOnceUnderTheirSyncId() = runBlocking {
        val pack = newPackage(newKit(), amount = "10").pack
        val syncId = Uuid.random()
        val changes = PackageSyncNetworkDTO(consumed = "1", packageVersion = pack.version)

        success(owner.synchronise(pack.id, syncId, changes))
        val repeated = success(owner.synchronise(pack.id, syncId, changes))
        assertEquals("9.000000", repeated?.pack?.amount)
        assertEquals(
            ApiFailure.Conflict,
            failure(owner.synchronise(pack.id, syncId, PackageSyncNetworkDTO("2", pack.version)))
        )
    }

    @Test
    fun packageMovesToAnotherKitOfTheOwner() = runBlocking {
        val target = newKit()
        val pack = newPackage(newKit()).pack

        assertEquals(target, success(owner.movePackage(pack.id, target, pack.version)).pack.medKitId)
    }

    @Test
    fun removalAnswersWithNoContent() = runBlocking {
        val pack = newPackage(newKit()).pack

        assertEquals(Unit, success(owner.deletePackage(pack.id, pack.version)))
        assertEquals(ApiFailure.NotFound, failure(owner.packageSnapshot(pack.id)))
    }

    /**
     * Отправка очереди: замороженный запрос уходит как есть и отвечает снимком; повтор `sync`
     * под тем же номером сервер применяет один раз (PLAN B4, E3).
     */
    @Test
    fun preparedRequestOfTheQueueIsAcceptedAsIs() = runBlocking {
        val pack = newPackage(newKit(), amount = "10").pack
        val unitObject = QuantityUnit(unit, "проба")
        val sync = PackageSyncState(pack.id, version = pack.version)
        val operationId = Uuid.random()
        val consume = PackageSyncCommand.Consume(
            pack.id, Dose(Quantity(BigDecimal("2"), unitObject)), operationId,
            claimAfter = Quantity(BigDecimal("3"), unitObject)
        )
        val request = consume.toPreparedRequest(operationId, sync, confirmed = null, mine = null, at = Instant.EPOCH)

        val body = requireNotNull(success(owner.send(request)).body.takeIf { it.isNotEmpty() }) { "sync отвечает снимком" }
        val snapshot = medAppJson.decodeFromString(PackageSnapshotNetworkDTO.serializer(), body)
        assertEquals("8.000000", snapshot.pack.amount)
        assertEquals("3.000000", snapshot.claims.mine)
        // Тот же замороженный запрос второй раз: сервер применил его один раз и отвечает тем же
        // снимком; 409 он отдаёт только другому телу под тем же номером.
        val repeated = medAppJson.decodeFromString(
            PackageSnapshotNetworkDTO.serializer(), success(owner.send(request)).body
        )
        assertEquals("8.000000", repeated.pack.amount)
        // Снятие брони и удаление — без тела.
        assertEquals("", success(owner.send(PackageSyncCommand.ReleaseClaim(pack.id).toPreparedRequest(
            Uuid.random(), PackageSyncState(pack.id, snapshot.pack.version, snapshot.claims.version), null, null, Instant.EPOCH
        ))).body)
    }

    /**
     * 412 у `sync` — устаревшая версия, и запрос **не применён** (PLAN B3, E3): остаток тот же, а
     * тот же номер с той же дельтой и свежей версией сервер принимает. Внеплановый расход — тот же
     * `sync` без блока брони. На этом стоит переподготовка очереди под тем же `syncId`.
     */
    @Test
    fun staleSyncIsNotAppliedAndTheSameNumberIsAcceptedWithTheFreshVersion() = runBlocking {
        val pack = newPackage(newKit(), amount = "10").pack
        val unitObject = QuantityUnit(unit, "проба")
        val operationId = Uuid.random()
        val consume = PackageSyncCommand.Consume(pack.id, Dose(Quantity(BigDecimal("2"), unitObject)), operationId)
        val stale = consume.toPreparedRequest(
            operationId, PackageSyncState(pack.id, version = ResourceVersion(pack.version.number + 1)), null, null, Instant.EPOCH
        )

        assertEquals(ApiFailure.PreconditionFailed, failure(owner.send(stale)))
        // 428 сюда не приходит: расход без версии клиент не выражает вовсе — это отвергает сама
        // форма запроса (`PackageSyncNetworkDTO`, проверено в `WireContractTest`).
        val untouched = success(owner.packageSnapshot(pack.id))
        assertEquals("10.000000", untouched.pack.amount)
        assertEquals(pack.version, untouched.pack.version)

        val fresh = consume.toPreparedRequest(operationId, PackageSyncState(pack.id, version = untouched.pack.version), null, null, Instant.EPOCH)
        val applied = medAppJson.decodeFromString(PackageSnapshotNetworkDTO.serializer(), success(owner.send(fresh)).body)
        assertEquals("8.000000", applied.pack.amount)
        assertNull(applied.claims.mine)
        // И ещё раз тем же номером — журнал: применено один раз.
        assertEquals("8.000000", medAppJson.decodeFromString(PackageSnapshotNetworkDTO.serializer(), success(owner.send(fresh)).body).pack.amount)
    }
}
