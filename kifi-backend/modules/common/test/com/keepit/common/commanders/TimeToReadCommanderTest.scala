package com.keepit.common.commanders

import org.specs2.mutable.Specification


class TimeToReadCommanderTest extends Specification {
  "TimeToReadCommander" should {
    "correctly estimate time to read" in {
      val wordCounts = Seq(-3, -1, 0, 1, 2, 34, 123, 250, 300, 500, 5345, 15000, 15001, 10000000)
      val readTimes = Seq(None, None, None, Some(1), Some(1), Some(1), Some(1), Some(1), Some(2), Some(2), Some(30), Some(60), Some(60), Some(60))
      (wordCounts map TimeToReadCommander.wordCountToReadTimeMinutes) === readTimes
    }
  }
}
