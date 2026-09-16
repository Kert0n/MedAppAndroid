package com.kert0n.medapp.app.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.HiltTestActivity
import com.kert0n.medapp.domain.notification.NoticeDelivery
import com.kert0n.medapp.domain.notification.NotificationKey
import com.kert0n.medapp.domain.notification.NotificationKind
import com.kert0n.medapp.domain.notification.NotificationTarget
import com.kert0n.medapp.domain.notification.Reminder
import com.kert0n.medapp.domain.pack.ExpiryDate
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.MOSCOW
import com.kert0n.medapp.fixture.OTHER_PACK
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
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **История Нины** (PLAN U1 «история человека», `docs/истории.md`).
 *
 * У Нины дома аптечка на полке в ванной, и разбирает она её раз в полгода — когда доходят руки. В
 * этот раз руки не дошли: у двух коробок срок годности кончается **сегодня**, а она об этом не
 * знает. Одна из них — обычный нурофен, который она держит «на всякий случай»; вторая — цетрин,
 * которым она лечится прямо сейчас.
 *
 * Приложение говорит ей об этом при входе, и говорит один раз: если бы оно писало об этом строкой
 * где-то в списке аптечек, Нина увидела бы её через полгода — вместе со всем остальным.
 *
 * Дальше начинается то, ради чего история и написана. Нина нажимает на нурофен, чтобы посмотреть,
 * сколько его там, — и выбрасывает: просроченным она лечиться не станет. Возвращается — и тут
 * приложение обязано вести себя правильно дважды: **не потерять** вторую новость (о цетрине, с
 * которым ещё предстоит решать) и **не показывать** выброшенную коробку, которой уже нет.
 *
 * Самая обидная беда здесь — попап, закрывшийся сам от ухода на карточку: Нина вернулась бы и не
 * узнала о втором лекарстве вовсе, а сегодня новость уже сказана и второй раз не придёт.
 *
 * Руками такое не проверить: срок годности наступает раз в жизни коробки, и подгадать этот день
 * человеку нечем. В проверке он назначается строкой.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ExpiryNewsStoryTest {

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
            val packages = database.packageRepository()
            packages.add(expiringToday(PACK, "Нурофен"))
            packages.add(expiringToday(OTHER_PACK, "Цетрин"))
        }
    }

    @Test
    fun ninaReadsTheExpiryNewsAndThrowsOneBoxAwayWithoutLosingTheOther() {
        theNewsIsWaitingAtEntry()
        sheGoesToTheBoxAndThrowsItAway()
        theOtherBoxIsStillWaitingForHer()
        andTheNewsGoesAwayOnlyByTheCross()
    }

    /** Две коробки, у обеих срок кончается сегодня: об этом и новость. */
    private fun expiringToday(id: kotlin.uuid.Uuid, name: String) = pack(
        id = id,
        name = name,
        quantity = tablets("10"),
        form = TABLET_FORM,
        expiresOn = ExpiryDate(LocalDate.now(MOSCOW))
    )

    /** Новость ждёт её при входе — до всякого списка аптечек и до всякого поиска. */
    private fun theNewsIsWaitingAtEntry() {
        runBlocking {
            Scenarios(database, Instant.now()).reminderPromising.promise(
                listOf(PACK, OTHER_PACK).map {
                    Reminder(
                        key = NotificationKey(NotificationKind.EXPIRY_TODAY, it.toString()),
                        target = NotificationTarget.PackageCard(it),
                        dueAt = Instant.now()
                    )
                }
            )
        }
        compose.setContent { MedAppTheme { MedAppShell() } }

        compose.waitUntil(WAIT) {
            compose.onAllNodesWithText("Сегодня истекает срок годности").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Нурофен").assertIsDisplayed()
        compose.onNodeWithText("Цетрин").assertIsDisplayed()
    }

    /**
     * Нина идёт к нурофену — посмотреть, что с ним, — и выбрасывает его: просроченным она лечиться
     * не станет. Попап при этом остаётся: уход к коробке — продолжение того же разговора, а не
     * отказ от него.
     */
    private fun sheGoesToTheBoxAndThrowsItAway() {
        compose.onNodeWithText("Нурофен").performClick()
        compose.waitUntil(WAIT) { compose.onAllNodesWithText("Сколько есть").fetchSemanticsNodes().isNotEmpty() }
        // Новость никуда не делась, пока Нина смотрит на коробку.
        compose.onNodeWithText("Сегодня истекает срок годности").assertIsDisplayed()

        compose.onNodeWithContentDescription("Выбросить").performClick()
        compose.waitUntil(WAIT) { compose.onAllNodesWithText("Выбросить упаковку?").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Выбросить").performClick()
    }

    /**
     * Выброшенная коробка уходит из новости сама — показывать то, чего нет, нельзя, — а цетрин
     * остаётся: с ним Нине ещё решать, и об этом ей и сказали.
     */
    private fun theOtherBoxIsStillWaitingForHer() {
        compose.waitUntil(WAIT) { compose.onAllNodesWithText("Нурофен").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Сегодня истекает срок годности").assertIsDisplayed()
        compose.onNodeWithText("Цетрин").assertIsDisplayed()
    }

    /** Закрывает новость только крестик — и сегодня она больше не придёт. */
    private fun andTheNewsGoesAwayOnlyByTheCross() {
        compose.onNodeWithContentDescription("Закрыть").performClick()
        compose.waitUntil(WAIT) {
            compose.onAllNodesWithText("Сегодня истекает срок годности").fetchSemanticsNodes().isEmpty()
        }
        runBlocking {
            assertTrue(
                "обязательство осталось несказанным",
                Scenarios(database, Instant.now()).reminderStore.awaiting(NoticeDelivery.IN_APP_BANNER).isEmpty()
            )
        }
    }

    /**
     * **И вторая история Нины — та, что случается чаще.** В тот день она приложение не открыла:
     * работа, дети, не до аптечки. Открыла на следующий — и вот тут приложение не должно врать.
     *
     * «Сегодня истекает» — новость **одного дня**. Назавтра она неправда: срок уже вышел, коробка
     * просрочена, и сказать об этом надо иначе — статусом на самой коробке, а не вчерашним
     * баннером. Устаревшее обязательство снимает сверка, и попап назавтра не приходит вовсе
     * (PLAN D8: «если приложение не открыли в этот день, при следующем входе видна актуальная
     * просрочка, а устаревший баннер „сегодня истекает“ не показывается»).
     *
     * Без этого правила человек каждый день видел бы новость о том, что случилось когда-то, и
     * перестал бы её читать — а с ней перестал бы читать и настоящие.
     */
    @Test
    fun ninaDidNotOpenTheAppThatDayAndTomorrowTheNewsIsGone() {
        runBlocking {
            val now = Instant.now()
            Scenarios(database, now).reminderPromising.promise(
                listOf(
                    Reminder(
                        key = NotificationKey(NotificationKind.EXPIRY_TODAY, PACK.toString()),
                        target = NotificationTarget.PackageCard(PACK),
                        dueAt = now
                    )
                )
            )
            // Наступило завтра: сверка прошла новыми часами и сняла вчерашнюю новость.
            Scenarios(database, now.plus(1, ChronoUnit.DAYS)).dailyRound.run()
        }

        compose.setContent { MedAppTheme { MedAppShell() } }
        compose.waitUntil(WAIT) { compose.onAllNodesWithText("Аптечки").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText("Сегодня истекает срок годности").assertDoesNotExist()
    }
}
