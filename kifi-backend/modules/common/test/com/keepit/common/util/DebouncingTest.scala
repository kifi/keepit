package com.keepit.common.util

import org.joda.time.Period
import org.specs2.mutable.Specification

import scala.concurrent.duration.Duration
import scala.concurrent.{ Await, Future }

class DebouncingTest extends Specification {
  private class LoudYapper {
    var count = 0
    var value = 0
    def trigger() = {
      value += 1
      count += 1
    }
  }
  private class QuietYapper extends Debouncing.Drop {
    var count = 0
    var value = 0
    def trigger() = debounce("trigger", Period.millis(1)) {
      value += 1
      count += 1
    }
  }
  private class BufferedYapper extends Debouncing.Buffer {
    var count = 0
    var value = 0
    def trigger() = debounce("trigger", Period.millis(1))(1) { buf =>
      value += buf.sum
      count += 1
    }
  }
  "Debouncing" should {
    "make sure an event doesn't get triggered too often" in {
      val (loud, quiet, buffered) = (new LoudYapper, new QuietYapper, new BufferedYapper)
      val n = 1000
      (1 to n).foreach { _ =>
        loud.trigger(); quiet.trigger(); buffered.trigger()
      }

      println(s"loud = ${(loud.count, loud.value)}, quiet = ${(quiet.count, quiet.value)}, buffered = ${(buffered.count, buffered.value)}")
      loud.count === 1000
      loud.value === 1000

      quiet.count must beBetween(1, n / 5)
      quiet.value must beBetween(1, n / 5)

      buffered.count must beBetween(1, n / 5)
      buffered.value must beCloseTo(n, n / 5)

      // The buffered one will always get to the correct value right after it actually triggers
      Thread.sleep(5)
      buffered.trigger()
      buffered.value === n + 1
    }
    "be thread-safe" in {
      val n = 1000
      val (loud, quiet, buffered) = (new LoudYapper, new QuietYapper, new BufferedYapper)
      Await.result(Future.sequence((1 to n).map { _ =>
        Future { loud.trigger(); quiet.trigger(); buffered.trigger() }
      }), Duration.Inf)

      println(s"loud = ${(loud.count, loud.value)}, quiet = ${(quiet.count, quiet.value)}, buffered = ${(buffered.count, buffered.value)}")
      loud.count must beCloseTo(n, n / 5)
      loud.value must beCloseTo(n, n / 5)

      quiet.count must beBetween(1, n / 5)
      quiet.value must beBetween(1, n / 5)

      buffered.count must beBetween(1, n / 5)
      buffered.value must beCloseTo(n, n / 5)

      Thread.sleep(5)
      buffered.trigger()
      buffered.value === n + 1
    }
  }
}
