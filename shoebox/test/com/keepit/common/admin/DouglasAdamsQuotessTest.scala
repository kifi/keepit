package com.keepit.common.admin

import com.keepit.model.NormalizedURIStates._
import com.keepit.common.time._
import com.keepit.model._
import org.specs2.mutable._
import play.api.test._
import play.api.test.Helpers._
import scala.math._
import com.keepit.test.EmptyApplication

class DouglasAdamsQuotesTest extends Specification {

  "DouglasAdamsQuotes" should {
    "load" in {
      running(new EmptyApplication()) {
        DouglasAdamsQuotes.qoutes.size === 490
        DouglasAdamsQuotes.random.quote.size !== 0
      }
    }

  }
}
