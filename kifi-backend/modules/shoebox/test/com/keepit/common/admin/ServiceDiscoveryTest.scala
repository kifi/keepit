package com.keepit.common.admin

import com.keepit.common.service.ServiceStatus
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification
import play.api.test.FakeRequest
import play.api.test.Helpers._

/**
 * Running in shoebox project instead of commons project since we need an actual module to run play with
 */
class ServiceDiscoveryTest extends Specification with ShoeboxTestInjector {
  val modules = Seq.empty

  "up for deployment" in {
    withDb(modules: _*) { implicit injector =>
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
    withDb(modules: _*) { implicit injector =>
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
    withDb(modules: _*) { implicit injector =>
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
    withDb(modules: _*) { implicit injector =>
      inject[ServiceDiscovery].changeStatus(ServiceStatus.STARTING)
      val controller = inject[ServiceController]
      val result = controller.upForElb()(FakeRequest())
      status(result) must equalTo(SERVICE_UNAVAILABLE)
      contentType(result) must beSome("text/plain")
    }
  }
  "up for pingdom" in {
    withDb(modules: _*) { implicit injector =>
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
    withDb(modules: _*) { implicit injector =>
      inject[ServiceDiscovery].changeStatus(ServiceStatus.STARTING)
      val controller = inject[ServiceController]
      val result = controller.upForPingdom()(FakeRequest())
      status(result) must equalTo(SERVICE_UNAVAILABLE)
      contentType(result) must beSome("text/plain")
    }
  }
}
