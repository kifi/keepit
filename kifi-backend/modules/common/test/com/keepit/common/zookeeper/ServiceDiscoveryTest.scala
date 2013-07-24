package com.keepit.common.zookeeper

import com.keepit.test._
import com.keepit.inject._
import com.keepit.common.amazon._
import com.keepit.common.service._
import com.keepit.common.strings._
import play.api.Play.current
import play.api.libs.json.JsValue
import play.api.test.Helpers._
import play.api.templates.Html
import akka.actor.{ActorRef, Scheduler}
import akka.testkit.ImplicitSender
import org.specs2.mutable.Specification
import org.apache.zookeeper.CreateMode
import org.apache.zookeeper.CreateMode._
import scala.util.{Random, Try}
import com.keepit.common.net.FakeHttpClient
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.net.FakeClientResponse
import com.google.inject.Provider

class ServiceDiscoveryTest extends Specification with DeprecatedTestInjector {

  "discovery" should {
    "serialize" in {
      withInjector() { implicit injector =>
        val service = RemoteService(inject[AmazonInstanceInfo], ServiceStatus.UP, ServiceType.DEV_MODE)
        val discovery = new ServiceDiscoveryImpl(inject[ZooKeeperClient], inject[FortyTwoServices], inject[Provider[AmazonInstanceInfo]], inject[Scheduler], ServiceType.TEST_MODE::Nil)
        val json = RemoteService.toJson(service)
        val deserialized = RemoteService.fromJson(json)
        deserialized === service
      }
    }

    "set of nodes" in {
      Set(Node("/a/b"), Node("a/c")).contains(Node("a/b")) == true
      Set(Node("/a/b"), Node("a/c")).contains(Node("a/d")) == false
    }

    "register" in {
      withInjector() { implicit injector =>
        val zk = inject[ZooKeeperClient]
        val discovery = new ServiceDiscoveryImpl(inject[ZooKeeperClient], inject[FortyTwoServices], inject[Provider[AmazonInstanceInfo]], inject[Scheduler], ServiceType.TEST_MODE::Nil)
        val registeredNode = discovery.register()
        fromByteArray(zk.get(registeredNode)) === RemoteService.toJson(RemoteService(inject[AmazonInstanceInfo], ServiceStatus.STARTING, ServiceType.TEST_MODE))
      }
    }
  }
}
