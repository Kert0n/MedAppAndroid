package com.kert0n.medapp.ui.medkit

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
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

/** Список аптечек (PLAN H3 №2): что человек видит и что он может нажать. */
@RunWith(AndroidJUnit4::class)
class MedKitListScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private var opened: Uuid? = null
    private var added = 0
    private var searched = 0

    private fun shelf(
        id: Uuid = HOME_KIT,
        name: String = "Домашняя",
        location: String? = null,
        publication: MedKit.Publication = MedKit.Publication.LOCAL,
        participants: Long = 1,
        contents: MedKitContents = MedKitContents.EMPTY
    ): MedKitPresentationDTO = medKit(
        id = id, name = name, location = location, publication = publication, participantCount = participants
    ).projection(contents).toPresentationDTO()

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

    /**
     * Ни одной аптечки — рассказ и одна кнопка: плавающая тогда спрятана.
     *
     * Красная проверка: оставить плавающую кнопку — на пустом экране две кнопки «завести», и
     * выбирать человеку не из чего.
     */
    @Test
    fun anEmptyListTellsWhatToDoWithASingleButton() {
        show(ScreenState.Ready(emptyList()))

        compose.onNodeWithText("Аптечек пока нет. Заведите первую — в неё и положим лекарства.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Завести аптечку").assertDoesNotExist()
        compose.onNodeWithText("Завести аптечку").performClick()

        assertEquals(1, added)
    }

    /** Пока база не ответила, экран ждёт, а не говорит «пусто». */
    @Test
    fun whileTheListIsUnreadTheScreenWaits() {
        show(ScreenState.Loading)

        compose.onNodeWithContentDescription("Загрузка").assertIsDisplayed()
        compose.onNodeWithText("Аптечек пока нет. Заведите первую — в неё и положим лекарства.").assertDoesNotExist()
    }

    /**
     * Первым видно не название, а что внутри: число коробок и просрочка — **словами**, а не
     * одним цветом.
     *
     * Красная проверка: красить просроченные цветом без слов — экранный чтец молчит, и человек
     * с дальтонизмом не отличит их от прочих.
     */
    @Test
    fun aShelfSaysWhatIsInsideInWords() {
        show(ScreenState.Ready(listOf(shelf(contents = MedKitContents(packages = 12, expired = 2)))))

        compose.onNodeWithText("12 упаковок").assertIsDisplayed()
        compose.onNodeWithText("2 просрочены").assertIsDisplayed()
    }

    /** Общая аптечка названа общей, и число участников сказано словами. */
    @Test
    fun aSharedShelfSaysSoAndCountsItsParticipants() {
        show(
            ScreenState.Ready(
                listOf(shelf(id = SHARED_KIT, name = "Дача", publication = MedKit.Publication.PUBLISHED, participants = 3))
            )
        )

        compose.onNodeWithText("Общая · 3 участника").assertIsDisplayed()
    }

    /** Нажимается вся карточка, а не одна её строка. */
    @Test
    fun theWholeCardIsTheButton() {
        show(ScreenState.Ready(listOf(shelf(contents = MedKitContents(packages = 4, expired = 0)))))

        compose.onNodeWithText("4 упаковки").performClick()

        assertEquals(HOME_KIT, opened)
    }

    /** Поиск отсюда ведёт ко всем лекарствам: ища лекарство, человек не помнит его аптечку. */
    @Test
    fun theSearchLeadsToAllMedicines() {
        show(ScreenState.Ready(listOf(shelf())))

        compose.onNodeWithText("Найти лекарство во всех аптечках").performClick()

        assertEquals(1, searched)
    }
}
