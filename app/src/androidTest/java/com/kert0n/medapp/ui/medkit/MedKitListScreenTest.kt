package com.kert0n.medapp.ui.medkit

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.medkit.MedKitContents
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.SHARED_KIT
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.presentation.ScreenState
import com.kert0n.medapp.presentation.medkit.MedKitPresentationDTO
import com.kert0n.medapp.presentation.medkit.toPresentationDTO
import com.kert0n.medapp.ui.theme.MedAppTheme
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Список аптечек (PLAN H3 №2). Человек приходит сюда решить, куда идти, поэтому в строке стоит
 * то, что внутри, а не одно название.
 */
@RunWith(AndroidJUnit4::class)
class MedKitListScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private var opened: Uuid? = null
    private var added = 0
    private var searched = 0

    private fun show(state: ScreenState<List<MedKitPresentationDTO>>) {
        compose.setContent {
            MedAppTheme {
                MedKitListScreen(
                    state = state,
                    onOpen = { opened = it },
                    onAdd = { added++ },
                    onSearch = { searched++ }
                )
            }
        }
    }

    private fun shelf(
        id: Uuid = HOME_KIT,
        name: String = "Домашняя",
        location: String? = null,
        contents: MedKitContents = MedKitContents.EMPTY,
        participants: Long = 1,
        publication: MedKit.Publication = MedKit.Publication.LOCAL
    ) = medKit(id = id, name = name, location = location, publication = publication, participantCount = participants)
        .projection(contents)
        .toPresentationDTO()

    private fun ready(vararg shelves: MedKitPresentationDTO) = ScreenState.Ready(shelves.toList())

    /** До первого чтения — загрузка: «пусто» здесь было бы неправдой. */
    @Test
    fun beforeTheFirstReadTheScreenSaysItIsLoading() {
        show(ScreenState.Loading)

        compose.onNodeWithContentDescription("Загрузка").assertIsDisplayed()
    }

    /** Ни одной аптечки — рассказ о том, что это такое, и одна кнопка, а не две одинаковые. */
    @Test
    fun withNoShelvesTheScreenTellsWhatAShelfIsAndOffersTheFirst() {
        show(ready())

        compose.onNodeWithText("Завести аптечку").performClick()

        assertEquals(1, added)
    }

    /**
     * Карточка говорит, сколько внутри; пустая аптечка говорит, что пуста, — молчание неотличимо
     * от незагруженного.
     */
    @Test
    fun aCardSaysHowMuchLiesInside() {
        show(ready(shelf(contents = MedKitContents(packages = 12, expired = 0)), shelf(id = SHARED_KIT, name = "Дача")))

        compose.onNodeWithText("12 упаковок").assertIsDisplayed()
        compose.onNodeWithText("Пусто").assertIsDisplayed()
    }

    /**
     * Просрочка видна **словами**, а не одним цветом: цвета не видят ни в темноте, ни при
     * дальтонизме, ни экранным чтецом.
     *
     * Красная проверка: оставить у просрочки один цвет — этой строки на экране не окажется.
     */
    @Test
    fun anExpiryWarningIsSpelledOutNotJustColoured() {
        show(ready(shelf(contents = MedKitContents(packages = 12, expired = 2))))

        compose.onNodeWithText("2 просрочены").assertIsDisplayed()
        compose.onNodeWithContentDescription("Есть просроченные упаковки").assertIsDisplayed()
    }

    /** Без просрочек предупреждения нет: тревога без повода перестаёт быть тревогой. */
    @Test
    fun withoutExpiredPackagesThereIsNoWarning() {
        show(ready(shelf(contents = MedKitContents(packages = 3, expired = 0))))

        compose.onNodeWithContentDescription("Есть просроченные упаковки").assertDoesNotExist()
    }

    /** Общая аптечка отличается значком и числом участников, а не оттенком; местная не помечена. */
    @Test
    fun aSharedShelfIsMarkedBySignAndWordsAndALocalOneIsNot() {
        show(
            ready(
                shelf(name = "Домашняя"),
                shelf(id = SHARED_KIT, name = "Дача", participants = 3, publication = MedKit.Publication.PUBLISHED)
            )
        )

        compose.onNodeWithText("3 участника").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Общая аптечка").assertCountEquals(1)
    }

    /** Нажимается вся карточка, а не одна её строка. */
    @Test
    fun theWholeCardIsTheButton() {
        show(ready(shelf(contents = MedKitContents(packages = 4, expired = 0))))

        compose.onNodeWithText("4 упаковки").performClick()

        assertEquals(HOME_KIT, opened)
    }

    /** Поиск со списка аптечек ищет везде: человек не помнит, в какой аптечке лекарство. */
    @Test
    fun theSearchEntryLeadsToAllMedicines() {
        show(ready(shelf()))

        compose.onNodeWithText("Найти лекарство во всех аптечках").performClick()

        assertEquals(1, searched)
    }
}
