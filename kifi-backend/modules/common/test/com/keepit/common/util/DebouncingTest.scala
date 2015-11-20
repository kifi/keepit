package com.keepit.common.util

import org.joda.time.Period
import org.specs2.mutable.Specification

class DebouncingTest extends Specification {
  private class LoudYapper {
    var count = 0
    private def increment(): Unit = count += 1
    def trigger() = increment()
  }
  private class QuietYapper extends Debouncing {
    var count = 0
    private def increment(): Unit = count += 1
    def trigger() = debounce("trigger", Period.millis(1)) { increment() }
  }
  "Debouncing" should {
    "make sure an event doesn't get triggered too often" in {
      val (loud, quiet) = (new LoudYapper, new QuietYapper)
      (1 to 1000).foreach { _ =>
        loud.trigger()
        quiet.trigger()
      }
      println(s"loud = ${loud.count}, quiet = ${quiet.count}")
      loud.count === 1000
      quiet.count must beBetween(1, 20) // yell at Ryan if this breaks
    }
  }
}
