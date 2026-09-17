package com.kert0n.medapp.ui.medkit

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.medkit.MedKitContents
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.OTHER_PACK
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.SHARED_KIT
import com.kert0n.medapp.fixture.expiry
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.projected
import com.kert0n.medapp.presentation.medkit.MedKitPresentationDTO
import com.kert0n.medapp.presentation.medkit.toPresentationDTO
import com.kert0n.medapp.presentation.pack.MedKitContentsUiState
import com.kert0n.medapp.presentation.pack.Narrowing
import com.kert0n.medapp.presentation.pack.Ordering
import com.kert0n.medapp.presentation.pack.PackagePresentationDTO
import com.kert0n.medapp.presentation.pack.RemovalRefusal
import com.kert0n.medapp.presentation.pack.RemovalStep
import com.kert0n.medapp.presentation.pack.toPresentationDTO
import com.kert0n.medapp.ui.theme.MedAppTheme
import java.time.LocalDate
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Содержимое полки и все лекарства (PLAN H3 №4, №5): что человек видит и что он может нажать.
 * Каким запросом экран спрашивает базу — `MedKitContentsViewModelTest`; подбор и порядок —
 * у запроса базы.
 */
@RunWith(AndroidJUnit4::class)
class MedKitContentsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private var reset = 0
    private var left = 0
    private var added = 0
    private var opened: Uuid? = null
    private var ordered: Ordering? = null
    private val narrowed = mutableListOf<Narrowing?>()

    private val today: LocalDate = LocalDate.parse("2026-09-15")

    private fun shelf(packages: Int) =
        medKit(id = HOME_KIT, name = "Домашняя").projection(MedKitContents(packages = packages, expired = 0)).toPresentationDTO()

    private fun row(id: Uuid, name: String, expiresOn: String? = null): PackagePresentationDTO =
        pack(id = id, name = name, expiresOn = expiresOn?.let(::expiry)).projected().toPresentationDTO()

    private fun show(state: MedKitContentsUiState) {
        compose.setContent {
            MedAppTheme {
                MedKitContentsScreen(
                    state = state,
                    onSearch = {},
                    onNarrow = { narrowed += it },
                    onOrder = { ordered = it },
                    onReset = { reset++ },
                    onOpen = { opened = it },
                    onAdd = { added++ },
                    onEdit = {},
                    onShare = {},
                    onAskToRemove = {},
                    onPickTarget = {},
                    onDismissRemoval = {},
                    onRemove = {},
                    onLeave = { left++ },
                    onBack = {}
                )
            }
        }
    }

    private fun contents(
        packages: List<PackagePresentationDTO> = listOf(row(PACK, "Нурофен")),
        text: String = "",
        narrowing: Narrowing? = null,
        ordering: Ordering = Ordering.NAME,
        everywhere: Boolean = false,
        placeNames: Map<Uuid, String> = emptyMap(),
        others: List<MedKitPresentationDTO> = listOf(
            medKit(id = SHARED_KIT, name = "Дача").projection(MedKitContents.EMPTY).toPresentationDTO()
        ),
        removing: RemovalStep? = null,
        removalRefusal: RemovalRefusal? = null
    ) = MedKitContentsUiState(
        medKit = if (everywhere) null else shelf(packages.size),
        isEverywhere = everywhere,
        packages = packages,
        placeNames = placeNames,
        others = others,
        isAreaEmpty = packages.isEmpty() && text.isEmpty() && narrowing == null,
        text = text,
        narrowing = narrowing,
        ordering = ordering,
        today = today,
        isLoaded = true,
        removing = removing,
        removalRefusal = removalRefusal
    )

    /** Пока база не ответила, экран ждёт: сказать «пусто» раньше — неправда. */
    @Test
    fun whileTheShelfIsUnreadTheScreenWaits() {
        show(MedKitContentsUiState(isEverywhere = false))

        compose.onNodeWithContentDescription("Загрузка").assertIsDisplayed()
        compose.onNodeWithText("Здесь пока ничего нет. Заведите упаковку — и она появится в списке.").assertDoesNotExist()
    }

    /**
     * Полки у нас больше нет — её убрали у всех или нас из неё вывели, и перечитывание при открытии
     * это записало (PLAN E4). Заводить в неё нечего: ни «Завести упаковку», ни плавающей кнопки, ни
     * меню полки — только слова.
     *
     * Красная проверка: полка, которой нет, читалась пустой полкой и звала завести в неё коробку.
     */
    @Test
    fun aShelfThatIsNoLongerOursOffersNothingToAdd() {
        show(MedKitContentsUiState(isEverywhere = false, isAreaEmpty = true, today = today, isLoaded = true))

        compose.onNodeWithText("Завести упаковку").assertDoesNotExist()
        compose.onNodeWithContentDescription("Завести упаковку").assertDoesNotExist()
        compose.onNodeWithContentDescription("Что сделать с аптечкой").assertDoesNotExist()
        compose.onNodeWithText("Этой аптечки у вас больше нет.").assertIsDisplayed()
    }

    /**
     * Пустая полка — рассказ и одна кнопка: поиска и чипов нет (они обещали бы содержимое), а
     * плавающая кнопка спрятана.
     *
     * Красная проверка: оставить плавающую кнопку — на пустом экране две кнопки «завести», и
     * выбирать человеку не из чего.
     */
    @Test
    fun anEmptyShelfTellsWhatToDoWithASingleButton() {
        show(contents(packages = emptyList()))

        compose.onNodeWithText("Здесь пока ничего нет. Заведите упаковку — и она появится в списке.").assertIsDisplayed()
        compose.onNodeWithText("Поиск по аптечке").assertDoesNotExist()
        compose.onNodeWithText("Просроченные").assertDoesNotExist()
        compose.onNodeWithContentDescription("Завести упаковку").assertDoesNotExist()
        compose.onNodeWithText("Завести упаковку").performClick()

        assertEquals(1, added)
    }

    /**
     * «Ничего не нашлось» — не «здесь пусто»: запрос и сужение остаются на месте, и предлагается
     * сброс, а не «завести упаковку».
     *
     * Красная проверка: одно сообщение на оба случая — человеку предложат не то.
     */
    @Test
    fun nothingFoundKeepsTheQueryAndOffersAReset() {
        show(contents(packages = emptyList(), text = "такого нет", narrowing = Narrowing.Expired))

        compose.onNodeWithText("Ничего не нашлось.").assertIsDisplayed()
        compose.onNodeWithText("Поиск по аптечке").assertIsDisplayed()
        compose.onNodeWithText("Просроченные").assertIsDisplayed()
        compose.onNodeWithText("Завести упаковку").assertDoesNotExist()
        compose.onNodeWithText("Сбросить").performClick()

        assertEquals(1, reset)
        assertEquals(0, added)
    }

    /** Во всех аптечках пусто — третье сообщение: заводить отсюда нечего, класть некуда. */
    @Test
    fun emptyEverywhereOffersNothingToPress() {
        show(contents(packages = emptyList(), everywhere = true))

        compose.onNodeWithText("Во всех аптечках пока пусто.").assertIsDisplayed()
        compose.onNodeWithText("Завести упаковку").assertDoesNotExist()
    }

    /**
     * Просроченная говорит словами, а не одной заливкой: заливку не видят ни экранный чтец, ни
     * человек, не различающий цвета. И из списка она не исчезает сама (REQ-027).
     *
     * Красная проверка: оставить заливку без строки — случай краснеет.
     */
    @Test
    fun anExpiredPackageIsNamedNotOnlyColoured() {
        show(contents(packages = listOf(row(PACK, "Ярлык", expiresOn = "2025-03-31"))))

        compose.onNodeWithText("Ярлык").assertIsDisplayed()
        compose.onNodeWithText("Просрочен 03.2025").assertIsDisplayed()
    }

    /** Истекающая предупреждает, не поднимая тревоги; о неизвестном сроке строка молчит. */
    @Test
    fun aPackageAboutToExpireWarnsAndAnUnknownExpiryIsSilent() {
        show(
            contents(
                packages = listOf(
                    row(PACK, "Нурофен", expiresOn = "2026-09-17"),
                    row(OTHER_PACK, "Аспирин")
                )
            )
        )

        compose.onNodeWithText("Истекает 17.09.2026").assertIsDisplayed()
        assertEquals(1, compose.onAllNodesWithText("Истекает", substring = true).fetchSemanticsNodes().size)
        compose.onNodeWithText("Просрочен ", substring = true).assertDoesNotExist()
    }

    /**
     * На экране всех лекарств каждая строка называет свою аптечку, и класть там некуда — FAB
     * нет. Внутри одной полки имя не повторяется: человек знает, куда пришёл.
     */
    @Test
    fun everywhereEachRowNamesItsShelfAndThereIsNowhereToAdd() {
        show(contents(everywhere = true, placeNames = mapOf(HOME_KIT to "Домашняя")))

        compose.onNodeWithText("Все лекарства").assertIsDisplayed()
        compose.onNodeWithText("Домашняя").assertIsDisplayed()
        compose.onNodeWithContentDescription("Завести упаковку").assertDoesNotExist()
    }

    @Test
    fun insideAShelfItsNameIsNotRepeatedOnEveryRow() {
        show(contents())

        // Одно вхождение — заголовок; на строке имени нет.
        assertEquals(1, compose.onAllNodesWithText("Домашняя").fetchSemanticsNodes().size)
        compose.onNodeWithContentDescription("Завести упаковку").assertIsDisplayed()
    }

    /**
     * Сужение снимается нажатием на выбранное, а порядок — нет: порядок есть всегда, и его чип
     * открывает список, не сбрасывая выбранного.
     *
     * Красная проверка: обращаться с сортировкой как с сужением — нажатие на «по сроку» вернуло бы
     * порядок к названию, и сменить его на «по количеству» удалось бы только со второго раза.
     */
    @Test
    fun tappingTheChosenNarrowingClearsItButTheOrderChipOpensTheList() {
        show(contents(narrowing = Narrowing.Expired, ordering = Ordering.EXPIRY))

        compose.onNodeWithText("Просроченные").performClick()
        assertEquals(listOf<Narrowing?>(null), narrowed)

        compose.onNodeWithText("Сортировка: по сроку").performScrollTo().performClick()
        compose.onNodeWithText("по количеству").performClick()

        assertEquals(Ordering.QUANTITY, ordered)
        assertEquals(1, narrowed.size)
    }

    /** Нажимается вся карточка, а не одна её строка. */
    @Test
    fun theWholeRowIsTheButton() {
        show(contents())

        compose.onNodeWithText("20 таблетка").performClick()

        assertEquals(PACK, opened)
    }

    /** Меню полки есть только у названной: у всех лекарств править и убирать нечего. */
    @Test
    fun onlyANamedShelfHasAMenu() {
        show(contents(everywhere = true))
        compose.onNodeWithContentDescription("Что сделать с аптечкой").assertDoesNotExist()
    }

    @Test
    fun aNamedShelfOffersToEditAndToRemove() {
        show(contents())

        compose.onNodeWithContentDescription("Что сделать с аптечкой").performClick()

        compose.onNodeWithText("Править").assertIsDisplayed()
        compose.onNodeWithText("Убрать").assertIsDisplayed()
    }

    /**
     * Уборка непустой полки называет, сколько в ней коробок, и обе судьбы — последствиями, а не
     * словом «удалить».
     */
    @Test
    fun removingAFullShelfNamesItsBoxesAndBothFates() {
        show(contents(packages = listOf(row(PACK, "Нурофен"), row(OTHER_PACK, "Аспирин")), removing = RemovalStep.ASKING))

        compose.onNodeWithText("Убрать аптечку «Домашняя»?").assertIsDisplayed()
        compose.onNodeWithText("В аптечке 2 упаковки. Решите, что с ними будет.").assertIsDisplayed()
        compose.onNodeWithText("Перенести и убрать").assertIsDisplayed()
        compose.onNodeWithText("Убрать вместе с лекарствами").assertIsDisplayed()
    }

    /** Переносить некуда — так и сказано, а не спрятана кнопка. */
    @Test
    fun withNowhereToMoveTheDialogSaysSo() {
        show(contents(others = emptyList(), removing = RemovalStep.ASKING))

        compose.onNodeWithText("Переносить некуда: другой аптечки нет.").assertIsDisplayed()
        compose.onNodeWithText("Перенести и убрать").assertDoesNotExist()
        compose.onNodeWithText("Убрать вместе с лекарствами").assertIsDisplayed()
    }

    /** Отказ цели виден там же, где выбирают цель: другую полку выбирают, не начиная сначала. */
    @Test
    fun aTargetRefusalIsToldInThePicker() {
        show(contents(removing = RemovalStep.PICKING_TARGET, removalRefusal = RemovalRefusal.TARGET_GONE))

        compose.onNodeWithText("Куда перенести лекарства?").assertIsDisplayed()
        compose.onNodeWithText("Той аптечки больше нет — выберите другую.").assertIsDisplayed()
    }

    /** Цель переноса выбирается по имени, и пока не выбрана — переносить нечего. */
    @Test
    fun theTargetIsChosenByName() {
        show(contents(removing = RemovalStep.PICKING_TARGET))

        compose.onNodeWithText("Куда перенести лекарства?").assertIsDisplayed()
        compose.onNodeWithText("Перенести и убрать").assertIsNotEnabled()
        compose.onNodeWithText("Дача").performClick()
        compose.onNodeWithText("Перенести и убрать").assertIsEnabled()
    }

    /**
     * Решение, которое ещё едет серверу, видно **там, где вещь**: человек ищет коробку в списке и
     * должен понимать, почему число у неё оценочное (PLAN E1).
     *
     * Красная проверка: держать пометку только в карточке — из списка не видно, что с коробкой
     * что-то происходит, и человек считает показанное число окончательным.
     */
    @Test
    fun aDecisionOnItsWayIsMarkedInTheListToo() {
        show(
            MedKitContentsUiState(
                medKit = medKit(id = HOME_KIT, name = "Домашняя")
                    .projection(MedKitContents(packages = 1, expired = 0)).toPresentationDTO(),
                packages = listOf(
                    pack(id = PACK, name = "Нурофен").projected(hasUnconfirmedChanges = true).toPresentationDTO()
                ),
                today = today,
                isLoaded = true
            )
        )

        compose.onNodeWithText("Изменение в пути").assertIsDisplayed()
    }

    /**
     * Не всякое решение в пути меняет число: правка сведений и перенос его не трогают, и
     * `hasUnconfirmedChanges` у такой коробки пуст. Пометка нужна ей ровно так же — иначе
     * янтарная подложка остаётся единственным знаком, а цвет один ничего не говорит человеку,
     * который его не различает (разбор CodeRabbit, PLAN H3 «Дизайн»).
     */
    @Test
    fun aChangeThatDoesNotTouchTheNumberIsMarkedAllTheSame() {
        show(
            MedKitContentsUiState(
                medKit = medKit(id = HOME_KIT, name = "Домашняя")
                    .projection(MedKitContents(packages = 1, expired = 0)).toPresentationDTO(),
                packages = listOf(
                    pack(id = PACK, name = "Нурофен").markChanging(Uuid.random()).projected().toPresentationDTO()
                ),
                today = today,
                isLoaded = true
            )
        )

        compose.onNodeWithText("Изменение в пути").assertIsDisplayed()
    }

    /**
     * Выброшенная коробка — состояние конечное: она видна призраком, но **не нажимается**. Открыть
     * её карточку значило бы предложить человеку действия над тем, чего уже нет (решение владельца
     * 2026-09-17).
     *
     * Красная проверка: оставить строку нажимаемой — человек открывает карточку удалённой коробки
     * и пробует из неё принять.
     */
    @Test
    fun aGhostBoxDoesNotOpen() {
        show(
            MedKitContentsUiState(
                medKit = medKit(id = HOME_KIT, name = "Домашняя")
                    .projection(MedKitContents(packages = 1, expired = 0)).toPresentationDTO(),
                packages = listOf(
                    pack(id = PACK, name = "Цетрин").markRemoving(Uuid.random()).projected().toPresentationDTO()
                ),
                today = today,
                isLoaded = true
            )
        )

        // Спрашивается само состояние, а не только исход нажатия: погашенная карточка может
        // сохранить действие в семантике, и тогда «не открылось» ничего бы не доказывало
        // (разбор CodeRabbit).
        compose.onNodeWithText("Цетрин").assertIsNotEnabled().performClick()

        assertNull(opened)
    }
}
