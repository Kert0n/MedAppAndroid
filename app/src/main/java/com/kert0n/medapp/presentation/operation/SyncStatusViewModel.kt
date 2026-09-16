package com.kert0n.medapp.presentation.operation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kert0n.medapp.feature.operation.OperationDismissing
import com.kert0n.medapp.feature.operation.Refreshing
import com.kert0n.medapp.storage.server.SyncOperationStorageRepository
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
    operations: SyncOperationStorageRepository
) : ViewModel() {

    private val working = MutableStateFlow(false)

    val state: StateFlow<SyncStatusUiState> =
        combine(operations.observeTroubles(), refreshing.state, working) { troubles, sync, working ->
            SyncStatusUiState(
                rows = troubles.map { it.toPresentationDTO() },
                isRunning = sync.isRunning || working,
                refreshedAt = sync.refreshedAt,
                isOffline = sync.isOffline,
                isLoaded = true
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncStatusUiState())

    /**
     * Обновить — это **заход целиком**, а не отправка одной строки: очередь едет вся, и просить
     * её по одной человеку незачем (PLAN E4). Второе нажатие, пока идёт первое, ничего не делает.
     */
    fun refresh() {
        if (working.value) return
        working.value = true
        viewModelScope.launch {
            try {
                refreshing.now()
            } finally {
                working.value = false
            }
        }
    }

    /** Разобрал: строка уходит с экрана, но не из базы. */
    fun dismiss(operationId: Uuid) {
        viewModelScope.launch { dismissing.dismiss(operationId) }
    }
}

/** Что показывает экран состояния синхронизации. */
data class SyncStatusUiState(
    val rows: List<OutstandingOperationPresentationDTO> = emptyList(),
    val isRunning: Boolean = false,
    val refreshedAt: Instant? = null,
    /** Связи нет — это не ошибка: очередь цела, ей просто некуда ехать (PLAN H3 №28). */
    val isOffline: Boolean = false,
    val isLoaded: Boolean = false
) {
    val isEmpty: Boolean get() = rows.isEmpty()
}
