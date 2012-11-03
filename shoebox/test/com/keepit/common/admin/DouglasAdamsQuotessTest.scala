package com.keepit.common.admin

import com.keepit.model.NormalizedURI.States._
import com.keepit.common.time._
import com.keepit.model._
import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner
import play.api.test._
import play.api.test.Helpers._
import scala.math._
import org.junit.runner.RunWith
import org.specs2.runner.JUnitRunner
import com.keepit.test.EmptyApplication

@RunWith(classOf[JUnitRunner])
class DouglasAdamsQuotesTest extends SpecificationWithJUnit {

  "DouglasAdamsQuotes" should {
    "load" in {
      running(new EmptyApplication()) {
        DouglasAdamsQuotes.qoutes.size === 490
        DouglasAdamsQuotes.random.quote.size !== 0
      }
    }
    
  }
}