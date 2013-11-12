package com.keepit.common.admin

import org.specs2.mutable._
import com.keepit.test.TestApplication
import play.api.test.Helpers._

class DouglasAdamsQuotesTest extends Specification {

  "DouglasAdamsQuotes" should {
    "load" in {
      running(new TestApplication()) {
        DouglasAdamsQuotes.quotes.size === 490
        DouglasAdamsQuotes.random.quote.size !== 0
      }
    }

  }
}
