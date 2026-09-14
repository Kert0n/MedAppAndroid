package com.kert0n.medapp.network.account

/**
 * Что сети нужно от хранилища учётки: прочитать её, записать придуманную и отметить, что сервер её
 * принял. Хранит её платформа (`platform/credentials`), и сеть знает о хранилище только этот порт.
 */
interface CredentialSource {

    suspend fun read(): StoredAccount

    /**
     * Записать придуманные данные — до регистрации: пока их нет на устройстве, отправлять нечего
     * (PLAN G2). Записанное остаётся неподтверждённым, пока о нём не узнает сервер.
     */
    suspend fun save(credentials: AccountCredentials): CredentialsSaved

    /** Сервер эти данные принял: дальше учётка читается как подтверждённая. */
    suspend fun confirm(): CredentialsSaved

    /**
     * Стереть сохранённое — только по решению человека о нечитаемой учётке (PLAN G2): после
     * этого учётки нет, и следующая запись ложится на чистое. Файл, который не читается, стирается
     * целиком — править его нечем. [CredentialsSaved.LOST] — стереть не удалось.
     */
    suspend fun forget(): CredentialsSaved
}
