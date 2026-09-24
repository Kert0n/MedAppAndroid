package com.kert0n.medapp.presentation.scan

import androidx.lifecycle.ViewModel
import com.kert0n.medapp.domain.scan.CodeFormat
import com.kert0n.medapp.domain.scan.ScannedCode
import com.kert0n.medapp.feature.settings.CameraAccess
import com.kert0n.medapp.feature.settings.DevicePermissions
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Сканер (PLAN H3 №24). Экран узнаёт код и **ведёт дальше молча**, а сам ничего не спрашивает и
 * ничего не показывает от себя: DataMatrix уезжает кодом в форму новой коробки, приглашение —
 * ключом на экран вступления, и оба открываются уже заполненными (решение владельца 2026-09-17).
 * Спрашивает реестр маркировки та же форма, что показывает ответ (PLAN C1). Всё остальное — «код не
 * поддерживается», и запроса нет ни у кого.
 *
 * **Один код — один переход.** Камера отдаёт тот же код десятками кадров в секунду, и без этого
 * правила форма открывалась бы стопкой. Прочитанное забывается при возвращении на место: человек
 * вернулся из формы и наводит телефон на ту же коробку — значит, хочет ещё раз.
 */
@HiltViewModel
class ScannerViewModel @Inject constructor(
    private val permissions: DevicePermissions
) : ViewModel() {

    private var isAsked = false

    private val _state = MutableStateFlow(ScannerUiState(camera = camera()))

    val state: StateFlow<ScannerUiState> = _state.asStateFlow()

    private var last: String? = null

    /**
     * Человек вернулся на место. Разрешение спрашивается у системы заново — его меняют в системных
     * настройках, и своего мнения о нём приложение не держит (PLAN H3 «Уведомления на экране»), —
     * а прочитанный код забывается.
     */
    fun resumed() {
        last = null
        // Сказанное о прежнем коде забывается вместе с ним: вернувшись от вступления, человек
        // видел бы весть о коде, которого сканер уже не помнит (разбор #55).
        _state.value = _state.value.copy(
            camera = camera(),
            isUnsupported = false,
            opening = null,
            invitation = null
        )
    }

    /** Система ответила на просьбу о камере: спрошено — значит, второго диалога уже не будет. */
    fun asked() {
        isAsked = true
        _state.value = _state.value.copy(camera = camera())
    }

    /** Камера увидела код. */
    fun seen(code: ScannedCode) {
        val now = _state.value
        if (now.opening != null || now.invitation != null || code.text.isEmpty() || code.text == last) return
        last = code.text
        _state.value = when (code.format) {
            CodeFormat.DATA_MATRIX -> now.copy(opening = code.text, isUnsupported = false)
            CodeFormat.QR -> now.copy(invitation = code.text, isUnsupported = false)
            CodeFormat.OTHER -> now.copy(isUnsupported = true)
        }
    }

    /** Форма открыта: тот же код её второй раз не откроет — ни новым кадром, ни поворотом экрана. */
    fun opened() {
        _state.value = _state.value.copy(opening = null, isUnsupported = false)
    }

    /** Вступление открыто с этим ключом: держать его дольше сканеру незачем (PLAN G3). */
    fun invitationOpened() {
        _state.value = _state.value.copy(invitation = null, isUnsupported = false)
    }

    private fun camera(): ScannerCamera = when (permissions.current().camera) {
        CameraAccess.GRANTED -> ScannerCamera.READY
        CameraAccess.DENIED -> if (isAsked) ScannerCamera.REFUSED else ScannerCamera.UNASKED
        CameraAccess.ABSENT -> ScannerCamera.ABSENT
    }
}

/**
 * Что видно на сканере. [opening] — код коробки, с которым открывается форма: не предложение и не
 * объект, а сам код с упаковки, и потому он законно едет ключом маршрута (PLAN G3).
 */
data class ScannerUiState(
    val camera: ScannerCamera,
    /** Перед камерой чужой код: сказать о нём — единственное, что сканер делает от себя. */
    val isUnsupported: Boolean = false,
    val opening: String? = null,
    /**
     * Ключ приглашения, с которым открывается вступление. В состоянии, а не в маршруте: ключ —
     * секрет, а маршрут ложится в сохранённую стопку (PLAN G3). Живёт он до первого открытия и
     * умирает вместе с процессом — как и ключ на экране 21.
     */
    val invitation: String? = null
)

/**
 * Что с камерой — глазами экрана. Четыре случая, потому что человек делает в них разное: смотрит
 * в видоискатель, отвечает на просьбу, идёт в системные настройки, заводит коробку руками.
 *
 * [UNASKED] и [REFUSED] различаются тем, будет ли диалог: второй отказ Android запоминает и больше
 * не спрашивает, и кнопка «Разрешить» в этом случае ничего бы не открыла.
 */
enum class ScannerCamera { READY, UNASKED, REFUSED, ABSENT }

