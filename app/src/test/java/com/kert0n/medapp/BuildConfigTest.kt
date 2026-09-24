package com.kert0n.medapp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Параметры сборки приходят из local.properties или из окружения CI. Проверяется не
 * значение — оно у каждой сборки своё, — а то, что подстановка вообще произошла и
 * приложению есть с чем идти к серверу (G1).
 *
 * Тест видит только debug, где значение токена по умолчанию допустимо. Release с этим
 * значением собрать нельзя: задача `verifyReleaseSecrets` требует настоящий токен и
 * останавливает `assembleRelease` и `bundleRelease`. Проверить это тестом отсюда
 * невозможно — запрет живёт в сборке, а не в коде приложения.
 */
class BuildConfigTest {

    @Test
    fun testsRunAgainstDebugVariant() {
        assertTrue(BuildConfig.DEBUG)
    }

    @Test
    fun registrationTokenIsPresent() {
        assertFalse(BuildConfig.REGISTRATION_TOKEN.isBlank())
    }

    @Test
    fun serverAddressesAreHttps() {
        assertTrue(BuildConfig.BASE_URL.startsWith("https://"))
        assertTrue(BuildConfig.MARKING_URL.startsWith("https://"))
    }
}
