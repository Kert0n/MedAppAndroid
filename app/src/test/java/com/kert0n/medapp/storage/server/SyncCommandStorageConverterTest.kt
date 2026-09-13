package com.kert0n.medapp.storage.server

import com.kert0n.medapp.domain.pack.PackageSharedFacts
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.INTAKE
import com.kert0n.medapp.fixture.OTHER_PACK
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.SHARED_KIT
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.queue.medkit.MedKitSyncCommand
import com.kert0n.medapp.queue.pack.PackageSyncCommand
import com.kert0n.medapp.queue.SyncCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.kert0n.medapp.fixture.VOCABULARY
import com.kert0n.medapp.domain.value.Vocabulary
import com.kert0n.medapp.fixture.MILLILITRES
import com.kert0n.medapp.fixture.TABLETS
import com.kert0n.medapp.fixture.millilitres
import com.kert0n.medapp.network.value.VocabularyMiss

/**
 * Круговой тест по **всем двенадцати** видам команд: исчерпывающего `when` по обоим корням
 * сразу у маркера нет, и закрытость набора держит именно этот перечень (PLAN E2, PR 4).
 */
class SyncCommandStorageConverterTest {

    private val facts = PackageSharedFacts(
        name = "Парацетамол",
        form = TABLET_FORM,
        category = "Обезболивающие",
        manufacturer = "Завод",
        country = "Россия",
        description = "Таблетки"
    )

    private val everyKind: List<SyncCommand> = listOf(
        PackageSyncCommand.Create(PACK, HOME_KIT, tablets("20"), facts),
        PackageSyncCommand.Describe(PACK, facts, facts.copy(name = "Paracetamol", category = null)),
        PackageSyncCommand.CorrectStock(PACK, tablets("18.5")),
        PackageSyncCommand.Move(PACK, SHARED_KIT),
        PackageSyncCommand.Delete(PACK),
        PackageSyncCommand.Withdraw(PACK, SHARED_KIT, tablets("20")),
        PackageSyncCommand.Consume(PACK, dose("1.5"), INTAKE),
        PackageSyncCommand.Consume(PACK, dose("1.5"), INTAKE, claimAfter = tablets("4")),
        PackageSyncCommand.SetClaim(PACK, tablets("6")),
        PackageSyncCommand.ReleaseClaim(PACK),
        MedKitSyncCommand.Publish(HOME_KIT),
        MedKitSyncCommand.Delete(HOME_KIT),
        MedKitSyncCommand.Delete(HOME_KIT, transferTo = SHARED_KIT),
        MedKitSyncCommand.Leave(SHARED_KIT)
    )

    private fun roundTrip(command: SyncCommand): SyncCommand? = SyncCommandStorageConverter.commandOf(
        kind = SyncCommandStorageConverter.kindOf(command),
        payload = SyncCommandStorageConverter.payloadOf(command),
        payloadVersion = SyncCommandStorageConverter.PAYLOAD_VERSION,
        vocabulary = VOCABULARY
    )

    @Test
    fun everyCommandSurvivesTheRoundTrip() {
        for (command in everyKind) {
            assertEquals(command, roundTrip(command))
        }
    }

    @Test
    fun twelveKindsAndNoMore() {
        assertEquals(
            listOf(
                "PACKAGE_CREATE", "PACKAGE_DESCRIBE", "PACKAGE_CORRECT_STOCK", "PACKAGE_MOVE",
                "PACKAGE_DELETE", "PACKAGE_WITHDRAW", "PACKAGE_CONSUME", "PACKAGE_SET_CLAIM", "PACKAGE_RELEASE_CLAIM",
                "MEDKIT_PUBLISH", "MEDKIT_DELETE", "MEDKIT_LEAVE"
            ),
            everyKind.map(SyncCommandStorageConverter::kindOf).distinct()
        )
    }

    /** Пустая бронь и заполненная — разные команды, и различать их должен именно payload. */
    @Test
    fun consumeWithAndWithoutClaimAreNotConfused() {
        val plain = PackageSyncCommand.Consume(PACK, dose("1"), INTAKE)
        val withClaim = PackageSyncCommand.Consume(PACK, dose("1"), INTAKE, claimAfter = tablets("0"))

        assertNull((roundTrip(plain) as PackageSyncCommand.Consume).claimAfter)
        assertEquals(tablets("0"), (roundTrip(withClaim) as PackageSyncCommand.Consume).claimAfter)
    }

    /** Очистка текста и его отсутствие — одно и то же в описании, и оба должны вернуться пустыми. */
    @Test
    fun describeKeepsBothSidesIncludingClearedFields() {
        val command = PackageSyncCommand.Describe(
            PACK,
            before = facts,
            after = PackageSharedFacts(name = "Парацетамол")
        )
        val restored = roundTrip(command) as PackageSyncCommand.Describe
        assertEquals(facts, restored.before)
        assertNull(restored.after.category)
        assertNull(restored.after.form)
    }

    @Test
    fun commandNamesThePackageItTouchesAndMedKitCommandsDoNot() {
        assertEquals(PACK, SyncCommandStorageConverter.packageIdOf(PackageSyncCommand.Delete(PACK)))
        assertNull(SyncCommandStorageConverter.packageIdOf(MedKitSyncCommand.Leave(HOME_KIT)))
        assertEquals(HOME_KIT, SyncCommandStorageConverter.medKitIdOf(MedKitSyncCommand.Publish(HOME_KIT)))
        assertEquals(
            SHARED_KIT,
            SyncCommandStorageConverter.medKitIdOf(PackageSyncCommand.Move(PACK, SHARED_KIT))
        )
        // Унесённая домой коробка действует на полке, откуда её унесли: там её и снимают.
        assertEquals(
            HOME_KIT,
            SyncCommandStorageConverter.medKitIdOf(PackageSyncCommand.Withdraw(PACK, HOME_KIT, tablets("20")))
        )
    }

    /** Чужая версия payload не роняет разбор: операция читается как нечитаемая. */
    @Test
    fun unknownPayloadVersionIsUnreadableRatherThanFatal() {
        val command = PackageSyncCommand.Delete(OTHER_PACK)
        assertNull(
            SyncCommandStorageConverter.commandOf(
                kind = SyncCommandStorageConverter.kindOf(command),
                payload = SyncCommandStorageConverter.payloadOf(command),
                payloadVersion = SyncCommandStorageConverter.PAYLOAD_VERSION + 1,
                vocabulary = VOCABULARY
            )
        )
    }

    /** Вид команды, которого эта сборка не знает, — обычное следствие обновления приложения. */
    @Test
    fun anUnknownKindIsUnreadableToo() {
        assertNull(
            SyncCommandStorageConverter.commandOf(
                "PACKAGE_EXPLODE",
                "{}",
                SyncCommandStorageConverter.PAYLOAD_VERSION,
                VOCABULARY
            )
        )
    }

    /**
     * Повреждённый payload известного вида — не «команда неизвестна»: причины разные, и человеку
     * сообщается своя. Иначе строка очереди объясняла бы поломку версией, которая в порядке.
     */
    @Test
    fun aDamagedPayloadNamesItselfRatherThanPretendingToBeUnknown() {
        val version = SyncCommandStorageConverter.PAYLOAD_VERSION

        for (payload in listOf("не json", "{}")) {
            val refusal = runCatching {
                SyncCommandStorageConverter.commandOf("PACKAGE_DELETE", payload, version, VOCABULARY)
            }.exceptionOrNull()

            assertTrue("$payload: $refusal", refusal is IllegalArgumentException)
            assertTrue("$payload: $refusal", refusal?.message?.contains("PACKAGE_DELETE") == true)
        }
    }

    /** Единица вне снимка — промах словаря, а не порча payload: он лечится чтением, а не человеком. */
    @Test
    fun aUnitMissingFromTheSnapshotIsAVocabularyMissNotAFormatError() {
        val command = PackageSyncCommand.CorrectStock(PACK, millilitres("10"))
        val refusal = runCatching {
            SyncCommandStorageConverter.commandOf(
                kind = SyncCommandStorageConverter.kindOf(command),
                payload = SyncCommandStorageConverter.payloadOf(command),
                payloadVersion = SyncCommandStorageConverter.PAYLOAD_VERSION,
                vocabulary = Vocabulary(listOf(TABLETS), emptyList())
            )
        }.exceptionOrNull()
        assertTrue("$refusal", refusal is VocabularyMiss)
        assertEquals(MILLILITRES.id, (refusal as VocabularyMiss).id)
    }
}
