package com.github.carolosf

import java.time.ZonedDateTime

data class DailyTimeRange(val start: DailyTime, val end: DailyTime, val clockGateway: IClockGateway = JavaDateTimeClockGateway()) {
    companion object {
        fun parse(timeRange: String, clockGateway: IClockGateway = JavaDateTimeClockGateway()): DailyTimeRange {
            val split = timeRange.split("-")
            if (split.count() != 2) {
                throw IllegalArgumentException("Missing - between 24 hour time ranges $timeRange")
            }
            val start = split.first()
            val end = split.last()

            return DailyTimeRange(DailyTime.parse(start), DailyTime.parse(end), clockGateway);
        }

    }
    fun inWindow(): Boolean {
        val sameDay = end.hour > start.hour
        val now: ZonedDateTime = clockGateway.getDateTime()
        val relativeNow: ZonedDateTime = if (!sameDay && now.hour > 0 && now.hour < end.hour) {
            now.minusDays(1)
        } else {
            now
        }

        val tomorrow: ZonedDateTime = relativeNow.plusDays(1).withHour(0).withMinute(0).withSecond(0)

        val startDateTime = relativeNow.withHour(start.hour).withMinute(start.minute).withSecond(0)

        val endDateTime = if (sameDay) {
            relativeNow.withHour(end.hour).withMinute(end.minute).withSecond(0)
        } else {
            tomorrow.withHour(end.hour).withMinute(end.minute).withSecond(0)
        }

        return (startDateTime.isBefore(now) || startDateTime.isEqual(now)) && endDateTime.isAfter(now)
    }
}