package com.github.carolosf

import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalUnit

// TODO: Split these into their own files
interface IClockGateway {
    fun getDateTime(): ZonedDateTime
}

class JavaDateTimeClockGateway : IClockGateway {
    override fun getDateTime(): ZonedDateTime = ZonedDateTime.now()
}

interface IWaitUntilGateway {
    fun waitUntilNext(
        startTime: ZonedDateTime,
        temporalUnit: TemporalUnit = ChronoUnit.MINUTES,
        temporalAmount: Long = 1,
        pollingTimeInMilliseconds: Long = 100)
}


class WaitUntilGateway(private val clock: IClockGateway = JavaDateTimeClockGateway()) : IWaitUntilGateway {
    override fun waitUntilNext(
        startTime: ZonedDateTime,
        temporalUnit: TemporalUnit,
        temporalAmount: Long,
        pollingTimeInMilliseconds: Long)
    {
        startTime.waitUntilNext(temporalUnit, temporalAmount, pollingTimeInMilliseconds)
    }

    fun ZonedDateTime.waitUntilNext(
        temporalUnit: TemporalUnit = ChronoUnit.MINUTES,
        temporalAmount: Long = 1,
        pollingTimeInMilliseconds: Long = 100,
        clock: IClockGateway = JavaDateTimeClockGateway()
    ) {
        val resumeTime: ZonedDateTime = this.plus(temporalAmount, temporalUnit)
        while(clock.getDateTime().isBefore(resumeTime)) {
            Thread.sleep(pollingTimeInMilliseconds)
        }
    }
}
