package com.kert0n.medapp.presentation.pack

import com.kert0n.medapp.domain.Unavailability
import com.kert0n.medapp.domain.template.PackageTemplates
import com.kert0n.medapp.feature.packages.PackageAdding
import com.kert0n.medapp.feature.packages.PackageDescribing
import com.kert0n.medapp.feature.template.TemplateSearching
import com.kert0n.medapp.feature.time.Today
import com.kert0n.medapp.fixture.DirectTransactions
import com.kert0n.medapp.fixture.FakeFollowing
import com.kert0n.medapp.fixture.FakeMedKits
import com.kert0n.medapp.fixture.FakePackageTemplates
import com.kert0n.medapp.fixture.FakePackages
import com.kert0n.medapp.fixture.FakeQueue
import com.kert0n.medapp.fixture.FakeVocabulary
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.QuietClock
import com.kert0n.medapp.fixture.TABLETS
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.template
import com.kert0n.medapp.presentation.value.toPresentationDTO
import com.kert0n.medapp.queue.QueueService
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Подсказки справочника на форме упаковки (PLAN H3 №7, U2): когда экран спрашивает справочник и
 * что делает с ответом. Время здесь виртуальное — пауза печати измеряется, а не ждётся.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PackageFormSuggestionsTest {

    private val clock: Clock = Clock.fixed(Instant.parse("2026-09-15T09:00:00Z"), ZoneId.of("Europe/Moscow"))

    private val packages = FakePackages()

    private val medKits = FakeMedKits(medKit(id = HOME_KIT))

    private val queue = QueueService(DirectTransactions, FakeQueue())

    private val templates = FakePackageTemplates(
        template(id = PARACETAMOL, name = "Парацетамол"),
        template(id = EXTRA, name = "Парацетамол-Экстра", manufacturer = "Фармстандарт")
    )

    @After
    fun resetMain() = Dispatchers.resetMain()

    /** Экран на виртуальных часах теста: `viewModelScope` идёт на них же, и `delay` внутри — тоже. */
    private fun TestScope.viewModel(opened: PackageFormViewModel.Opened = PackageFormViewModel.Opened(medKitId = HOME_KIT)): PackageFormViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return PackageFormViewModel(
            adding = PackageAdding(packages, medKits, queue, DirectTransactions, clock),
            describing = PackageDescribing(packages, FakeFollowing(), queue, DirectTransactions, clock),
            searching = TemplateSearching(templates),
            packages = packages,
            vocabulary = FakeVocabulary(),
            medKits = medKits,
            today = Today(clock, QuietClock),
            opened = opened
        ).also { it.state.launchIn(backgroundScope) }
    }

    private fun PackageFormViewModel.type(text: String) = edit(state.value.form.copy(name = text))

    /**
     * Пять букв за 200 мс — один запрос: справочник спрашивают, когда человек остановился, а не
     * на каждую букву.
     *
     * Красная проверка: снять паузу — запросов столько же, сколько букв.
     */
    @Test
    fun fiveLettersInTwoHundredMillisecondsAskTheServerOnce() = runTest {
        val model = viewModel()

        for (typed in listOf("п", "па", "пар", "пара", "парац")) {
            model.type(typed)
            advanceTimeBy(40)
        }
        advanceTimeBy(400)

        assertEquals(listOf("парац"), templates.asked.map { it.text })
        val found = model.state.value.suggestions as Suggestions.Found
        assertEquals(listOf("Парацетамол", "Парацетамол-Экстра"), found.templates.map { it.name })
    }

    /**
     * Ответ на «пара» пришёл после ответа на «парац» — показан «парац»: старый запрос отменён
     * вместе с ответом, и перекрыть новый ему нечем.
     *
     * Красная проверка: собирать ответы без отмены (`flatMapConcat`) — поздний «пара» перепишет
     * список.
     */
    @Test
    fun aLateAnswerToAnOldQueryDoesNotOverwriteTheNewOne() = runTest {
        templates.hold("пара")
        val model = viewModel()

        model.type("пара")
        advanceTimeBy(400)
        assertEquals(Suggestions.Searching, model.state.value.suggestions)

        model.type("парац")
        advanceTimeBy(400)
        val shown = model.state.value.suggestions as Suggestions.Found
        assertEquals(listOf("Парацетамол", "Парацетамол-Экстра"), shown.templates.map { it.name })

        templates.release("пара")
        advanceTimeBy(10)
        assertEquals(shown, model.state.value.suggestions)
        assertEquals(listOf("пара", "парац"), templates.asked.map { it.text })
    }

    /** Без связи подсказок нет, причина названа, а форма живёт: коробку заводят руками. */
    @Test
    fun withoutConnectionTheReasonIsNamedAndTheFormStillWrites() = runTest {
        templates.answer = PackageTemplates.Search.Unavailable(Unavailability.NO_CONNECTION)
        val model = viewModel()

        model.type("Нурофен")
        advanceTimeBy(400)
        assertEquals(Suggestions.Unavailable(Unavailability.NO_CONNECTION), model.state.value.suggestions)

        model.edit(model.state.value.form.copy(amount = "20", unit = TABLETS.toPresentationDTO()))
        model.save()
        advanceTimeBy(10)
        assertNotNull(model.state.value.saved)
        assertEquals("Нурофен", packages.packages.single().name)
    }

    /** Пустое и пробелы запросом не становятся; в правке коробки справочник не спрашивают. */
    @Test
    fun emptyTextAndEditingAskNothing() = runTest {
        val model = viewModel()
        model.type("   ")
        advanceTimeBy(400)
        assertEquals(Suggestions.None, model.state.value.suggestions)
        assertTrue(templates.asked.isEmpty())

        packages.lying(pack(id = PACK, name = "Нурофен"))
        val editing = viewModel(PackageFormViewModel.Opened(packageId = PACK))
        advanceTimeBy(10)
        editing.type("Нурофен форте")
        advanceTimeBy(400)
        assertEquals(Suggestions.None, editing.state.value.suggestions)
        assertTrue(templates.asked.isEmpty())
    }

    /** Ничего не нашлось — не ошибка: список пуст, причины нет. */
    @Test
    fun nothingFoundIsNotAFailure() = runTest {
        val model = viewModel()
        model.type("Ибупрофен")
        advanceTimeBy(400)
        assertEquals(Suggestions.Found(emptyList()), model.state.value.suggestions)
    }

    private companion object {
        val PARACETAMOL: Uuid = Uuid.parse("00000000-0000-4000-8000-000000000071")
        val EXTRA: Uuid = Uuid.parse("00000000-0000-4000-8000-000000000072")
    }
}
