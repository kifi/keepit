package com.keepit.common.admin

import org.specs2.mutable._
import play.api.test.Helpers._
import com.keepit.test.CommonTestApplication

class DouglasAdamsQuotesTest extends Specification {

  "DouglasAdamsQuotes" should {
    "load" in {
      running(new CommonTestApplication()) {
        DouglasAdamsQuotes.quotes.size === 490
        DouglasAdamsQuotes.random.quote.size !== 0
      }
    }

  }
}
