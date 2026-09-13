package com.kert0n.medapp.queue

import com.kert0n.medapp.network.pack.PackageSnapshot

/**
 * Что известно о пачке на сервере после закрытия операции. Снимок — уже разрешённый
 * ([PackageSnapshotResolver]): хранению остаётся положить его. «Пачки нет» выражено типом, а не
 * пустым снимком, и допустимо только там, где команда его ждала ([Expected.SNAPSHOT_OR_GONE],
 * [Expected.NOTHING] у удаления); у команды аптечки состояния пачки нет вовсе.
 */
sealed interface PackageState {

    data class Present(val snapshot: PackageSnapshot) : PackageState

    /** Пачки на сервере больше нет: коробки у нас нет, и строки после неё не остаётся (D3). */
    data object Gone : PackageState

    /**
     * Пачка на сервере есть, но на полке, которой у нас нет: сосед переставил её туда, где нас нет.
     * Для нас это утрата доступа — окончательная, а не ожидание (PLAN E3, E6).
     */
    data object Elsewhere : PackageState

    /** Команда пачки не касается. */
    data object None : PackageState
}
