package com.kert0n.medapp.di

import com.kert0n.medapp.domain.account.DeviceAccount
import com.kert0n.medapp.feature.account.AccountRegistration
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Знакомство устройства с сервером ведёт сценарий регистрации. Отдельно от хранилища учётки:
 * проверки подменяют хранилище, а сценарий остаётся настоящим.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AccountModule {

    @Binds
    abstract fun deviceAccount(implementation: AccountRegistration): DeviceAccount
}
