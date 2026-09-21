package com.kert0n.medapp.tour

import android.Manifest
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidTest
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.kert0n.medapp.app.navigation.MedAppShell
import com.kert0n.medapp.app.navigation.Screen
import com.kert0n.medapp.app.navigation.TabStacks
import com.kert0n.medapp.app.navigation.rememberTabStacks
import com.kert0n.medapp.domain.pack.ExpiryDate
import com.kert0n.medapp.ui.scan.scanned
import com.kert0n.medapp.domain.value.Dose
import com.kert0n.medapp.domain.value.Quantity
import com.kert0n.medapp.feature.notification.DailyRound
import com.kert0n.medapp.feature.notification.ReminderOutbox
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.platform.notifications.NotificationChannels
import com.kert0n.medapp.fixture.packageRepository
import java.math.BigDecimal
import javax.inject.Inject
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **Съёмка демонстрации к защите** (`docs/WorkInProgress/Презентация/Сценарий демонстрации.md`).
 * Не проверка и не обход экранов: тот же мир [TourWorld] проходится по сюжету, но медленно - так,
 * чтобы зритель успевал прочесть экран. Запись ведёт `adb shell screenrecord` снаружи.
 *
 * Состояния, которые вживую за две минуты не показать, мир заводит заранее: просроченная пачка,
 * чужая бронь, изменение в пути и отказ сервера по версии.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class DemoFilm : ScreenTour() {

    private lateinit var stacks: TabStacks

    /** Стопки мест нужны, чтобы открыть форму так же, как её открывает сканер, узнавший код. */
    @Composable
    override fun Content() {
        val here = rememberTabStacks()
        stacks = here
        MedAppShell(stacks = here)
    }

    /**
     * Настоящий код с настоящей коробки: снимок владельца из `androidTest/assets/scan`, тот же,
     * которым `PrintedCodeTest` держит чтение DataMatrix. Читает его тот же ML Kit, что и сканер
     * в приложении - камере эмулятора этот код показать негде, а распознавание здесь настоящее.
     */
    private fun codeFromPhoto(): String {
        val context = InstrumentationRegistry.getInstrumentation().context
        val photo = context.assets.open("scan/charcoal.jpg").use { BitmapFactory.decodeStream(it) }
        val scanner = BarcodeScanning.getClient()
        try {
            val found = Tasks.await(scanner.process(InputImage.fromBitmap(photo, 0)))
            val code = found.firstOrNull() ?: error("код на снимке не распознан")
            return requireNotNull(code.scanned()) { "код пустой" }.text
        } finally {
            scanner.close()
        }
    }

    @Inject
    lateinit var dailyRound: DailyRound

    @Inject
    lateinit var reminders: ReminderOutbox

    @Inject
    lateinit var channels: NotificationChannels

    override fun prepare() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.grantRuntimePermission("com.kert0n.medapp", Manifest.permission.POST_NOTIFICATIONS)
        automation.grantRuntimePermission("com.kert0n.medapp", Manifest.permission.CAMERA)
        // Переходы окон идут своим чередом: выключенные системой, они дали бы ту же склейку, что
        // и стоящие часы Compose.
        for (scale in listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")) {
            shell("settings put global $scale 1")
        }
        channels.ensure()
        runBlocking {
            world.intakeDueNow()
            // Вторая пачка того же лекарства: у неё срок ближе, и на записи видно, зачем человек
            // меняет порядок расходования. Одной пачкой приоритет не показать.
            database.packageRepository().add(
                pack(
                    id = Uuid.random(), name = "Нурофен Экспресс", medKit = world.home.ref,
                    quantity = Quantity(BigDecimal("10"), world.pieces),
                    form = world.tabletsForm, category = "Обезболивающие",
                    expiresOn = ExpiryDate(world.today.plusMonths(2)),
                    defaultIntakeAmount = Dose(Quantity(BigDecimal("1"), world.pieces)),
                    addedAt = world.now
                )
            )
        }
    }

    /**
     * Кадр за кадром в настоящем времени. Часы Compose в проверке стоят и прыгают вперёд только
     * там, где проверка ждёт покоя, — переход при этом проскакивает целиком, и запись выходит
     * слайд-шоу. Здесь часы идут, как у человека: кадр, и столько же сна.
     */
    private fun play(seconds: Double) {
        // Отобрать у проверки автоматический ход часов нельзя: системные события идут в настоящем
        // времени, а распознаватель жеста живёт на этих часах — перетаскивание перестаёт
        // срабатывать вовсе (проверено прогоном). Поэтому кадры крутятся **вдобавок** к нему: в
        // паузах, где иначе ничего не двигалось бы.
        repeat((seconds * 1000 / FRAME).toInt()) {
            compose.mainClock.advanceTimeByFrame()
            Thread.sleep(FRAME)
        }
    }

    /**
     * Пауза между действиями: на записи она и есть темп показа. [PACE] подогнан так, чтобы весь
     * сюжет занимал около двух минут - столько отведено демонстрации на защите.
     */
    private fun beat(seconds: Double = 1.4) = play(seconds * PACE)

    /** Где на экране лежит подпись: по ней бьёт палец, а не внедрённое нажатие. */
    private fun spotOf(text: String): Pair<Int, Int> {
        val node = if (shown(text)) compose.onAllNodesWithText(text).onFirst().fetchSemanticsNode()
        else compose.onAllNodesWithContentDescription(text).onFirst().fetchSemanticsNode()
        val at = node.positionOnScreen
        return (at.x + node.size.width / 2).toInt() to (at.y + node.size.height / 2).toInt()
    }

    /**
     * Нажать **пальцем**: палец опускается, держится и поднимается, а не бьёт мгновенно. Системный
     * кружок «показывать касания» в запись не попадает — его рисует не приложение, — зато волна
     * Material под пальцем рисуется самим экраном, и на записи видно, куда человек нажал.
     * Внедрённое нажатие Compose не даёт ни волны, ни перехода: экран просто вдруг становится
     * другим.
     */
    private fun press(text: String, after: Double = 0.7) {
        show(text)
        val (x, y) = spotOf(text)
        finger(x, y)
        play(after)
    }

    /**
     * Палец бьёт в точку системным событием. Волна нажатия Material живёт около трети секунды и
     * гаснет сама — на записи её видно, потому что кадры идут в настоящем времени; раздельные
     * `motionevent` и нулевой `swipe` до экрана не доходят вовсе.
     */
    private fun finger(x: Int, y: Int) {
        shell("input tap $x $y")
        play(HOLD / 1000.0)
    }

    /** Дождаться подписи, не останавливая часы: приход экрана тоже должен быть виден. */
    private fun show(text: String, timeout: Long = WAIT) {
        val until = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < until) {
            if (shown(text) || described(text)) return
            play(FRAME / 1000.0)
        }
        error("на экране так и не появилось «$text»")
    }

    /** Системная «назад» — тем же путём, каким её нажимает человек. */
    private fun goBack(after: Double = 0.7) {
        shell("input keyevent 4")
        play(after)
    }

    /** Панель мест видна только у корней мест: по ней и видно, что «назад» дошёл докуда надо. */
    private fun atPlaces() = shown("Аптечки") && shown("План") && shown("Сканер") && shown("Опции")

    /**
     * Место внизу: до него доходят «назад», как и у человека, а потом жмут вкладку.
     *
     * После каждого «назад» панель мест ждут отдельно: приходит она не в тот же кадр, и решение
     * «ещё не дома» по пустому дереву отправило бы следующий «назад» уже с корня места — а он
     * закрывает приложение, и запись обрывается на полуслове.
     */
    private fun goTo(place: String) {
        repeat(6) {
            if (atPlaces()) return@repeat
            goBack(0.35)
            val until = System.currentTimeMillis() + 1_000
            while (!atPlaces() && System.currentTimeMillis() < until) play(FRAME / 1000.0)
        }
        check(atPlaces()) { "панель мест так и не появилась: следующий «назад» закрыл бы приложение" }
        show(place)
        val node = compose.onAllNodesWithText(place).onLast().fetchSemanticsNode()
        val at = node.positionOnScreen
        finger((at.x + node.size.width / 2).toInt(), (at.y + node.size.height / 2).toInt())
        play(0.7)
    }

    /**
     * Перетащить строку источника за ручку: палец давит, держит дольше долгого нажатия и ведёт
     * строку шагами - каждое событие настоящее, поэтому на записи видно и захват, и ход.
     */
    private fun dragSource(name: String, other: String) {
        val handle = "Переставить: "
        val (x, from) = spotOf(handle + name)
        val (_, to) = spotOf(handle + other)
        // Палец давит, держит дольше долгого нажатия и ведёт строку шагами. Одним вызовом это не
        // делается: `input draganddrop` ведёт палец сразу, захват строки не успевает случиться, и
        // строка остаётся на месте — молча.
        shell("input motionevent DOWN $x $from")
        play(1.0)
        val step = (to - from) / 12
        for (i in 1..12) {
            shell("input motionevent MOVE $x ${from + step * i}")
            play(0.06)
        }
        play(0.4)
        shell("input motionevent UP $x $to")
        play(1.0)
        val (_, movedTo) = spotOf(handle + name)
        val (_, stayedAt) = spotOf(handle + other)
        check(movedTo < stayedAt) { "строка «$name» осталась на месте: перетаскивание не сыграло" }
    }

    /** Напечатать в поле: буквы важны сами по себе, и внедрённый ввод показывает их так же. */
    private fun fill(label: String, text: String) {
        show(label)
        compose.onAllNodesWithText(label).onFirst().performTextInput(text)
        play(0.6)
    }

    /** Нажать по части подписи: время пункта стоит в строке вместе с названием. */
    private fun pressPart(text: String) {
        val until = System.currentTimeMillis() + WAIT
        while (compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isEmpty()) {
            check(System.currentTimeMillis() < until) { "на экране так и не появилось «$text»" }
            play(FRAME / 1000.0)
        }
        val node = compose.onAllNodes(hasText(text, substring = true)).onFirst().fetchSemanticsNode()
        val at = node.positionOnScreen
        finger((at.x + node.size.width / 2).toInt(), (at.y + node.size.height / 2).toInt())
        play(0.7)
    }

    /** Нажать, если такая подпись на экране есть: сюжет не должен падать из-за одной кнопки. */
    private fun pressIfShown(text: String): Boolean {
        val here = shown(text) || described(text)
        if (here) press(text)
        return here
    }

    /**
     * Весь сюжет защиты одним прогоном. Сцена, которая не сыграла, — дыра в демонстрации, и
     * молчать о ней нельзя: без этого прогон зеленеет, а на записи не хватает того, о чём
     * рассказывают вслух. Поэтому каждая сцена ждёт своего экрана и падает с именем подписи,
     * которой не дождалась.
     */
    @Test
    fun demo() {
        // Сцена 1. Аптечки
        show("Без ответа за прошлые дни")
        beat(2.0)
        press("Закрыть")
        show("Домашняя")
        beat(3.0)

        press("Домашняя")
        show("Нурофен")
        beat(2.5)
        press("Просроченные")
        beat(2.5)
        press("Просроченные")
        beat(0.8)

        // Сцена 2. Упаковка
        press("Нурофен")
        show("Сколько есть")
        beat(3.5)
        goBack()

        // Сцена 3. Курс лечения
        goTo("План")
        press("Курсы")
        show("Нурофен от спины")
        beat(2.0)
        press("Нурофен от спины")
        beat(2.5)
        press("Источники лечения")
        beat(2.5)

        // Смена приоритета: подключаем вторую пачку и ставим её первой - у неё срок ближе.
        press("Подключить ещё коробку")
        show("Подключить пачку")
        beat(2.5)
        // Список длинный, и пачка добавлена последней: человек нашёл бы её так же - поиском.
        fill("Поиск по названию", "Экспресс")
        beat(2.0)
        press("Нурофен Экспресс")
        show("Берётся сверху вниз")
        beat(2.5)
        dragSource("Нурофен Экспресс", "Нурофен")
        beat(3.0)
        pressIfShown("Сохранить")
        beat(2.0)

        // Сцена 4. План дня и напоминание
        goTo("План")
        press("День")
        show("Нурофен от спины")
        beat(3.0)
        pressPart("14:00")
        show("Из какой коробки")
        beat(2.5)
        pressIfShown("Принял")
        beat(2.0)
        goBack()

        runBlocking {
            dailyRound.run()
            reminders.pass()
        }
        shell("cmd statusbar expand-notifications")
        Thread.sleep(6000)
        shell("cmd statusbar collapse")
        beat(1.0)

        // Сцена 5. Общая аптечка
        goTo("Аптечки")
        press("Дача")
        show("Но-шпа")
        beat(2.5)
        press("Но-шпа")
        show("Сколько есть")
        beat(3.0)
        goBack()
        press("Смекта")
        show("Сколько есть")
        beat(2.5)
        goBack()
        press("Аспирин")
        show("Сколько есть")
        beat(3.0)
        goBack()

        // Сцена 6. Приглашение в общую аптечку. Ключ и QR в кадр не попадают: их выдаёт сервер, а
        // обход идёт без сети - экран показывает, чем аптечка становится общей.
        press("Что сделать с аптечкой")
        beat(1.2)
        // У общей полки приглашение зовётся «Пригласить», у местной - «Поделиться аптечкой».
        if (!pressIfShown("Пригласить")) pressIfShown("Поделиться аптечкой")
        show("Поделиться")
        beat(3.0)

        // Сцена 7. Отчёты и сканер
        goTo("Отчёты")
        show("По категориям")
        beat(2.5)
        press("Расход")
        beat(2.5)
        press("Истрачено")
        beat(2.5)

        goTo("Сканер")
        Thread.sleep(4000)
        // Код читает настоящий распознаватель с настоящей фотографии коробки, а дальше идёт
        // обычный путь приложения: форма уже знает то, что сказал реестр.
        val code = codeFromPhoto()
        compose.runOnUiThread { stacks.go(Screen.PackageForm(scannedCode = code)) }
        show("Новая упаковка")
        beat(4.0)
    }

    private companion object {

        /** Во сколько раз пауза длиннее обычной: на записи она и есть темп показа. */
        const val PACE = 1.35

        /** Кадр в миллисекундах: столько же и спим, чтобы часы шли как у человека. */
        const val FRAME = 16L

        /** Сколько палец держится на месте: столько живёт волна нажатия, и её видно на записи. */
        const val HOLD = 260
    }
}
