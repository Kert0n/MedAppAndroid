package com.kert0n.medapp.presentation.scan

import com.kert0n.medapp.domain.scan.CodeFormat
import com.kert0n.medapp.domain.scan.ScannedCode
import com.kert0n.medapp.platform.settings.CameraAccess
import com.kert0n.medapp.platform.settings.DevicePermissions
import com.kert0n.medapp.platform.settings.PermissionStates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Сканер (PLAN H3 №24): что экран делает с прочитанным кодом и что показывает, когда камеры нет.
 * Как это нарисовано, проверяет `ScannerScreenTest`.
 */
class ScannerViewModelTest {

    /** Состояние системы задаётся снаружи: настоящее у проверки отобрать нечем. */
    private class Camera(var access: CameraAccess) : DevicePermissions {
        override fun current() = PermissionStates(notifications = true, exactAlarms = true, camera = access)
    }

    private fun scanner(access: CameraAccess = CameraAccess.GRANTED) = ScannerViewModel(Camera(access))

    private val dataMatrix = ScannedCode(CodeFormat.DATA_MATRIX, "010467001234567721abc\u001D91EE06")

    /**
     * EAN-13 описывает товарную позицию, а не эту коробку (PLAN C1 «Сканирование кодов»), и
     * спрашивать о нём реестр нечего. Сними правило — и человек получил бы сведения о чужой партии
     * либо молчание, неотличимое от неработающей камеры.
     */
    @Test
    fun anAlienFormatIsNamedAndNothingIsAsked() {
        val model = scanner()

        model.seen(ScannedCode(CodeFormat.OTHER, "4607004891014"))

        assertEquals(ScannerNotice.UNSUPPORTED, model.state.value.notice)
        assertNull("чужой код формы не открывает", model.state.value.opening)
    }

    /**
     * Приглашение сканер называет, но не принимает: вступление живёт на своём экране со всеми
     * своими исходами (PLAN C1 «Приглашение в сканере не вступает, а ведёт»).
     */
    @Test
    fun anInvitationIsNamedAndLeadsToJoining() {
        val model = scanner()

        model.seen(ScannedCode(CodeFormat.QR, "K7F-2M9-QX4"))

        assertEquals(ScannerNotice.INVITATION, model.state.value.notice)
        assertNull("приглашение коробки не заводит", model.state.value.opening)
    }

    /** Код с коробки ведёт в форму — тем самым текстом, каким его дал распознаватель. */
    @Test
    fun aDataMatrixOpensTheFormWithItsCode() {
        val model = scanner()

        model.seen(dataMatrix)

        assertEquals(dataMatrix.text, model.state.value.opening)
    }

    /**
     * Камера отдаёт один и тот же код десятками кадров в секунду. Сними дедупликацию — и на каждый
     * кадр открывалась бы своя форма: человек навёл телефон один раз, а возвращаться ему пришлось
     * бы тридцать.
     */
    @Test
    fun theSameCodeInAStreamOpensTheFormOnce() {
        val model = scanner()

        repeat(30) { model.seen(dataMatrix) }
        assertEquals(dataMatrix.text, model.state.value.opening)
        model.opened()
        repeat(30) { model.seen(dataMatrix) }

        assertNull("тот же код второй формы не открывает", model.state.value.opening)
    }

    /**
     * Человек вернулся из формы и наводит телефон на ту же коробку — значит, хочет ещё раз.
     * Помнить прочитанное между заходами значило бы молчать в ответ на настоящее действие.
     */
    @Test
    fun comingBackForgetsWhatWasRead() {
        val model = scanner()
        model.seen(dataMatrix)
        model.opened()

        model.resumed()
        model.seen(dataMatrix)

        assertEquals(dataMatrix.text, model.state.value.opening)
    }

    /** До первой просьбы отказа ещё нет — есть незаданный вопрос, и спросить его можно. */
    @Test
    fun aRefusalIsToldApartFromAnUnaskedQuestion() {
        val camera = Camera(CameraAccess.DENIED)
        val model = ScannerViewModel(camera)
        assertEquals(ScannerCamera.UNASKED, model.state.value.camera)

        model.asked()

        assertEquals(ScannerCamera.REFUSED, model.state.value.camera)
    }

    /** Разрешение меняют у системы: дали в её настройках — вернувшийся человек видит видоискатель. */
    @Test
    fun permissionGivenInSystemSettingsIsSeenOnReturn() {
        val camera = Camera(CameraAccess.DENIED)
        val model = ScannerViewModel(camera)
        model.asked()

        camera.access = CameraAccess.GRANTED
        model.resumed()

        assertEquals(ScannerCamera.READY, model.state.value.camera)
    }

    /** Без камеры сканера нет и просить нечего: остаётся ручной ввод (T-45). */
    @Test
    fun aDeviceWithoutACameraIsNotAsked() {
        val model = scanner(CameraAccess.ABSENT)

        assertEquals(ScannerCamera.ABSENT, model.state.value.camera)
    }
}
