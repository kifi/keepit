package com.keepit.commanders

object TimeToReadCommander {
  private val WORDS_PER_MINUTE = 250
  private val READ_TIMES = Seq(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 15, 20, 30, 60)

  def wordCountToReadTimeMinutes(wc: Int): Option[Int] = {
    if (wc <= 0) return None
    val estimate = wc.toFloat / WORDS_PER_MINUTE
    Some(READ_TIMES.dropWhile(_ < estimate).headOption.getOrElse(60))
  }
}
