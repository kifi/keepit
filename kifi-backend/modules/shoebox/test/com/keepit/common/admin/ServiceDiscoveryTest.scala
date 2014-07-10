package com.keepit.common.admin

import play.api.test.Helpers._
import com.keepit.test.{ ShoeboxApplicationInjector, ShoeboxApplication }
import play.api.test.FakeRequest
import org.specs2.mutable.Specification
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.common.service.ServiceStatus

/**
 * Running in shoebox project instead of commons project since we need an actual module to run play with
 */
class ServiceDiscoveryTest extends Specification with ShoeboxApplicationInjector {
  "up for deployment" in {
    running(new ShoeboxApplication()) {
      val route = com.keepit.common.admin.routes.ServiceController.upForDeployment().toString
      route === "/up/deployment"

      inject[ServiceDiscovery].changeStatus(ServiceStatus.UP)
      val controller = inject[ServiceController]
      val result = controller.upForDeployment()(FakeRequest())
      status(result) must equalTo(OK)
      contentType(result) must beSome("text/plain")
    }
  }
  "down for deployment" in {
    running(new ShoeboxApplication()) {
      val discovery = inject[ServiceDiscovery]
      discovery.changeStatus(ServiceStatus.STARTING)
      discovery.myStatus.get === ServiceStatus.STARTING
      val controller = inject[ServiceController]
      val result = controller.upForDeployment()(FakeRequest())
      status(result) must equalTo(SERVICE_UNAVAILABLE)
      contentType(result) must beSome("text/plain")
    }
  }
  "up for elb" in {
    running(new ShoeboxApplication()) {
      val route = com.keepit.common.admin.routes.ServiceController.upForElb().toString
      route === "/up/elb"

      inject[ServiceDiscovery].changeStatus(ServiceStatus.SICK)
      val controller = inject[ServiceController]
      val result = controller.upForElb()(FakeRequest())
      status(result) must equalTo(OK)
      contentType(result) must beSome("text/plain")
    }
  }
  "down for elb" in {
    running(new ShoeboxApplication()) {
      inject[ServiceDiscovery].changeStatus(ServiceStatus.STARTING)
      val controller = inject[ServiceController]
      val result = controller.upForElb()(FakeRequest())
      status(result) must equalTo(SERVICE_UNAVAILABLE)
      contentType(result) must beSome("text/plain")
    }
  }
  "up for pingdom" in {
    running(new ShoeboxApplication()) {
      val route = com.keepit.common.admin.routes.ServiceController.upForPingdom().toString
      route === "/up/pingdom"

      inject[ServiceDiscovery].changeStatus(ServiceStatus.SICK)
      val controller = inject[ServiceController]
      val result = controller.upForPingdom()(FakeRequest())
      status(result) must equalTo(OK)
      contentType(result) must beSome("text/plain")
    }
  }
  "down for pingdom" in {
    running(new ShoeboxApplication()) {
      inject[ServiceDiscovery].changeStatus(ServiceStatus.STARTING)
      val controller = inject[ServiceController]
      val result = controller.upForPingdom()(FakeRequest())
      status(result) must equalTo(SERVICE_UNAVAILABLE)
      contentType(result) must beSome("text/plain")
    }
  }
}
