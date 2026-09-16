package com.kert0n.medapp.presentation.scan

import androidx.lifecycle.ViewModel
import com.kert0n.medapp.domain.scan.CodeFormat
import com.kert0n.medapp.domain.scan.ScannedCode
import com.kert0n.medapp.platform.settings.CameraAccess
import com.kert0n.medapp.platform.settings.DevicePermissions
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Сканер (PLAN H3 №24). Экран узнаёт код и **ведёт дальше**, а сам ничего не спрашивает у реестра:
 * DataMatrix уезжает кодом в форму новой коробки, и спрашивает «Честный знак» уже она — тот, кто
 * показывает ответ (PLAN C1 «Результат сканирования — заполненная форма»). Приглашение сканер
 * называет и предлагает перейти на экран вступления; всё остальное — «код не поддерживается», и
 * запроса нет ни у кого.
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
        _state.value = _state.value.copy(camera = camera())
    }

    /** Система ответила на просьбу о камере: спрошено — значит, второго диалога уже не будет. */
    fun asked() {
        isAsked = true
        _state.value = _state.value.copy(camera = camera())
    }

    /** Камера увидела код. */
    fun seen(code: ScannedCode) {
        val now = _state.value
        if (now.opening != null || code.text.isEmpty() || code.text == last) return
        last = code.text
        _state.value = when (code.format) {
            CodeFormat.DATA_MATRIX -> now.copy(opening = code.text, notice = null)
            CodeFormat.QR -> now.copy(notice = ScannerNotice.INVITATION)
            CodeFormat.OTHER -> now.copy(notice = ScannerNotice.UNSUPPORTED)
        }
    }

    /** Форма открыта: тот же код её второй раз не откроет — ни новым кадром, ни поворотом экрана. */
    fun opened() {
        _state.value = _state.value.copy(opening = null, notice = null)
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
    val notice: ScannerNotice? = null,
    val opening: String? = null
)

/**
 * Что с камерой — глазами экрана. Четыре случая, потому что человек делает в них разное: смотрит
 * в видоискатель, отвечает на просьбу, идёт в системные настройки, заводит коробку руками.
 *
 * [UNASKED] и [REFUSED] различаются тем, будет ли диалог: второй отказ Android запоминает и больше
 * не спрашивает, и кнопка «Разрешить» в этом случае ничего бы не открыла.
 */
enum class ScannerCamera { READY, UNASKED, REFUSED, ABSENT }

/**
 * Что сказать о прочитанном коде. Два случая, и человек делает в них разное: у приглашения есть
 * куда идти, у чужого кода — нечего делать, кроме как навести на другой.
 */
enum class ScannerNotice { UNSUPPORTED, INVITATION }
