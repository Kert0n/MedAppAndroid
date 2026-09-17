package com.kert0n.medapp.presentation.pack

import com.kert0n.medapp.domain.Unavailability
import com.kert0n.medapp.domain.template.PackageTemplates
import com.kert0n.medapp.feature.packages.PackageAdding
import com.kert0n.medapp.feature.packages.PackageDescribing
import com.kert0n.medapp.feature.scan.PackageScanning
import com.kert0n.medapp.feature.template.TemplateSearching
import com.kert0n.medapp.feature.time.Today
import com.kert0n.medapp.fixture.DirectTransactions
import com.kert0n.medapp.fixture.FakeFollowing
import com.kert0n.medapp.fixture.FakeMedKits
import com.kert0n.medapp.fixture.FakePackageCodes
import com.kert0n.medapp.fixture.FakePackageTemplates
import com.kert0n.medapp.fixture.FakePackages
import com.kert0n.medapp.fixture.FakeQueue
import com.kert0n.medapp.fixture.FakeVocabulary
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.QuietClock
import com.kert0n.medapp.fixture.TABLETS
import com.kert0n.medapp.fixture.TABLET_FORM
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
            scanning = PackageScanning(FakePackageCodes()),
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

    /**
     * Новая буква отменяет прежний запрос **сразу**, а не когда истечёт её собственная пауза:
     * ответ на «пара», пришедший, пока «парац» ещё ждёт своих 300 мс, не показан.
     *
     * Красная проверка: держать паузу снаружи отмены (`debounce` перед `flatMapLatest`) — пока
     * новый текст ждёт паузы, старый запрос жив, и его ответ ложится под новый текст.
     */
    @Test
    fun aNewLetterCancelsTheOldQueryBeforeItsOwnPauseEnds() = runTest {
        templates.hold("пара")
        val model = viewModel()

        model.type("пара")
        advanceTimeBy(400)
        assertEquals(Suggestions.Searching, model.state.value.suggestions)

        model.type("парац")
        advanceTimeBy(100)
        templates.release("пара")
        advanceTimeBy(10)
        assertEquals(Suggestions.None, model.state.value.suggestions)

        advanceTimeBy(300)
        val shown = model.state.value.suggestions as Suggestions.Found
        assertEquals(listOf("Парацетамол", "Парацетамол-Экстра"), shown.templates.map { it.name })
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

    /** Пустое и пробелы запросом не становятся: справочнику нечего искать. */
    @Test
    fun blankTextAsksNothing() = runTest {
        val model = viewModel()
        model.type("   ")
        advanceTimeBy(400)
        assertEquals(Suggestions.None, model.state.value.suggestions)
        assertTrue(templates.asked.isEmpty())
    }

    /** В правке коробки справочник не спрашивают: название уже записано, подсказывать нечего. */
    @Test
    fun editingABoxAsksNothing() = runTest {
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

    /**
     * Выбор заполняет только то, что знает карточка: название — её; пустые поля — из неё;
     * введённое руками не затирается; количество и срок не трогаются; карточка запомнена и
     * уходит с записью.
     *
     * Красная проверка: писать поля карточки поверх введённого — производитель, который человек
     * напечатал сам, исчезает.
     */
    @Test
    fun pickingFillsOnlyWhatTheCardKnowsAndKeepsWhatWasTyped() = runTest {
        val card = template(
            id = EXTRA, name = "Парацетамол-Экстра", form = TABLET_FORM, category = "Обезболивающие",
            manufacturer = "Фармстандарт", country = "Россия", unit = TABLETS
        )
        templates.answer = PackageTemplates.Search.Found(listOf(card))
        val model = viewModel()
        model.edit(model.state.value.form.copy(name = "парац", manufacturer = "Bayer", amount = "20"))
        advanceTimeBy(400)

        model.pick(card.toPresentationDTO())
        advanceTimeBy(400)
        val form = model.state.value.form

        assertEquals("Парацетамол-Экстра", form.name)
        assertEquals("Bayer", form.manufacturer)
        assertEquals("Обезболивающие", form.category)
        assertEquals("Россия", form.country)
        assertEquals(TABLET_FORM.toPresentationDTO(), form.form)
        assertEquals(TABLETS.toPresentationDTO(), form.unit)
        assertEquals("20", form.amount)
        assertEquals("", form.expiresOn)
        assertEquals(EXTRA, form.templateId)
        // Выбранное — не напечатанное: справочник о нём заново не спрашивают, список свёрнут.
        assertEquals(Suggestions.None, model.state.value.suggestions)
        assertEquals(1, templates.asked.size)
    }

    /** Выбранная карточка уходит с записью: коробка помнит, откуда пришла (D3). */
    @Test
    fun theChosenCardTravelsWithTheRecord() = runTest {
        val model = viewModel()
        model.pick(template(id = EXTRA, name = "Парацетамол-Экстра").toPresentationDTO())
        advanceTimeBy(10)
        model.edit(model.state.value.form.copy(amount = "20"))

        model.save()
        advanceTimeBy(10)

        assertEquals(EXTRA, packages.packages.single().templateId)
    }

    /** Карточка, у которой словарь не знает формы и единицы, оставляет их пустыми — не выдумывает. */
    @Test
    fun aCardWithoutAKnownFormLeavesTheFormEmpty() = runTest {
        val model = viewModel()
        model.pick(template(name = "Микстура", form = null, unit = null).toPresentationDTO())
        advanceTimeBy(10)
        val form = model.state.value.form
        assertEquals("Микстура", form.name)
        assertEquals(null, form.form)
        assertEquals(null, form.unit)
    }

    /** Название, напечатанное после выбора, не перезаписывается поздним ответом: ответ в форму не пишет. */
    @Test
    fun aLateAnswerNeverWritesIntoTheForm() = runTest {
        templates.hold("парац")
        val model = viewModel()
        model.type("парац")
        advanceTimeBy(400)
        model.pick(template(id = PARACETAMOL, name = "Парацетамол").toPresentationDTO())
        advanceTimeBy(10)
        model.type("Парацетамол детский")
        templates.release("парац")
        advanceTimeBy(400)

        assertEquals("Парацетамол детский", model.state.value.form.name)
        assertEquals(PARACETAMOL, model.state.value.form.templateId)
    }

    private companion object {
        val PARACETAMOL: Uuid = Uuid.parse("00000000-0000-4000-8000-000000000071")
        val EXTRA: Uuid = Uuid.parse("00000000-0000-4000-8000-000000000072")
    }
}
