package com.keepit.common.admin

import play.api.test.Helpers._
import com.keepit.test.{ShoeboxApplicationInjector, ShoeboxApplication}
import play.api.test.FakeRequest
import org.specs2.mutable.Specification

/**
 * Running in shoebox project instead of commons project since we need an actual module to run play with
 */
class ServiceDiscoveryTest extends Specification with ShoeboxApplicationInjector {
  "up for deployment" in {
    running(new ShoeboxApplication()) {
      val route = com.keepit.common.admin.routes.ServiceController.upForDeployment().toString
      route === "/up/deployment"

      val controller = inject[ServiceController]
      val result = controller.upForDeployment()(FakeRequest())
      status(result) must equalTo(OK)
      contentType(result) must beSome("text/plain")
    }
  }
  "up for elb" in {
    running(new ShoeboxApplication()) {
      val route = com.keepit.common.admin.routes.ServiceController.upForElb().toString
      route === "/up/elb"

      val controller = inject[ServiceController]
      val result = controller.upForElb()(FakeRequest())
      status(result) must equalTo(OK)
      contentType(result) must beSome("text/plain")
    }
  }
  "up for pingdom" in {
    running(new ShoeboxApplication()) {
      val route = com.keepit.common.admin.routes.ServiceController.upForPingdom().toString
      route === "/up/pingdom"

      val controller = inject[ServiceController]
      val result = controller.upForPingdom()(FakeRequest())
      status(result) must equalTo(OK)
      contentType(result) must beSome("text/plain")
    }
  }
}
