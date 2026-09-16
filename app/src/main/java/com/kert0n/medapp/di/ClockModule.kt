package com.kert0n.medapp.di

import com.kert0n.medapp.platform.time.DeviceClock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton

/**
 * Часы — своим модулем, а не соседом сети: история человека двигает время приложения (полночь,
 * другой пояс), и подменить часы, не трогая клиентов сервера, можно только так (PLAN U5, «Историям
 * — часы, шторка и живые механизмы»).
 */
@Module
@InstallIn(SingletonComponent::class)
object ClockModule {

    /**
     * Часы системные, в **нынешней** зоне устройства: по ней сценарии узнают день момента,
     * названного человеком, — например, просрочена ли коробка на день приёма (PLAN C1). Зона
     * спрашивается при каждом обращении — переезд её меняет, а процесс живёт. Тесты подставляют свои.
     */
    @Provides
    @Singleton
    fun clock(): Clock = DeviceClock
}
