package com.kert0n.medapp.domain.pack

/**
 * Что стало с коробкой после перехода: она либо осталась, либо кончилась. Два случая названы
 * типом, а не `null`: отсутствие ничего не говорит о том, что при этом должно быть сделано, и
 * потому его легко не заметить — а у конца есть зависимые, которые о нём узнают (PLAN D3).
 */
sealed interface PackageAfter {

    /** Коробка осталась: в ней [pkg] после перехода. */
    data class Left(val pkg: Package) : PackageAfter

    /** Коробки больше нет; что от неё осталось, несёт [ending]. */
    data class Ended(val ending: PackageEnding) : PackageAfter
}
