package com.kert0n.medapp.domain

import kotlin.coroutines.cancellation.CancellationException

/**
 * Попытка, которая **не глотает отмену**: обёртка из стандартной библиотеки ловит и
 * `CancellationException`, и отменённый вызов выглядел бы как сбой — «сервер промолчал» вместо
 * «нас остановили» (разбор #30). Отмена летит дальше, всё остальное — исход. В `main` стандартная
 * обёртка больше не пишется — это держит `CancellationSafetyTest`.
 */
inline fun <T> attempt(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    Result.failure(failure)
}
