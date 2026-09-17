package com.kert0n.medapp.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.medkit.InvitationKey
import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.medkit.MedKitContents
import com.kert0n.medapp.domain.pack.ExpiryDate
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.SHARED_KIT
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.projected
import com.kert0n.medapp.domain.value.Quantity
import com.kert0n.medapp.domain.value.QuantityUnit
import java.math.BigDecimal
import com.kert0n.medapp.presentation.ScreenState
import com.kert0n.medapp.presentation.course.CourseCoveragePresentationDTO
import com.kert0n.medapp.presentation.course.ShortagePresentationDTO
import com.kert0n.medapp.presentation.medkit.InvitationPresentationDTO
import com.kert0n.medapp.presentation.medkit.MedKitJoiningRefusal
import com.kert0n.medapp.presentation.medkit.MedKitJoiningUiState
import com.kert0n.medapp.presentation.medkit.MedKitSharingUiState
import com.kert0n.medapp.presentation.medkit.toPresentationDTO
import com.kert0n.medapp.presentation.operation.OutstandingOperationPresentationDTO
import com.kert0n.medapp.presentation.operation.SyncStatusUiState
import com.kert0n.medapp.presentation.pack.MedKitContentsUiState
import com.kert0n.medapp.presentation.pack.PackageCardUiState
import com.kert0n.medapp.presentation.pack.RemovalStep
import com.kert0n.medapp.presentation.pack.toPresentationDTO
import com.kert0n.medapp.domain.pack.PackageStatus
import com.kert0n.medapp.presentation.course.CourseSourcePresentationDTO
import com.kert0n.medapp.presentation.course.CourseSourcesUiState
import com.kert0n.medapp.presentation.value.toPresentationDTO
import com.kert0n.medapp.ui.course.CourseSourcesScreen
import com.kert0n.medapp.ui.course.ShortageRemedies
import com.kert0n.medapp.ui.medkit.MedKitContentsScreen
import com.kert0n.medapp.ui.medkit.MedKitJoiningScreen
import com.kert0n.medapp.ui.medkit.MedKitListScreen
import com.kert0n.medapp.ui.medkit.MedKitSharingScreen
import com.kert0n.medapp.ui.operation.OptionsScreen
import com.kert0n.medapp.ui.operation.SyncStatusScreen
import com.kert0n.medapp.ui.pack.PackageCardScreen
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.uuid.Uuid
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Снимки набора общей полки (PLAN H3 «Набор общей полки», `docs/screens/U6-снять.md`): свои
 * экраны и новые состояния чужих — полка и коробка «в пути», погашенная удаляемая коробка,
 * отказ сервера на карточке.
 */
@RunWith(AndroidJUnit4::class)
class U6ScreenShots : ScreenShots() {

    private val today: LocalDate = LocalDate.of(2026, 9, 17)
    private val dacha = Uuid.parse("00000000-0000-4000-8000-0000000000dd")

    // ── 20. Поделиться ────────────────────────────────────────────────────────────────────────

    @Test
    fun sharingLocal() = shot("20-sharing/local") {
        MedKitSharingScreen(MedKitSharingUiState.Deciding("Домашняя"), {}, {}, {}, {}, {}, {}, {})
    }

    @Test
    fun sharingAsking() = shotOfDialog("20-sharing/confirm") {
        MedKitSharingScreen(MedKitSharingUiState.Deciding("Домашняя", isAsking = true), {}, {}, {}, {}, {}, {}, {})
    }

    @Test
    fun sharingOnItsWay() = shot("20-sharing/on-its-way") {
        MedKitSharingScreen(MedKitSharingUiState.OnItsWay("Домашняя"), {}, {}, {}, {}, {}, {}, {})
    }

    @Test
    fun sharingShared() = shot("20-sharing/shared") {
        MedKitSharingScreen(shared(), {}, {}, {}, {}, {}, {}, {})
    }

    @Test
    fun invitationFullScreen() = shotOfDialog("21-invitation/full-screen") {
        MedKitSharingScreen(shared(isFullScreen = true), {}, {}, {}, {}, {}, {}, {})
    }

    private fun shared(isFullScreen: Boolean = false) = MedKitSharingUiState.Shared(
        name = "Семейная",
        invitation = InvitationPresentationDTO(InvitationKey("K7F-2M9-QX4"), LocalTime.of(15, 30)),
        isFullScreen = isFullScreen
    )

    // ── 22. Присоединиться ────────────────────────────────────────────────────────────────────

    @Test
    fun joiningEmpty() = shot("22-joining/empty") {
        MedKitJoiningScreen(MedKitJoiningUiState(), {}, {}, {})
    }

    @Test
    fun joiningInvalid() = shot("22-joining/invalid") {
        MedKitJoiningScreen(
            MedKitJoiningUiState(code = "K7F-2M9-QX4", refusal = MedKitJoiningRefusal.Invalid), {}, {}, {}
        )
    }

    // ── 23. Уборка и выход ────────────────────────────────────────────────────────────────────

    @Test
    fun removalLocal() = shotOfDialog("23-removal/local") {
        MedKitContentsScreen(contents(removing = RemovalStep.ASKING), {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
    }

    @Test
    fun removalShared() = shotOfDialog("23-removal/shared") {
        MedKitContentsScreen(
            contents(
                shelf = medKit(
                    id = SHARED_KIT, name = "Семейная",
                    publication = MedKit.Publication.PUBLISHED, participantCount = 3
                ),
                removing = RemovalStep.ASKING,
                affected = listOf("Спина", "Колено")
            ),
            {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}
        )
    }

    // ── 28. Состояние синхронизации и место «Опции» ───────────────────────────────────────────

    @Test
    fun syncRows() = shot("28-sync/rows") {
        SyncStatusScreen(
            SyncStatusUiState(rows = listOf(waiting(), refused(), unreadable()), refreshedAt = at(14, 2), isLoaded = true),
            {}, {}, {}, {}, {}, zone = ZoneOffset.UTC
        )
    }

    @Test
    fun syncEmpty() = shot("28-sync/empty") {
        SyncStatusScreen(SyncStatusUiState(refreshedAt = at(14, 2), isLoaded = true), {}, {}, {}, {}, {}, zone = ZoneOffset.UTC)
    }

    @Test
    fun syncOffline() = shot("28-sync/offline") {
        SyncStatusScreen(
            SyncStatusUiState(rows = listOf(waiting()), isOffline = true, isLoaded = true),
            {}, {}, {}, {}, {}, zone = ZoneOffset.UTC
        )
    }

    @Test
    fun optionsRoot() = shot("27-options-place/root") { OptionsScreen(outstanding = 2, onSyncStatus = {}) }

    @Test
    fun optionsQuiet() = shot("27-options-place/quiet") { OptionsScreen(outstanding = 0, onSyncStatus = {}) }

    // ── Нехватка ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun shortageRemedies() = shotOfDialog("14-course-card/remedies") {
        ShortageRemedies(ShortagePresentationDTO(19, LocalDate.of(2026, 9, 11)), {}, {}, {}, {})
    }

    // ── Новые состояния чужих экранов ─────────────────────────────────────────────────────────

    @Test
    fun medKitsInFlight() = shot("02-med-kits/in-flight") {
        MedKitListScreen(
            ScreenState.Ready(
                listOf(
                    shelfRow(medKit(id = HOME_KIT, name = "Домашняя")),
                    shelfRow(medKit(id = dacha, name = "Дача"), com.kert0n.medapp.domain.medkit.MedKitStatus.PUBLISHING),
                    shelfRow(
                        medKit(id = SHARED_KIT, name = "Семейная", publication = MedKit.Publication.PUBLISHED, participantCount = 3),
                        com.kert0n.medapp.domain.medkit.MedKitStatus.REMOVING
                    )
                )
            ),
            {}, {}, {}, {}
        )
    }

    @Test
    fun contentsInFlight() = shot("04-med-kit-contents/in-flight") {
        MedKitContentsScreen(contents(packages = inFlightBoxes()), {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
    }

    /**
     * Источник, о судьбе которого решение уже принято: приёмов он больше не даёт, и строка
     * называет причину теми же словами, что списки коробок (PLAN D4, замечание владельца
     * 2026-09-17).
     */
    @Test
    fun sourcesLeaving() = shot("16-course-sources/leaving") {
        CourseSourcesScreen(
            CourseSourcesUiState(
                title = "Спина",
                dose = Quantity(BigDecimal.ONE, pieces).toPresentationDTO(),
                sources = listOf(
                    leavingSource(PACK, "Ибупрофен", PackageStatus.REMOVING, allocated = 6),
                    leavingSource(dacha, "Нурофен", PackageStatus.LOST, allocated = 4),
                    leavingSource(SHARED_KIT, "Ибупрофен про запас", PackageStatus.ACTIVE, allocated = 4)
                ),
                coverage = CourseCoveragePresentationDTO(
                    requiredDoses = 14, coveredDoses = 4, missingDoses = 10,
                    coveredUntilOn = today.plusDays(2), firstUncoveredOn = today.plusDays(3)
                )
            ),
            { _, _ -> }, { _, _ -> }, {}, {}, {}, {}, {}, {}, {}
        )
    }

    private fun leavingSource(id: Uuid, name: String, status: PackageStatus, allocated: Int) =
        CourseSourcePresentationDTO(
            packageId = id,
            name = name,
            medKitName = "Семейная",
            expiresOn = null,
            availableToMe = pieces(if (status == PackageStatus.ACTIVE) "12" else "0").toPresentationDTO(),
            allocatedDoses = allocated,
            allocatedAmount = pieces(allocated.toString()).toPresentationDTO(),
            coveredDoses = if (status == PackageStatus.ACTIVE) allocated else 0,
            maxDoses = if (status == PackageStatus.ACTIVE) 12 else 0,
            fault = null,
            status = status
        )

    @Test
    fun cardRefusedByServer() = shot("06-package-card/refused") {
        PackageCardScreen(
            PackageCardUiState(
                pack = pack(id = PACK, name = "Ибупрофен", quantity = pieces("8"), form = TABLET_FORM)
                    .projected().toPresentationDTO(),
                medKitName = "Семейная",
                today = today,
                isRefusedByServer = true
            ),
            {}, {}, {}, {}, {}, {}, {}, {}, {}
        )
    }

    // ── Заготовки ─────────────────────────────────────────────────────────────────────────────

    /** Единица с живым именем: у фикстурной «таблетка» нет форм числа, и снимок врёт глазу. */
    private val pieces = QuantityUnit(Uuid.parse("00000000-0000-4000-8000-0000000000e1"), "шт.")

    private fun pieces(amount: String) = Quantity(BigDecimal(amount), pieces)

    private fun at(hour: Int, minute: Int): Instant =
        today.atTime(hour, minute).toInstant(ZoneOffset.UTC)

    private fun shelfRow(
        shelf: MedKit,
        status: com.kert0n.medapp.domain.medkit.MedKitStatus = com.kert0n.medapp.domain.medkit.MedKitStatus.ACTIVE
    ) = shelf.projection(MedKitContents(packages = 4, expired = if (status == com.kert0n.medapp.domain.medkit.MedKitStatus.ACTIVE) 1 else 0))
        .copy(status = status)
        .toPresentationDTO()

    private fun inFlightBoxes() = listOf(
        pack(id = PACK, name = "Ибупрофен", quantity = pieces("8"), form = TABLET_FORM)
            .projected(hasUnconfirmedChanges = true).toPresentationDTO(),
        pack(id = Uuid.random(), name = "Цетрин", quantity = pieces("10"), form = TABLET_FORM)
            .markRemoving(Uuid.random()).projected().toPresentationDTO(),
        pack(id = Uuid.random(), name = "Но-шпа", quantity = pieces("20"), form = TABLET_FORM)
            .markLost(Uuid.random()).projected().toPresentationDTO(),
        pack(
            id = Uuid.random(), name = "Аспирин", quantity = pieces("6"), form = TABLET_FORM,
            expiresOn = ExpiryDate(today.minusDays(3))
        ).projected().toPresentationDTO()
    )

    private fun contents(
        shelf: MedKit = medKit(id = HOME_KIT, name = "Домашняя"),
        packages: List<com.kert0n.medapp.presentation.pack.PackagePresentationDTO> = emptyList(),
        removing: RemovalStep? = null,
        affected: List<String> = emptyList()
    ) = MedKitContentsUiState(
        medKit = shelf.projection(MedKitContents(packages = 4, expired = 0)).toPresentationDTO(),
        packages = packages,
        others = listOf(
            medKit(id = dacha, name = "Дача").projection(MedKitContents(packages = 0, expired = 0)).toPresentationDTO()
        ),
        today = today,
        isLoaded = true,
        removing = removing,
        affectedCourses = affected
    )

    private fun waiting() = OutstandingOperationPresentationDTO(
        id = Uuid.random(),
        trouble = OutstandingOperationPresentationDTO.Trouble.WAITING,
        about = OutstandingOperationPresentationDTO.About.INTAKE,
        subject = "Нурофен",
        reason = null,
        retryAt = null,
        recountable = null
    )

    private fun refused() = OutstandingOperationPresentationDTO(
        id = Uuid.random(),
        trouble = OutstandingOperationPresentationDTO.Trouble.REFUSED,
        about = OutstandingOperationPresentationDTO.About.INTAKE,
        subject = "Ибупрофен",
        reason = OutstandingOperationPresentationDTO.Reason.NOT_ENOUGH,
        retryAt = null,
        recountable = PACK
    )

    private fun unreadable() = OutstandingOperationPresentationDTO(
        id = Uuid.random(),
        trouble = OutstandingOperationPresentationDTO.Trouble.UNREADABLE,
        about = OutstandingOperationPresentationDTO.About.UNKNOWN,
        subject = null,
        reason = OutstandingOperationPresentationDTO.Reason.UNREADABLE,
        retryAt = null,
        recountable = null
    )
}
