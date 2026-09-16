package com.kert0n.medapp.presentation.notification

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kert0n.medapp.domain.notification.NoticeDelivery
import com.kert0n.medapp.domain.notification.NotificationKey
import com.kert0n.medapp.domain.notification.NotificationKind
import com.kert0n.medapp.domain.notification.NotificationTarget
import com.kert0n.medapp.feature.notification.ReminderOutbox
import com.kert0n.medapp.presentation.pack.PackagePresentationDTO
import com.kert0n.medapp.presentation.pack.toPresentationDTO
import com.kert0n.medapp.storage.notification.ReminderStorageRepository
import com.kert0n.medapp.storage.pack.PackageStorageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * «Сегодня истекает срок годности» — попап при входе (PLAN D8, H3 «Уведомления на экране»).
 *
 * Это новость о **вещи**, а не о приёме: строкой в списке она теряется, и человек узнаёт о ней,
 * когда коробка уже просрочена. Оттого попап, и оттого он закрывается **только крестиком**: уход на
 * карточку коробки — продолжение того же разговора, и возврат его не обрывает (решение владельца
 * 2026-09-16).
 *
 * Показанные обязательства отмечаются при закрытии, а не при показе: отметь их раньше — чтение
 * опустело бы под руками, и попап закрылся бы сам, ничего не сказав.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ExpiringTodayViewModel @Inject constructor(
    private val outbox: ReminderOutbox,
    private val reminders: ReminderStorageRepository,
    private val packages: PackageStorageRepository
) : ViewModel() {

    /**
     * Что человек закрыл крестиком — **ключами** обязательств, а не флагом «попап закрыт». Закрытое
     * уходит с экрана сразу, не дожидаясь записи отметки, а новость с новым ключом приходит сама:
     * флаг держал бы попап закрытым и назавтра, пока окно живёт (PLAN C1 «Попап вне мест»).
     */
    private val dismissed = MutableStateFlow<Set<NotificationKey>>(emptySet())

    /** Баннеры приложения — только свои: пропуски приходят своим попапом и этим крестиком не закрываются. */
    private val awaiting = reminders.observeAwaiting(NoticeDelivery.IN_APP_BANNER)
        .map { notices -> notices.filter { it.key.kind == NotificationKind.EXPIRY_TODAY } }

    val state: StateFlow<ExpiringTodayUiState> = combine(awaiting, dismissed) { notices, dismissed ->
        notices.filter { it.key !in dismissed }
    }.flatMapLatest { notices ->
        // Коробки читаются живыми: выброшенная уходит из попапа сама, пока человек на него смотрит.
        val ids = notices.mapNotNull { (it.target as? NotificationTarget.PackageCard)?.packageId }
        // Ключи едут тем же снимком, что и коробки, и **не** сужаются вместе со списком: коробка
        // может уйти из попапа, а обязательство о ней остаётся сказанным (C1 «Действие — по показанному»).
        val keys = notices.mapTo(HashSet()) { it.key }
        if (ids.isEmpty()) flowOf(ExpiringTodayUiState())
        else combine(ids.map { packages.observe(it) }) { boxes ->
            ExpiringTodayUiState(boxes = boxes.filterNotNull().map { it.toPresentationDTO() }, told = keys)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExpiringTodayUiState())

    /**
     * Закрыт крестиком: обязательства помечаются сказанными, и сегодня попап больше не придёт.
     * Владелец доставки один и для системы, и для экрана (PLAN D8).
     */
    fun dismiss() {
        val keys = state.value.told
        dismissed.update { it + keys }
        viewModelScope.launch { outbox.bannerShown(keys) }
    }

}

/**
 * Что показывает попап: коробки, у которых срок кончается сегодня. Пустой попап не показывается —
 * говорить не о чем.
 */
data class ExpiringTodayUiState(
    val boxes: List<PackagePresentationDTO> = emptyList(),
    /** Обязательства, о которых попап сейчас говорит: их отмечает крестик. Не рисуется. */
    val told: Set<NotificationKey> = emptySet()
) {
    val isEmpty: Boolean get() = boxes.isEmpty()
}
