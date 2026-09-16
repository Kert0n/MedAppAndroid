package com.kert0n.medapp.app.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.HiltTestActivity
import com.kert0n.medapp.domain.notification.NotificationKey
import com.kert0n.medapp.domain.notification.NotificationKind
import com.kert0n.medapp.domain.notification.NotificationTarget
import com.kert0n.medapp.domain.notification.Reminder
import com.kert0n.medapp.domain.pack.ExpiryDate
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.MOSCOW
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.TABLETS
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.allowNotifications
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.medkit.toStorageEntity as toMedKitStorageEntity
import com.kert0n.medapp.storage.value.toStorageEntity
import com.kert0n.medapp.ui.theme.MedAppTheme
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Попап «Сегодня истекает» живёт **на местах** (PLAN C1 «Попап вне мест»): ушёл человек на карточку
 * коробки — попап прячется, вернулся — он снова на месте.
 *
 * Модальный диалог над карточкой не давал с коробкой ничего сделать: «Выбросить» и «назад» не
 * отвечали, а проверка это пропускала — щелчок Compose идёт по семантике и проходит сквозь окно
 * диалога (разбор U5, касанием на `BigLatest`). Поэтому здесь спрашивается, **виден ли** попап над
 * карточкой, а не нажимается ли её кнопка.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ExpiringTodayOverPlacesTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<HiltTestActivity>()

    @Inject
    lateinit var database: MedAppDatabase

    private val WAIT = 5_000L

    @Before
    fun setUp() {
        allowNotifications()
        hilt.inject()
        runBlocking {
            database.vocabulary().save(
                units = listOf(TABLETS).map { it.toStorageEntity() },
                forms = listOf(TABLET_FORM).map { it.toStorageEntity() }
            )
            database.medKits().insertIfMissing(medKit(id = HOME_KIT).toMedKitStorageEntity())
            database.packageRepository().add(
                pack(id = PACK, name = "Нурофен", quantity = tablets("10"), form = TABLET_FORM, expiresOn = ExpiryDate(LocalDate.now(MOSCOW)))
            )
            Scenarios(database, Instant.now()).reminderPromising.promise(
                listOf(
                    Reminder(
                        key = NotificationKey(NotificationKind.EXPIRY_TODAY, PACK.toString()),
                        target = NotificationTarget.PackageCard(PACK),
                        dueAt = Instant.now()
                    )
                )
            )
        }
        compose.setContent { MedAppTheme { MedAppShell() } }
        compose.waitUntil(WAIT) {
            compose.onAllNodesWithText("Сегодня истекает срок годности").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Нажатие на коробку ведёт на её карточку, и **над карточкой попапа нет**: иначе человек видит
     * коробку, но ничего не может с ней сделать.
     */
    @Test
    fun theBoxCardIsNotCoveredByThePopup() {
        compose.onNodeWithText("Нурофен").performClick()
        compose.waitUntil(WAIT) { compose.onAllNodesWithText("Сколько есть").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText("Сегодня истекает срок годности").assertDoesNotExist()
    }

    /** Вернулся с карточки — попап снова на месте: закрывает его только крестик. */
    @Test
    fun thePopupWaitsForTheReturnToAPlace() {
        compose.onNodeWithText("Нурофен").performClick()
        compose.waitUntil(WAIT) { compose.onAllNodesWithText("Сколько есть").fetchSemanticsNodes().isNotEmpty() }
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        compose.waitUntil(WAIT) {
            compose.onAllNodesWithText("Сегодня истекает срок годности").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Сегодня истекает срок годности").assertIsDisplayed()
    }
}
