package com.keepit.rover

import com.keepit.rover.controllers.internal.RoverController
import com.keepit.rover.test.RoverTestInjector
import org.specs2.mutable.Specification
import play.api.test.Helpers._

class RoverControllerTest extends Specification with RoverTestInjector {
  "rover controller" should {
    val modules = Seq(FakeRoverServiceClientModule())

    "be tested" in {
      "all good" === "all good"
    }
  }
}
