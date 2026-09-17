package com.kert0n.medapp.tour

import android.graphics.Bitmap
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kert0n.medapp.HiltTestActivity
import com.kert0n.medapp.app.navigation.MedAppShell
import com.kert0n.medapp.fixture.TestPermissions
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.ui.theme.MedAppTheme
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.io.File
import javax.inject.Inject
import com.kert0n.medapp.feature.connectivity.Connection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **Обход экранов для документации** (`docs/screens/README.md`). Не проверка: настоящая оболочка
 * над миром [TourWorld] проходит места и экраны, как человек, и снимает экран устройства целиком —
 * с диалогами и листами. Включается только аргументом `tour` (`am instrument -e tour true`): в
 * обычном прогоне ему нечего утверждать.
 *
 * Связь у устройства на время обхода отнята: открытые коробки не спрашивают сервер, и очередь стоит
 * — видно всё, что ещё едет.
 */
abstract class ScreenTour {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<HiltTestActivity>()

    /** Сбой обхода — снимок того, что было на экране: подписи узнаются по нему, а не по журналу. */
    @get:Rule(order = 2)
    val onFailure = object : org.junit.rules.TestWatcher() {
        override fun failed(e: Throwable?, description: org.junit.runner.Description) {
            runCatching { snap("_fail/${description.methodName}") }
        }
    }

    @Inject
    lateinit var database: MedAppDatabase

    @Inject
    lateinit var connection: Connection

    protected lateinit var world: TourWorld

    protected val WAIT = 10_000L

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Before
    fun open() {
        assumeTrue("обход экранов включается только -e tour true", InstrumentationRegistry.getArguments().getString("tour") == "true")
        shell("svc wifi disable")
        shell("svc data disable")
        hilt.inject()
        runBlocking { withTimeout(30_000) { connection.online.first { !it } } }
        world = TourWorld(database)
        runBlocking { world.seed() }
        prepare()
        compose.setContent { MedAppTheme(darkTheme = darkTheme()) { MedAppShell() } }
    }

    /** Тёмная тема задаётся теме, а не системе: смена ночного режима пересоздала бы окно. */
    protected open fun darkTheme(): Boolean = false

    /** Что ещё поставить до появления оболочки: разрешения, настройки. */
    protected open fun prepare() = Unit

    @After
    fun close() {
        if (InstrumentationRegistry.getArguments().getString("tour") != "true") return
        TestPermissions.reset()
        shell("svc wifi enable")
        shell("svc data enable")
        shell("cmd uimode night no")
    }

    protected fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).close()
    }

    /** Снимок экрана устройства целиком: `<экран>/<состояние>.png`. */
    protected fun snap(name: String) {
        compose.waitForIdle()
        Thread.sleep(700)
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "экран $name не снялся" }
        val file = File(instrumentation.targetContext.filesDir, "tour/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    protected fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    protected fun shownPart(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    protected fun described(text: String) = compose.onAllNodesWithContentDescription(text).fetchSemanticsNodes().isNotEmpty()

    protected fun see(text: String) = compose.waitUntil(WAIT) { shown(text) || described(text) }

    protected fun tap(text: String) {
        see(text)
        if (shown(text)) compose.onAllNodesWithText(text).onFirst().performClick()
        else compose.onAllNodesWithContentDescription(text).onFirst().performClick()
        compose.waitForIdle()
    }

    protected fun tapPart(text: String) {
        compose.waitUntil(WAIT) { shownPart(text) }
        compose.onAllNodes(hasText(text, substring = true)).onFirst().performClick()
        compose.waitForIdle()
    }

    protected fun tapLast(text: String) {
        see(text)
        compose.onAllNodesWithText(text).onLast().performClick()
        compose.waitForIdle()
    }

    protected fun type(label: String, text: String) {
        see(label)
        compose.onAllNodesWithText(label).onFirst().performTextInput(text)
        closeSoftKeyboard()
    }

    /** Панель мест видна только у корней мест: в глубине «назад» ведёт к ней, а на корне закрыло бы окно. */
    private fun atPlaces() = shown("Аптечки") && shown("План") && shown("Сканер") && shown("Опции")

    protected fun place(name: String) {
        repeat(6) { if (!atPlaces()) back() }
        compose.waitUntil(WAIT) { atPlaces() }
        compose.onAllNodesWithText(name).onLast().performClick()
        compose.waitForIdle()
    }

    /** Системная «назад»: меню, диалог и лист закрываются ею так же, как у человека. */
    protected fun back() {
        compose.waitForIdle()
        shell("input keyevent 4")
        Thread.sleep(600)
        compose.waitForIdle()
    }

    protected fun dark(on: Boolean) {
        shell("cmd uimode night ${if (on) "yes" else "no"}")
        Thread.sleep(1500)
        compose.waitForIdle()
    }
}

/** Аптечки и коробки: места 2–11, поделиться и уборка полки. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class MedKitsTour : ScreenTour() {

    @Test
    fun medKitsAndBoxes() {
        see("Без ответа за прошлые дни")
        snap("02-med-kits/missed-popup")
        tap("Закрыть")
        see("Домашняя")
        snap("02-med-kits/filled")

        tap("Домашняя")
        see("Нурофен")
        snap("04-med-kit-contents/filled")
        tap("Просроченные")
        snap("04-med-kit-contents/narrowed")
        tap("Просроченные")
        tap("Поиск по аптечке")
        type("Поиск по аптечке", "аспирин")
        snap("04-med-kit-contents/nothing-found")
        tap("Сбросить")
        tap("Что сделать с аптечкой")
        snap("04-med-kit-contents/menu")
        back()

        tap("Нурофен")
        see("Сколько есть")
        snap("06-package-card/on-course")
        tap("Выбросить")
        snap("06-package-card/remove-dialog")
        back()
        tap("Пересчитать")
        see("Пересчитал и увидел")
        snap("09-recount/filled")
        back()
        tap("Перенести")
        snap("11-transfer/filled")
        back()
        tap("Принять")
        see("Сколько принял")
        snap("10-unplanned-intake/filled")
        back()
        tap("Править сведения")
        snap("08-package-edit/filled")
        back()
        back()

        tap("Парацетамол")
        see("Сколько есть")
        snap("06-package-card/expired")
        back()
        tap("Нурофен")
        see("Приёмы из этой коробки")
        tap("Приёмы из этой коробки")
        snap("19-intake-history/of-package")
        back()
        back()
        tap("Что сделать с аптечкой")
        tap("Править")
        snap("03-med-kit-form/edit")
        back()
        tap("Завести упаковку")
        snap("07-package-form/new")
        back()
        back()

        tap("Найти лекарство во всех аптечках")
        see("Поиск по всем аптечкам")
        snap("05-all-medicines/filled")
        back()

        tap("Дача")
        see("Но-шпа")
        snap("04-med-kit-contents/shared-in-flight")
        tap("Но-шпа")
        see("Сколько есть")
        snap("06-package-card/claimed-by-others")
        tap("Принять")
        see("Сколько принял")
        compose.onAllNodesWithText("Сколько принял").onFirst().performTextReplacement("20")
        closeSoftKeyboard()
        tapLast("Принять")
        see("Прежде чем записать")
        snap("10-unplanned-intake/questions")
        back()
        back()
        back()
        tap("Смекта")
        see("Сколько есть")
        snap("06-package-card/change-on-its-way")
        back()
        tap("Аспирин")
        see("Сколько есть")
        snap("06-package-card/refused-by-server")
        back()
        tap("Что сделать с аптечкой")
        snap("04-med-kit-contents/shared-menu")
        tap("Убрать")
        snap("23-removal/shared")
        back()
        back()

        tap("Рюкзак")
        snap("04-med-kit-contents/empty")
        tap("Что сделать с аптечкой")
        tap("Поделиться аптечкой")
        snap("20-sharing/local")
        back()
        back()

        tap("Присоединиться к аптечке")
        snap("22-joining/empty")
        back()
        tap("Завести аптечку")
        snap("03-med-kit-form/new")
    }
}

/** План: день, лечения, карточка пункта, источники и нехватка. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class PlanTour : ScreenTour() {

    @Test
    fun dayAndCourses() {
        see("Без ответа за прошлые дни")
        tap("Закрыть")
        place("План")
        tap("День")
        see("Нурофен от спины")
        snap("12-day/today")
        compose.onRoot().performTouchInput { swipeLeft() }
        compose.waitForIdle()
        snap("12-day/tomorrow")
        compose.onRoot().performTouchInput { swipeRight() }
        compose.waitForIdle()

        tapPart("14:00")
        see("Из какой коробки")
        snap("18-intake-card/planned")
        back()

        tap("Курсы")
        see("Витамин D")
        snap("13-courses/filled")
        tap("Нурофен от спины")
        snap("14-course-card/running")
        tapPart("Не хватает")
        see("Что можно сделать")
        snap("14-course-card/shortage-remedies")
        back()
        tap("Вся история")
        snap("19-intake-history/of-course")
        back()
        tap("Источники лечения")
        snap("16-course-sources/stack")
        tap("Подключить ещё")
        snap("17-source-picking/cards")
        back()
        back()
        back()

        tap("Витамин D")
        snap("15-course-form/draft")
        back()
        tap("Цетрин при аллергии")
        snap("14-course-card/cancelled")
        back()
        tap("Записать лечение")
        snap("15-course-form/new")
    }
}

/** День, когда уведомления у приложения выключены: строка беды над днём и в «Опциях». */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class NotificationsOffTour : ScreenTour() {

    override fun prepare() {
        TestPermissions.notifications = false
    }

    @Test
    fun dayAndOptionsWithoutNotifications() {
        see("Без ответа за прошлые дни")
        tap("Закрыть")
        place("План")
        tap("День")
        see("Напоминания не приходят")
        snap("12-day/notifications-off")
        place("Опции")
        see("Синхронизация")
        snap("27-options/root")
        tap("Разрешения")
        snap("27-permissions/notifications-off")
        back()
        tap("Уведомления")
        see("Сохранить")
        snap("27-settings/form")
        back()
        tap("Язык")
        snap("27-language/choice")
        back()
        tap("Синхронизация")
        see("Разобрал")
        snap("28-sync/rows")
    }
}

/** Сканер и отчёты — места, в которые приходят не за списком. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ScannerAndReportsTour : ScreenTour() {

    override fun prepare() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission("com.kert0n.medapp", android.Manifest.permission.CAMERA)
    }

    @Test
    fun scannerAndReports() {
        see("Без ответа за прошлые дни")
        tap("Закрыть")
        place("Сканер")
        Thread.sleep(2500)
        snap("24-scanner/live")
        place("Опции")
        tap("Разрешения")
        snap("27-permissions/all-allowed")
    }
}

/** Тёмная тема на самых плотных экранах. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class DarkTour : ScreenTour() {

    override fun darkTheme() = true

    @Test
    fun darkScreens() {
        see("Без ответа за прошлые дни")
        tap("Закрыть")
        see("Домашняя")
        snap("02-med-kits/dark")
        tap("Домашняя")
        see("Нурофен")
        snap("04-med-kit-contents/dark")
        tap("Нурофен")
        see("Сколько есть")
        snap("06-package-card/dark")
        place("План")
        tap("День")
        see("Нурофен от спины")
        snap("12-day/dark")
    }
}

/** Вход в день, когда у коробки кончается срок: попап при входе (PLAN C1 «Попап вне мест»). */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ExpiringTodayTour : ScreenTour() {

    override fun prepare() = runBlocking {
        world.expiringToday()
    }

    @Test
    fun expiringTodayPopup() {
        see("Без ответа за прошлые дни")
        tap("Закрыть")
        see("Сегодня истекает срок годности")
        snap("02-med-kits/expiring-today-popup")
    }
}
