package com.kert0n.medapp.presentation.pack

import androidx.lifecycle.SavedStateHandle
import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.feature.packages.PackageAdding
import com.kert0n.medapp.feature.packages.PackageDescribing
import com.kert0n.medapp.fixture.DirectTransactions
import com.kert0n.medapp.fixture.FakeFollowing
import com.kert0n.medapp.fixture.FakeMedKits
import com.kert0n.medapp.fixture.FakePackages
import com.kert0n.medapp.fixture.FakeQueue
import com.kert0n.medapp.fixture.FakeVocabulary
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.MainDispatcherRule
import com.kert0n.medapp.fixture.SHARED_KIT
import com.kert0n.medapp.fixture.TABLETS
import com.kert0n.medapp.fixture.awaiting
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.fixture.watching
import com.kert0n.medapp.presentation.RouteArguments
import com.kert0n.medapp.presentation.Today
import com.kert0n.medapp.presentation.value.toPresentationDTO
import com.kert0n.medapp.queue.QueueService
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * Форма упаковки (PLAN H3 №7): что она пишет, чего не пишет и что показывает, когда сценарий
 * отказал.
 */
class PackageFormViewModelTest {

    @get:Rule
    val dispatcher = MainDispatcherRule()

    private val clock: Clock =
        Clock.fixed(Instant.parse("2026-09-15T09:00:00Z"), ZoneId.of("Europe/Moscow"))

    private val medKits = FakeMedKits(medKit(id = HOME_KIT, name = "Домашняя"))

    private val packages = FakePackages()

    private val adding = PackageAdding(
        packages = packages,
        medKits = medKits,
        queue = QueueService(DirectTransactions, FakeQueue()),
        transactions = DirectTransactions,
        clock = clock
    )

    private fun viewModel(medKitId: Uuid? = HOME_KIT, packageId: Uuid? = null) = PackageFormViewModel(
        adding = adding,
        describing = describing,
        vocabulary = FakeVocabulary(),
        packages = packages,
        medKits = medKits,
        today = Today(clock),
        savedState = SavedStateHandle(
            buildMap {
                medKitId?.let { put(RouteArguments.MED_KIT_ID, it.toString()) }
                packageId?.let { put(RouteArguments.PACKAGE_ID, it.toString()) }
            }
        )
    )

    private val describing = PackageDescribing(
        packages = packages,
        following = FakeFollowing(),
        queue = QueueService(DirectTransactions, FakeQueue()),
        transactions = DirectTransactions,
        clock = clock
    )

    private fun PackageFormUiState.filled() = form.copy(
        name = "Нурофен",
        amount = "20",
        unit = TABLETS.toPresentationDTO()
    )

    /** Аптечка подставлена та, из которой человек пришёл. */
    @Test
    fun theShelfIsTheOneThePersonCameFrom() {
        assertEquals(HOME_KIT, viewModel().state.value.form.medKitId)
    }

    @Test
    fun aPackageIsWrittenWithWhatWasTyped() {
        val model = viewModel()

        watching(model.state) { state ->
            model.edit(state.value.filled())
            model.save()
            state.awaiting { it.saved != null }
        }

        val written = packages.packages.single()
        assertEquals("Нурофен", written.name)
        assertEquals(HOME_KIT, written.medKit.id)
        assertEquals("20", written.quantity.amount.toPlainString())
    }

    /**
     * Двойное нажатие «Сохранить» заводит одну коробку, а не две.
     *
     * Красная проверка: убрать сторожа идущей записи — коробок становится две.
     */
    @Test
    fun pressingSaveTwiceWritesOnePackage() {
        val model = viewModel()

        watching(model.state) { state ->
            model.edit(state.value.filled())
            model.save()
            model.save()
            state.awaiting { it.saved != null }
        }

        assertEquals(1, packages.packages.size)
    }

    /** Невалидная форма ничего не пишет и называет своё поле. */
    @Test
    fun anInvalidFormWritesNothing() {
        val model = viewModel()

        val state = watching(model.state) { state ->
            model.edit(state.value.filled().copy(amount = "0"))
            model.save()
            state.awaiting { it.error != null }
        }

        assertEquals(PackageFormError.AmountIsZero, state.error)
        assertEquals(emptyList<Any>(), packages.packages)
    }

    /** Ввод снимает отказ: человек уже правит то, на что ему указали. */
    @Test
    fun typingClearsTheRefusal() {
        val model = viewModel()

        val state = watching(model.state) { state ->
            model.edit(state.value.filled().copy(name = ""))
            model.save()
            val refused = state.awaiting { it.error != null }
            model.edit(refused.form.copy(name = "Н"))
            state.awaiting { it.error == null }
        }

        assertNull(state.error)
    }

    /**
     * Ввод человека не затирается тем, что дочитано из базы: заведённая другим экраном аптечка
     * меняет список выбора и не трогает ни одного набранного символа (наследство разбора #16).
     *
     * Красная проверка: собрать форму из того же потока, что и списки, — набранное исчезает.
     */
    @Test
    fun whatWasTypedSurvivesWhatComesFromTheDatabase() {
        val model = viewModel()

        val state = watching(model.state) { state ->
            model.edit(state.value.filled().copy(name = "Нурофен, начал печатать"))
            state.awaiting { it.form.name.startsWith("Нурофен,") }
            medKits.add(medKit(id = SHARED_KIT, name = "Дача"))
            state.awaiting { it.medKits.size == 2 }
        }

        assertEquals("Нурофен, начал печатать", state.form.name)
    }

    /** Аптечки не стало, пока форму держали открытой: отказ назван, коробка не заведена. */
    @Test
    fun aShelfThatIsGoneRefusesTheWriteOutLoud() {
        val model = viewModel()

        val state = watching(model.state) { state ->
            model.edit(state.value.filled())
            medKits.forget(HOME_KIT)
            model.save()
            state.awaiting { it.error != null }
        }

        assertEquals(PackageFormError.MedKitGone, state.error)
        assertEquals(emptyList<Any>(), packages.packages)
    }

    /** Полка, ждущая ответа на своё решение, коробку не принимает — и это сказано (PLAN E1). */
    @Test
    fun aShelfAwaitingItsOwnDecisionRefusesTheBox() {
        val busy = FakeMedKits(medKit(id = HOME_KIT, publication = MedKit.Publication.LOCAL).markRemoving())
        val model = PackageFormViewModel(
            adding = PackageAdding(packages, busy, QueueService(DirectTransactions, FakeQueue()), DirectTransactions, clock),
            describing = describing,
            vocabulary = FakeVocabulary(),
            packages = packages,
            medKits = busy,
            today = Today(clock),
            savedState = SavedStateHandle(mapOf(RouteArguments.MED_KIT_ID to HOME_KIT.toString()))
        )

        val state = watching(model.state) { state ->
            model.edit(state.value.filled())
            model.save()
            state.awaiting { it.error != null }
        }

        assertEquals(PackageFormError.MedKitBusy, state.error)
        assertEquals(emptyList<Any>(), packages.packages)
    }

    /** Открытая на правку форма показывает записанное, а не пустые поля. */
    @Test
    fun aFormOpenedForEditingShowsWhatIsStored() {
        packages.lying(pack(id = PACK, name = "Нурофен", quantity = tablets("20")))

        val state = watching(viewModel(packageId = PACK).state) { it.awaiting { s -> s.isEditing && s.stored != null } }

        assertEquals("Нурофен", state.form.name)
        assertEquals("20", state.stored?.quantity?.amount)
    }

    /**
     * Правка не трогает количество и место: их двигают пересчёт и перенос, у которых свой след
     * (PLAN D3, D7).
     *
     * Красная проверка: отдать сценарию правки ещё и количество — эта проверка краснеет числом.
     */
    @Test
    fun editingLeavesTheAmountAndTheShelfAlone() {
        packages.lying(pack(id = PACK, name = "Нурофен", quantity = tablets("20")))
        val model = viewModel(packageId = PACK)

        watching(model.state) { state ->
            val opened = state.awaiting { it.stored != null }.form
            model.edit(opened.copy(name = "Нурофен форте", amount = "1", manufacturer = "Reckitt"))
            model.save()
            state.awaiting { it.saved != null }
        }

        val stored = packages.packages.single()
        assertEquals("Нурофен форте", stored.name)
        assertEquals("Reckitt", stored.facts.manufacturer)
        assertEquals(tablets("20"), stored.quantity)
        assertEquals(HOME_KIT, stored.medKit.id)
    }

    /** Коробки не стало, пока форму держали открытой: писать некуда, и это сказано. */
    @Test
    fun aBoxThatIsGoneRefusesTheEdit() {
        packages.lying(pack(id = PACK, name = "Нурофен"))
        val model = viewModel(packageId = PACK)

        val state = watching(model.state) { state ->
            state.awaiting { it.stored != null }
            packages.forget(PACK)
            model.save()
            state.awaiting { it.error != null }
        }

        assertEquals(PackageFormError.PackageGone, state.error)
    }
}
