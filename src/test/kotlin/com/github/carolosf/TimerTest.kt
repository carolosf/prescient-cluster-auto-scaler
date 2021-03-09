package com.github.carolosf

import org.junit.jupiter.api.Test
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

class TimerTest {
    @Test
    internal fun `ensure timer works`() {
        val startTime = ZonedDateTime.now()
        val waitGateway = WaitUntilGateway()
        waitGateway.waitUntilNext(startTime, ChronoUnit.MINUTES, 1)
        val endTime = ZonedDateTime.now()
        assert(endTime.isAfter(startTime.plusMinutes(1)))
    }
}