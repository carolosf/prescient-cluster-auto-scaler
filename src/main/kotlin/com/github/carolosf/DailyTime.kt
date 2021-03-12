package com.github.carolosf

data class DailyTime(val hour: Int, val minute: Int) {
    init {
        assertHour(this.hour)
        assertMinute(this.minute)
    }
    companion object {
        fun parse(string24HourTime: String): DailyTime {
            val split = string24HourTime.split(":")
            if (split.count() != 2) {
                throw IllegalArgumentException("Missing : in 24 hour time $string24HourTime")
            }
            val hour = split.first().toInt()
            val minute = split.last().toInt()

            return DailyTime(hour, minute)
        }

        private fun assertMinute(minute: Int) {
            if (minute < 0 || minute > 59) {
                throw IllegalArgumentException("Invalid minute $minute")
            }
        }

        private fun assertHour(hour: Int) {
            if (hour < 0 || hour > 23) {
                throw IllegalArgumentException("Invalid hour $hour")
            }
        }
    }
}