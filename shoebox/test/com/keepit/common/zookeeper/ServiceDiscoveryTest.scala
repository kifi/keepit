package com.keepit.common.zookeeper

import com.keepit.test._
import com.keepit.inject._
import com.keepit.common.amazon._
import com.keepit.common.service._
import play.api.Play.current
import play.api.libs.json.JsValue
import play.api.test.Helpers._
import play.api.templates.Html
import akka.actor.ActorRef
import akka.testkit.ImplicitSender
import org.specs2.mutable.Specification
import com.keepit.common.db._
import com.keepit.common.db.slick._
import org.apache.zookeeper.CreateMode
import org.apache.zookeeper.CreateMode._
import scala.util.{Random, Try}

class ServiceDiscoveryTest extends Specification with TestInjector {

  args(skipAll = true)

  "discovery" should {
    "serialize" in {
      withInjector()  { implicit injector =>
        val service = RemoteService(AmazonInstanceId("id"), ServiceStatus.UP, IpAddress("1.1.1.1"), ServiceType.DEV_MODE)
        val discovery = inject[ServiceDiscovery]
        val bytes = discovery.fromRemoteService(service)
        val deserialized = discovery.toRemoteService(bytes)
        deserialized === service
      }
    }

    "register" in {
      withInjector()  { implicit injector =>
        val services = inject[FortyTwoServices]
        val service = RemoteService(AmazonInstanceId("id"), ServiceStatus.UP, IpAddress("1.1.1.1"), services.currentService)
        val basePath = Path("/test" + Random.nextLong.abs)
        val zk = new ZooKeeperClientImpl("localhost", 2000, basePath, Some({zk1 => println(s"in callback, got $zk1")}))
        val discovery = new ServiceDiscovery(zk, services)
        val node = discovery.register()
        node.name === s"""${basePath.name}/services/TEST_MODE/TEST_MODE_0000000000"""
      }
    }
  }
}
