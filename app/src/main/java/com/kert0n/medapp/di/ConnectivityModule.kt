package com.kert0n.medapp.di

import com.kert0n.medapp.feature.connectivity.Connection
import com.kert0n.medapp.platform.connectivity.SystemConnection
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Связь знает система: сценарии и заходы видят её портом (PLAN E4, H1). */
@Module
@InstallIn(SingletonComponent::class)
abstract class ConnectivityModule {

    @Binds
    @Singleton
    abstract fun connection(implementation: SystemConnection): Connection
}
