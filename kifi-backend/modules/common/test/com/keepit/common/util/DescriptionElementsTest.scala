package com.keepit.common.util

import org.joda.time.{ Duration, DateTime, Period }
import org.specs2.mutable.Specification

class DescriptionElementsTest extends Specification {
  "DescriptionElements" should {
    "format time periods properly" in {
      import DescriptionElements._
      val now = new DateTime
      val tests = Seq[(Period, String)](
        Period.millis(1) -> "just now",
        Period.millis(10) -> "just now",
        Period.seconds(1) -> "just now",
        Period.seconds(10) -> "just now",
        Period.minutes(1) -> "in the last minute",
        Period.minutes(10) -> "in the last 10 minutes",
        Period.hours(1) -> "in the last hour",
        Period.hours(10) -> "in the last 10 hours",
        Period.days(1) -> "in the last day",
        Period.days(5) -> "in the last 5 days",
        Period.days(10) -> "in the last week",
        Period.weeks(1) -> "in the last week",
        Period.weeks(2) -> "in the last 2 weeks",
        Period.weeks(10) -> "in the last 2 months",
        Period.months(1) -> "in the last month",
        Period.months(10) -> "in the last 10 months",
        Period.months(100) -> "in the last 8 years",
        Period.years(1) -> "in the last 12 months", // TODO(ryan): seems weird...
        Period.years(10) -> "in the last 10 years"
      )

      for ((input, ans) <- tests) yield {
        DescriptionElements.formatPlain(inTheLast(input.toDurationTo(now))) === ans
      }
    }
  }
}
