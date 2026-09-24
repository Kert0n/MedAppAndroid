package com.kert0n.medapp.queue.pack

import com.kert0n.medapp.domain.pack.Package

/**
 * Снимок пачки с сервера, собранный в домен: подтверждённое состояние и знание очереди о нём
 * вместе, потому что порознь с сервера они не приходят (PLAN E4). Равенство — по обвязке и
 * тождеству пачки: два снимка одной пачки различаются версиями, а не полями.
 */
data class PackageSnapshot(val pack: Package, val sync: PackageSyncState)
