package com.keepit.common.time

case class TimeToSliceInDays(days: Int) {
  require(days >= 1 && days < 360)
}
case class OneSliceInMinutes(minutes: Int) {
  require(minutes >= 1 && minutes < 60 * 24)
}

// helper for some cron jobs
class TimeSlicer(clock: Clock) {
  def getSliceAndSize(timeToSlice: TimeToSliceInDays, oneSlice: OneSliceInMinutes): (Int, Int) = {
    val t = clock.now
    val slicesPerDay = 60 * 24 / oneSlice.minutes
    val (dayIdx, minuteIdx) = (t.dayOfYear.get % timeToSlice.days, (t.minuteOfDay.get / oneSlice.minutes) % slicesPerDay)
    val idx = dayIdx * slicesPerDay + minuteIdx
    (idx, timeToSlice.days * slicesPerDay)
  }
}

object TimeToSliceInDays {
  val ONE_DAY = TimeToSliceInDays(1)
  val ONE_WEEK = TimeToSliceInDays(7)
  val TWO_WEEKS = TimeToSliceInDays(14)
}
