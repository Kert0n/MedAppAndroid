package com.kert0n.medapp.app.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.HiltTestActivity
import com.kert0n.medapp.domain.scan.PackageCodes
import com.kert0n.medapp.feature.packages.PackageQuery
import com.kert0n.medapp.fixture.FakePackageCodes
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.TABLETS
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.pressAfterTyping
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.medkit.toStorageEntity as toMedKitStorageEntity
import com.kert0n.medapp.storage.value.toStorageEntity
import com.kert0n.medapp.ui.theme.MedAppTheme
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **Артём завёл коробку сканером** (`docs/истории.md`). Он не любит печатать: код с упаковки должен
 * сделать за него всё, что может, — а чего реестр не знает, Артём допишет сам.
 *
 * **Что ловит:** путь от прочитанного кода до коробки на полке — без ручного ввода того, что код
 * уже сказал. Поведи приложение себя иначе — и сканер остался бы кнопкой, открывающей пустую форму,
 * то есть не сканером вовсе.
 *
 * Камеру проверке не подсунуть, и она не притворяется, что нажала на неё: историю начинает тот же
 * переход, который делает сканер, узнав DataMatrix. Что камера и вправду читает настоящий код с
 * коробки, держит `PrintedCodeTest` по снимкам владельца, а что она ведёт сюда — `ScannerViewModelTest`.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ScannerStoryTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<HiltTestActivity>()

    @Inject
    lateinit var database: MedAppDatabase

    @Inject
    lateinit var codes: PackageCodes

    /** Ожидание с запасом: сквозной проход тяжелее одиночной проверки (наследство прошлых историй). */
    private val WAIT = 5_000L

    private val code = "010460398804884021 15GE2L24E11ED\u001D91EE10".replace(" ", "")

    private val today: LocalDate = LocalDate.now()

    private lateinit var stacks: TabStacks

    @Before
    fun setUp() {
        hilt.inject()
        (codes as FakePackageCodes).answer = FakePackageCodes.found()
        runBlocking {
            database.vocabulary().save(
                units = listOf(TABLETS).map { it.toStorageEntity() },
                forms = listOf(TABLET_FORM).map { it.toStorageEntity() }
            )
            database.medKits().insertIfMissing(medKit(id = HOME_KIT, name = "Домашняя").toMedKitStorageEntity())
        }
        compose.setContent {
            MedAppTheme {
                val here = rememberTabStacks()
                stacks = here
                MedAppShell(stacks = here)
            }
        }
    }

    @Test
    fun artyomAddsABoxByScanningItsCode() {
        heAimsAtTheCodeOnTheBox()
        theFormAlreadyKnowsWhatTheRegistryKnows()
        heAddsWhatTheCodeCouldNotSay()
        theBoxIsOnTheShelf()
    }

    /** Наведённый код — тот же переход, который делает сканер, узнав DataMatrix. */
    private fun heAimsAtTheCodeOnTheBox() {
        compose.runOnUiThread { stacks.go(Screen.PackageForm(scannedCode = code)) }
        compose.waitUntil(WAIT) {
            compose.onAllNodesWithText("Новое лекарство").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Название, страну и сказанное реестром словами Артём не печатает: это всё уже в полях, и
     * описание среди них — иначе действующее вещество и дозировку он переписывал бы с коробки.
     */
    private fun theFormAlreadyKnowsWhatTheRegistryKnows() {
        compose.waitUntil(WAIT) { compose.onAllNodesWithText("Цетрин").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Индия").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(
            "таблетки, покрытые плёночной оболочкой, цетиризин, 10 мг, 20 таблеток в 2 блистерах"
        ).performScrollTo().assertIsDisplayed()
    }

    /**
     * Количество реестр называет словами, а форму — своим текстом, которого словарь не знает:
     * и то и другое остаётся человеку. Выдумай приложение число за него — коробка завелась бы с
     * остатком, которого в ней нет. Полку код не знает и знать не может: со сканера человек
     * приходит ниоткуда, и место коробки называет он сам.
     */
    private fun heAddsWhatTheCodeCouldNotSay() {
        // На какую полку легла коробка, код не знает и знать не может: это говорит человек.
        compose.onNodeWithText("Аптечка").performScrollTo().performClick()
        compose.onNodeWithText("Домашняя").performClick()
        compose.onNodeWithText("Форма выпуска").performScrollTo().performClick()
        compose.onNodeWithText("таблетки").performClick()
        compose.onNodeWithText("Единица").performScrollTo().performClick()
        compose.onNodeWithText("таблетка").performClick()
        compose.onNodeWithText("Количество").performScrollTo().performTextInput("20")
        compose.pressAfterTyping("Сохранить")
    }

    /** Заведённая коробка открывается карточкой и лежит на полке — там, где Артём её будет искать. */
    private fun theBoxIsOnTheShelf() {
        compose.waitUntil(WAIT) { compose.onAllNodesWithText("Сколько есть").fetchSemanticsNodes().isNotEmpty() }
        val stored = runBlocking { database.packageRepository().list(PackageQuery(medKitId = HOME_KIT), today).first() }
        assertEquals(listOf("Цетрин"), stored.map { it.facts.name })
    }
}
