package com.kert0n.medapp.presentation.operation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kert0n.medapp.feature.operation.OperationDismissing
import com.kert0n.medapp.feature.operation.OperationReadings
import com.kert0n.medapp.feature.operation.Refreshing
import com.kert0n.medapp.presentation.ScreenFailures
import com.kert0n.medapp.presentation.ScreenReading
import com.kert0n.medapp.presentation.act
import com.kert0n.medapp.presentation.stateInScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import javax.inject.Inject
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Состояние синхронизации (PLAN H3 №28). Человек приходит сюда с одним вопросом — доехало ли, — и
 * ответ на него стоит выше списка: идёт ли заход и когда последний раз лёг снимок.
 *
 * Разобранное уходит из чтения само: строка остаётся в базе, потому что приём держится за учёт
 * своего расхода, — но человеку она больше не нужна (PLAN C1 «Отказ разобран человеком»).
 */
@HiltViewModel
class SyncStatusViewModel @Inject constructor(
    private val refreshing: Refreshing,
    private val dismissing: OperationDismissing,
    operations: OperationReadings,
    private val failures: ScreenFailures = ScreenFailures()
) : ViewModel() {

    /** Что экран читает из базы; не прочиталось — говорит об этом и предлагает повторить. */
    val reading = ScreenReading()

    private val working = MutableStateFlow(false)

    private val message = MutableStateFlow<DismissMessage?>(null)

    val state: StateFlow<SyncStatusUiState> =
        combine(operations.observeTroubles(), refreshing.state, working, message) { troubles, sync, working, message ->
            SyncStatusUiState(
                rows = troubles.map { it.toPresentationDTO() },
                isRunning = sync.isRunning || working,
                isWorking = working,
                message = message,
                refreshedAt = sync.refreshedAt,
                isOffline = sync.isOffline,
                isLoaded = true
            )
        }.stateInScreen(viewModelScope, reading, SyncStatusUiState())

    /**
     * Обновить — это **заход целиком**, а не отправка одной строки: очередь едет вся, и просить
     * её по одной человеку незачем (PLAN E4). Второе нажатие, пока идёт первое, ничего не делает.
     */
    fun refresh() {
        if (working.value) return
        working.value = true
        act(failures) {
            try {
                refreshing.now()
            } finally {
                working.value = false
            }
        }
    }

    /**
     * Разобрал: строка уходит с экрана, но не из базы. Второе нажатие, пока идёт первое, ничего
     * не делает — признак работы ставится до обращения к сценарию.
     */
    fun dismiss(operationId: Uuid) {
        if (working.value) return
        working.value = true
        act(failures) {
            try {
                // Каждый исход сказан, а не проглочен: «разобрано» видно тем, что строка ушла, а
                // два других человеку объясняются — иначе нажатие выглядит бездействием.
                message.value = when (dismissing.dismiss(operationId)) {
                    OperationDismissing.Outcome.DISMISSED -> null
                    OperationDismissing.Outcome.GONE -> DismissMessage.GONE
                    OperationDismissing.Outcome.NOT_AWAITING_DECISION -> DismissMessage.NOT_AWAITING_DECISION
                }
            } finally {
                working.value = false
            }
        }
    }

    /** Сказанное о разборе прочитано человеком. */
    fun dismissMessage() {
        message.value = null
    }
}

/** Чем кончился разбор, если строка ушла не по нашей воле (PLAN H3 №28). */
enum class DismissMessage { GONE, NOT_AWAITING_DECISION }

/** Что показывает экран состояния синхронизации. */
data class SyncStatusUiState(
    val rows: List<OutstandingOperationPresentationDTO> = emptyList(),
    val isRunning: Boolean = false,
    /** Идёт наше действие — обновление или разбор: второго поверх него не начинают. */
    val isWorking: Boolean = false,
    /** Чем кончился разбор, если сказать есть что. */
    val message: DismissMessage? = null,
    val refreshedAt: Instant? = null,
    /** Связи нет — это не ошибка: очередь цела, ей просто некуда ехать (PLAN H3 №28). */
    val isOffline: Boolean = false,
    val isLoaded: Boolean = false
) {
    val isEmpty: Boolean get() = rows.isEmpty()
}
