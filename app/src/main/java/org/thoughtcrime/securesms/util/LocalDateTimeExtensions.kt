package org.thoughtcrime.securesms.util

import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.concurrent.TimeUnit

/**
 * Convert [LocalDateTime] to be same as [System.currentTimeMillis]
 */
fun LocalDateTime.toMillis(): Long {
  return TimeUnit.SECONDS.toMillis(toEpochSecond(ZoneOffset.UTC))
}

/**
 * Return true if the [LocalDateTime] is within [start] and [end] inclusive.
 */
fun LocalDateTime.isBetween(start: LocalDateTime, end: LocalDateTime): Boolean {
  return (isEqual(start) || isAfter(start)) && (isEqual(end) || isBefore(end))
}

/**
 * Convert milliseconds to local date time with provided [zoneId].
 */
fun Long.toLocalDateTime(zoneId: ZoneId = ZoneId.systemDefault()): LocalDateTime {
  return LocalDateTime.ofInstant(Instant.ofEpochMilli(this), zoneId)
}

/**
 * Converts milliseconds to local time with provided [zoneId].
 */
fun Long.toLocalTime(zoneId: ZoneId = ZoneId.systemDefault()): LocalTime {
  return LocalDateTime.ofInstant(Instant.ofEpochMilli(this), zoneId).toLocalTime()
}

/**
 * Formats [LocalTime] as localized time. For example, "8:00 AM"
 */
fun LocalTime.formatHours(): String {
  return DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(this)
}
