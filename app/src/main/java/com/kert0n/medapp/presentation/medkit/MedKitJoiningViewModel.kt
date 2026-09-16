package com.kert0n.medapp.presentation.medkit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kert0n.medapp.domain.Unavailability
import com.kert0n.medapp.domain.medkit.InvitationKey
import com.kert0n.medapp.feature.medkits.MedKitJoining
import com.kert0n.medapp.presentation.ParsedInput
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Присоединиться к чужой аптечке по коду (PLAN H3 №22). Действие требует связи: без сервера полки
 * не существует, и в очередь его не поставить (PLAN C3).
 *
 * Своей полки экран не показывает и списка не ведёт: он про **один** код и один ответ на него.
 */
@HiltViewModel
class MedKitJoiningViewModel @Inject constructor(
    private val joining: MedKitJoining
) : ViewModel() {

    private val _state = MutableStateFlow(MedKitJoiningUiState())

    val state: StateFlow<MedKitJoiningUiState> = _state.asStateFlow()

    /** Ввод снимает отказ: человек уже правит то, на что ему указали. */
    fun type(code: String) {
        _state.value = _state.value.copy(code = code, refusal = null)
    }

    /**
     * Войти. Второе нажатие, пока идёт первое, не делает ничего: человек ждёт от него того же
     * ответа, а не второго вступления.
     */
    fun join() {
        val now = _state.value
        if (now.isWorking || now.joined != null) return
        when (val parsed = now.parsed()) {
            is ParsedInput.Rejected -> _state.value = now.copy(refusal = parsed.error)
            is ParsedInput.Parsed -> {
                _state.value = now.copy(isWorking = true, refusal = null)
                viewModelScope.launch { enter(parsed.value) }
            }
        }
    }

    private suspend fun enter(key: InvitationKey) {
        val now = _state.value
        _state.value = when (val outcome = joining.join(key)) {
            is MedKitJoining.Outcome.Joined -> now.copy(isWorking = false, joined = outcome.medKitId)
            // Уже в полке — не беда и не отказ по делу: полка у человека есть, и сказать об этом
            // честнее, чем молча ничего не сделать (PLAN E4).
            MedKitJoining.Outcome.AlreadyMember -> now.copy(isWorking = false, refusal = MedKitJoiningRefusal.AlreadyMember)
            MedKitJoining.Outcome.InvitationInvalid -> now.copy(isWorking = false, refusal = MedKitJoiningRefusal.Invalid)
            is MedKitJoining.Outcome.Unavailable ->
                now.copy(isWorking = false, refusal = MedKitJoiningRefusal.Unavailable(outcome.reason))
        }
    }
}

/**
 * Что показывает экран: набранный код, идёт ли запрос, чем кончилось. [joined] — полка, в которую
 * вошли: экран после этого уходит в неё.
 */
data class MedKitJoiningUiState(
    val code: String = "",
    val isWorking: Boolean = false,
    val refusal: MedKitJoiningRefusal? = null,
    val joined: Uuid? = null
) {

    /**
     * Пробелы вокруг кода — не часть его: код приходит перепиской, и человек вставляет его вместе
     * с ними. Пустой код домену не показывается — [InvitationKey] его и не примет.
     */
    fun parsed(): ParsedInput<InvitationKey, MedKitJoiningRefusal> {
        val trimmed = code.trim()
        return if (trimmed.isEmpty()) ParsedInput.Rejected(MedKitJoiningRefusal.Empty)
        else ParsedInput.Parsed(InvitationKey(trimmed))
    }
}

/** Почему не вошли. Человек в каждом случае делает разное: вводит, просит новый код, повторяет. */
sealed interface MedKitJoiningRefusal {

    /** Кода нет вовсе: нажимать было не с чем. */
    data object Empty : MedKitJoiningRefusal

    /**
     * Приглашение не годится — **одна фраза на все случаи**: неизвестный ключ, истёкший и выход
     * пригласившего сервер не различает (PLAN B6), и гадать за него экран не станет.
     */
    data object Invalid : MedKitJoiningRefusal

    /** Уже в этой аптечке: она у человека есть, и он найдёт её в списке. */
    data object AlreadyMember : MedKitJoiningRefusal

    /** Сервера нет: причина и повтор. */
    data class Unavailable(val reason: Unavailability) : MedKitJoiningRefusal
}
