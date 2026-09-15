package com.kert0n.medapp.app.navigation

import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.navigation3.runtime.NavKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Стопка на каждое место (PLAN H3): человек уходит вглубь, переходит в другое место и,
 * вернувшись, застаёт своё таким, каким оставил.
 *
 * Проверяется сам механизм, без экранов: экранов вглубь у оболочки пока нет, а правило о них
 * уже есть — и появиться оно должно раньше, чем первый такой экран.
 */
@RunWith(AndroidJUnit4::class)
class TabStacksTest {

    @get:Rule
    val compose = createComposeRule()

    /** Лист вглубь: ключ, который местом не является, — как и любой экран вглубь. */
    @Serializable
    private data object Deep : NavKey

    private lateinit var stacks: TabStacks

    private fun given() {
        compose.setContent { stacks = rememberTabStacks() }
    }

    @Test
    fun theFirstPlaceIsWhereTheAppOpens() {
        given()

        compose.runOnIdle {
            assertEquals(Place.first.key, stacks.place)
            assertEquals(Place.first.key, stacks.screen)
        }
    }

    /**
     * Какой ход был последним — смена места или глубина, — знают стопки, а не ключи: с глубины
     * чужого места на корень своего ведёт та же смена места. По этому признаку оболочка выбирает
     * движение — мгновенную смену или сдвиг.
     */
    @Test
    fun theStacksKnowWhetherTheLastMoveChangedThePlace() {
        given()

        compose.runOnIdle {
            stacks.go(Deep)
            assertEquals(false, stacks.switchedPlace)
            assertEquals(true, stacks.isDeep)

            stacks.go(Place.entries[1].key)
            assertEquals(true, stacks.switchedPlace)
            assertEquals(false, stacks.isDeep)

            stacks.back()
            assertEquals(true, stacks.switchedPlace)
            assertEquals(Place.first.key, stacks.place)

            stacks.back()
            assertEquals(false, stacks.switchedPlace)
            assertEquals(Place.first.key, stacks.screen)
        }
    }

    @Test
    fun goingDeepPutsALeafOnTopOfItsOwnPlace() {
        given()

        compose.runOnIdle { stacks.go(Deep) }

        compose.runOnIdle {
            assertEquals(Place.first.key, stacks.place)
            assertEquals(Deep, stacks.screen)
        }
    }

    /**
     * Чужое место не трогает глубину своего.
     *
     * Красная проверка: одна стопка на всех — уход в чужое место либо стирает глубину своего,
     * либо кладёт чужой экран поверх неё, и возврат ведёт не туда.
     */
    @Test
    fun anotherPlaceKeepsItsOwnDepth() {
        given()

        compose.runOnIdle { stacks.go(Deep) }
        compose.runOnIdle { stacks.go(Place.entries[1].key) }
        compose.runOnIdle {
            assertEquals("чужое место показало чужую глубину", Place.entries[1].key, stacks.screen)
            stacks.go(Place.first.key)
        }

        compose.runOnIdle { assertEquals("своя глубина потеряна", Deep, stacks.screen) }
    }

    @Test
    fun goingBackTakesTheTopLeafOff() {
        given()

        compose.runOnIdle { stacks.go(Deep) }
        compose.runOnIdle { stacks.back() }

        compose.runOnIdle { assertEquals(Place.first.key, stacks.screen) }
    }

    /** С корня чужого места возврат ведёт к первому месту, а не закрывает приложение молча. */
    @Test
    fun goingBackFromTheRootOfAnotherPlaceLeadsToTheFirstOne() {
        given()

        compose.runOnIdle { stacks.go(Place.entries[1].key) }
        compose.runOnIdle { stacks.back() }

        compose.runOnIdle { assertEquals(Place.first.key, stacks.place) }
    }

    /** Повторное нажатие на своё место возвращает его к началу. */
    @Test
    fun tappingTheOwnPlaceAgainReturnsItToItsStart() {
        given()

        compose.runOnIdle { stacks.go(Deep) }
        compose.runOnIdle { stacks.backToRoot(Place.first.key) }

        compose.runOnIdle { assertEquals(Place.first.key, stacks.screen) }
    }

    /**
     * Поворот и смерть процесса — не действие человека: место и его глубина остаются теми же.
     *
     * Красная проверка: держать стопки обычным `remember` — после восстановления человек
     * оказывается у начала первого места.
     */
    @Test
    fun thePlaceAndItsDepthSurviveRecreation() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { stacks = rememberTabStacks() }
        compose.runOnIdle {
            stacks.go(Place.entries[1].key)
            stacks.go(Deep)
        }

        restoration.emulateSavedInstanceStateRestore()

        compose.runOnIdle {
            assertEquals(Place.entries[1].key, stacks.place)
            assertEquals(Deep, stacks.screen)
        }
    }
}
