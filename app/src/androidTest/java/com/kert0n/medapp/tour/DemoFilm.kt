package com.kert0n.medapp.tour

import android.Manifest
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.onNodeWithContentDescription
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
     * Перетащить строку источника за ручку: шаг берётся из расстояния между самими ручками, а
     * не из догадки о высоте карточки.
     */
    private fun dragSource(name: String, other: String, up: Boolean) {
        val handle = "Переставить: "
        val from = compose.onNodeWithContentDescription(handle + name).fetchSemanticsNode()
        val to = compose.onNodeWithContentDescription(handle + other).fetchSemanticsNode()
        val step = to.positionInRoot.y - from.positionInRoot.y
        compose.onNodeWithContentDescription(handle + name).performTouchInput {
            down(center)
            advanceEventTime(800)                 // дольше долгого нажатия: захват строки
            repeat(12) {
                moveBy(Offset(0f, step / 12f))
                advanceEventTime(45)
            }
            advanceEventTime(250)
            up()
        }
        compose.waitForIdle()
    }

    /**
     * Пауза между действиями: на записи она и есть темп показа. [PACE] подогнан так, чтобы весь
     * сюжет занимал около двух минут - столько отведено демонстрации на защите.
     */
    private fun beat(seconds: Double = 1.4) {
        compose.waitForIdle()
        Thread.sleep((seconds * PACE * 1000).toLong())
    }

    /** Нажать, если такая подпись на экране есть: сюжет не должен падать из-за одной кнопки. */
    private fun tapIfShown(text: String): Boolean {
        val here = shown(text) || described(text)
        if (here) tap(text)
        return here
    }

    @Test
    fun demo() {
        // Сцена 1. Аптечки
        see("Без ответа за прошлые дни")
        beat(2.0)
        tap("Закрыть")
        see("Домашняя")
        beat(3.0)

        tap("Домашняя")
        see("Нурофен")
        beat(2.5)
        tap("Просроченные")
        beat(2.5)
        tap("Просроченные")
        beat(0.8)

        // Сцена 2. Упаковка
        tap("Нурофен")
        see("Сколько есть")
        beat(3.5)
        back()

        // Сцена 3. Курс лечения
        place("План")
        tap("Курсы")
        see("Нурофен от спины")
        beat(2.0)
        tap("Нурофен от спины")
        beat(2.5)
        tap("Источники лечения")
        beat(2.5)

        // Смена приоритета: подключаем вторую пачку и ставим её первой - у неё срок ближе.
        tap("Подключить ещё коробку")
        see("Подключить пачку")
        beat(2.5)
        // Список длинный, и пачка добавлена последней: человек нашёл бы её так же - поиском.
        type("Поиск по названию", "Экспресс")
        beat(2.0)
        tap("Нурофен Экспресс")
        see("Берётся сверху вниз")
        beat(2.5)
        runCatching { dragSource("Нурофен Экспресс", "Нурофен", up = true) }
        beat(3.0)
        tapIfShown("Сохранить")
        beat(2.0)

        // Сцена 4. План дня и напоминание
        place("План")
        tap("День")
        see("Нурофен от спины")
        beat(3.0)
        tapPart("14:00")
        see("Из какой коробки")
        beat(2.5)
        tapIfShown("Принял")
        beat(2.0)
        back()

        runBlocking {
            dailyRound.run()
            reminders.pass()
        }
        shell("cmd statusbar expand-notifications")
        Thread.sleep(6000)
        shell("cmd statusbar collapse")
        beat(1.0)

        // Сцена 5. Общая аптечка
        place("Аптечки")
        tap("Дача")
        see("Но-шпа")
        beat(2.5)
        tap("Но-шпа")
        see("Сколько есть")
        beat(3.0)
        back()
        tap("Смекта")
        see("Сколько есть")
        beat(2.5)
        back()
        tap("Аспирин")
        see("Сколько есть")
        beat(3.0)
        back()

        // Сцена 6. Приглашение в общую аптечку. Ключ и QR в кадр не попадают: их выдаёт сервер, а
        // обход идёт без сети - экран показывает, чем аптечка становится общей.
        tap("Что сделать с аптечкой")
        beat(1.2)
        // У общей полки приглашение зовётся «Пригласить», у местной - «Поделиться аптечкой».
        if (!tapIfShown("Пригласить")) tapIfShown("Поделиться аптечкой")
        see("Поделиться")
        beat(3.0)

        // Сцена 7. Отчёты и сканер
        place("Отчёты")
        see("По категориям")
        beat(2.5)
        tap("Расход")
        beat(2.5)
        tap("Истрачено")
        beat(2.5)

        place("Сканер")
        Thread.sleep(4000)
        // Код читает настоящий распознаватель с настоящей фотографии коробки, а дальше идёт
        // обычный путь приложения: форма уже знает то, что сказал реестр.
        val code = runCatching { codeFromPhoto() }.getOrNull()
        if (code == null) return
        compose.runOnUiThread { stacks.go(Screen.PackageForm(scannedCode = code)) }
        see("Новая упаковка")
        beat(4.0)
    }

    private companion object {

        /** Во сколько раз пауза длиннее обычной: на записи она и есть темп показа. */
        const val PACE = 1.7
    }
}
