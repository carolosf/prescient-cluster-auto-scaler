package com.github.carolosf

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class MockClockGateway(val dt: ZonedDateTime) : IClockGateway{
    override fun getDateTime(): ZonedDateTime {
        return dt
    }
}

class DailyTimeRangeTest {
    private val start = DailyTime(20, 0)
    private val end = DailyTime(7, 0)
    private val beforeStartRange = ZonedDateTime.of(2021,1,1, 19, 0, 0, 0, ZoneId.systemDefault())!!
    private val startRange: ZonedDateTime = beforeStartRange.withHour(20)
    private val afterStartRange = beforeStartRange.withHour(21)
    private val endRange = beforeStartRange.plusDays(1).withHour(7)
    private val beforeEndRange = beforeStartRange.plusDays(1).withHour(6)
    private val afterEndRange = beforeStartRange.plusDays(1).withHour(8)

    @Test
    internal fun `ensure before start`() {
        assertFalse(DailyTimeRange(start, end, MockClockGateway(beforeStartRange)).inWindow())
    }

    @Test
    internal fun `ensure after start`() {
        assertTrue(DailyTimeRange(start, end, MockClockGateway(afterStartRange)).inWindow())
    }

    @Test
    internal fun `ensure start`() {
        assertTrue(DailyTimeRange(start, end, MockClockGateway(startRange)).inWindow())
    }

    @Test
    internal fun `ensure before end`() {
        assertTrue(DailyTimeRange(start, end, MockClockGateway(beforeEndRange)).inWindow())
    }

    @Test
    internal fun `ensure after end`() {
        assertFalse(DailyTimeRange(start, end, MockClockGateway(afterEndRange)).inWindow())
    }

    @Test
    internal fun `ensure end`() {
        assertFalse(DailyTimeRange(start, end, MockClockGateway(endRange)).inWindow())
    }
}