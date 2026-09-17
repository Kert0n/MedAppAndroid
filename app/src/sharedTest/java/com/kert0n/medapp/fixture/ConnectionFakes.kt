package com.kert0n.medapp.fixture

import com.kert0n.medapp.feature.connectivity.Connection
import kotlinx.coroutines.flow.MutableStateFlow

/** Связь, которую проверка включает и выключает сама: система тут ни при чём. */
class FakeConnection(online: Boolean = true) : Connection {

    override val online = MutableStateFlow(online)
}
