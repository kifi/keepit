package com.keepit.shoebox

import com.keepit.controllers.admin._
import com.keepit.controllers.ext._
import com.keepit.inject._
import com.keepit.test._
import org.specs2.mutable.Specification
import play.api.Play.current
import play.api.test.Helpers._

class ShoeboxModuleTest extends Specification {

  "Module" should {
    "instantiate controllers" in {
      running(new ShoeboxApplication().withFakeHealthcheck().withFakeMail()) {
        inject[AdminCommentController]
        inject[ExtCommentController]
        inject[AdminEventController]
        inject[ExtEventController]
        true
      }
    }
  }
}
