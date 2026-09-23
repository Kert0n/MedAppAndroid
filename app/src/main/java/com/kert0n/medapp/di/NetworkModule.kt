package com.kert0n.medapp.di

import android.util.Log
import com.kert0n.medapp.BuildConfig
import com.kert0n.medapp.domain.account.DeviceAccount
import com.kert0n.medapp.domain.medkit.MedKitInvitations
import com.kert0n.medapp.domain.value.VocabularyLibrary
import com.kert0n.medapp.network.account.AccessTokens
import com.kert0n.medapp.network.account.AccountReclaim
import com.kert0n.medapp.network.account.AccountRegistration
import com.kert0n.medapp.network.account.ServerDeviceAccount
import com.kert0n.medapp.network.delivery.CourierDoor
import com.kert0n.medapp.network.delivery.MedAppCourier
import com.kert0n.medapp.network.delivery.MedAppDoor
import com.kert0n.medapp.network.medkit.ServerMedKitInvitations
import com.kert0n.medapp.network.server.MedAppApi
import com.kert0n.medapp.network.server.crptHttpClient
import com.kert0n.medapp.network.server.medAppHttpClient
import com.kert0n.medapp.network.value.ServerVocabularyLibrary
import com.kert0n.medapp.queue.Courier
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.logging.Logger
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MedAppHttp

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CrptHttp

/** Регистрационный токен сборки (PLAN G1): приходит из `local.properties` или окружения CI. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class RegistrationToken

/**
 * Сколько, по нашей оценке, действует ключ приглашения. Сервер срок не отдаёт (PLAN B6), а
 * задаёт его своей настройкой `medkit.share.termInMinutes`; разошлись — правится параметр сборки.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class InvitationTerm

/**
 * Два клиента, а не один с настройками по месту вызова: строгость разбора и авторизация — свойство
 * сервера, к которому идёт запрос, и спутать их одной настройкой нельзя (PLAN H2, G3).
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    @MedAppHttp
    fun medAppHttp(tokens: AccessTokens): HttpClient = medAppHttpClient(
        engine = OkHttp.create(),
        baseUrl = BuildConfig.BASE_URL,
        logger = if (BuildConfig.DEBUG) LogcatLogger else null,
        tokens = tokens
    )

    /** Забытую сервером учётку возвращает регистрация — тем же путём, что недописанную. */
    @Provides
    fun accountReclaim(registration: AccountRegistration): AccountReclaim = registration

    /** Лог HTTP в debug; секреты из него вычищает клиент, а не этот адаптер. */
    private object LogcatLogger : Logger {
        override fun log(message: String) {
            Log.d("MedAppHttp", message)
        }
    }

    /**
     * Регистрационный токен сборки. Доказательством того, что регистрируется наше приложение, он
     * не является и таким не станет: он лежит в APK, и извлёкший его заводит учётки помимо
     * клиента. Независимого механизма — challenge, капчи, аттестации — здесь нет по принятому
     * решению (PLAN G1), и ограничение описано на стороне сервера.
     */
    @Provides
    @RegistrationToken
    fun registrationToken(): String = BuildConfig.REGISTRATION_TOKEN

    @Provides
    @Singleton
    @CrptHttp
    fun crptHttp(): HttpClient = crptHttpClient(OkHttp.create(), BuildConfig.CRPT_BASE_URL)

    /** Курьер поручений ходит к серверу тем же клиентом: пропуск, лог и таймауты у него те же. */
    @Provides
    @Singleton
    fun courierDoor(api: MedAppApi): CourierDoor = MedAppDoor(api)

    /** Курьер — порт очереди; исполняет его сеть. */
    @Provides
    @Singleton
    fun courier(implementation: MedAppCourier): Courier = implementation

    /**
     * Доменные порты, которые выполняет сеть: знакомство устройства с сервером и пополнение
     * словаря. Сценарии видят порт, а не провод (PLAN H1).
     */
    @Provides
    @Singleton
    fun deviceAccount(implementation: ServerDeviceAccount): DeviceAccount = implementation

    @Provides
    @Singleton
    fun vocabularyLibrary(implementation: ServerVocabularyLibrary): VocabularyLibrary = implementation

    @Provides
    @Singleton
    fun medKitInvitations(implementation: ServerMedKitInvitations): MedKitInvitations = implementation

    @Provides
    @InvitationTerm
    fun invitationTerm(): java.time.Duration = java.time.Duration.ofMinutes(BuildConfig.INVITATION_TERM_MINUTES)
}
