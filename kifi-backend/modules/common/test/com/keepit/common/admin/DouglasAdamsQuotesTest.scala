package com.keepit.common.admin

import org.specs2.mutable._

import com.keepit.test.DeprecatedEmptyApplication

import play.api.test.Helpers._

class DouglasAdamsQuotesTest extends Specification {

  "DouglasAdamsQuotes" should {
    "load" in {
      running(new DeprecatedEmptyApplication()) {
        DouglasAdamsQuotes.quotes.size === 490
        DouglasAdamsQuotes.random.quote.size !== 0
      }
    }

  }
}
