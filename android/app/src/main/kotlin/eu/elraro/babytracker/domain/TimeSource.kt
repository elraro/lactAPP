package eu.elraro.babytracker.domain

import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

/** The device's clock and zone, read at the moment of use. Open so tests can fix both. */
open class TimeSource
    @Inject
    constructor() {
        open fun now(): Instant = Instant.now()

        open fun zone(): ZoneId = ZoneId.systemDefault()
    }
