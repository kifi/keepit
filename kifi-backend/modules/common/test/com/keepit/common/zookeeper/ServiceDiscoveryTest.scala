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
import akka.actor.ActorRef
import akka.testkit.ImplicitSender
import org.specs2.mutable.Specification
import com.keepit.common.db._
import com.keepit.common.db.slick._
import org.apache.zookeeper.CreateMode
import org.apache.zookeeper.CreateMode._
import scala.util.{Random, Try}

class ServiceDiscoveryTest extends Specification with TestInjector {

  "discovery" should {
    "serialize" in {
      withInjector()  { implicit injector =>
        val service = RemoteService(AmazonInstanceId("id"), ServiceStatus.UP, IpAddress("127.0.0.1"), ServiceType.DEV_MODE)
        val discovery = inject[ServiceDiscoveryImpl]
        val bytes = discovery.fromRemoteService(service)
        val deserialized = discovery.toRemoteService(bytes)
        deserialized === service
      }
    }

    "register" in {
      withInjector()  { implicit injector =>
        val zk = inject[ZooKeeperClient]
        val discovery: ServiceDiscovery = inject[ServiceDiscoveryImpl]
        val registeredNode = discovery.register()
        fromByteArray(zk.get(registeredNode)) === """{"instanceId":{"id":"i-f168c1a8"},"localHostname":"ip-10-160-95-26.us-west-1.compute.internal","publicHostname":"ec2-50-18-183-73.us-west-1.compute.amazonaws.com","localIp":{"ip":"10.160.95.26"},"publicIp":{"ip":"50.18.183.73"},"instanceType":"c1.medium","availabilityZone":"us-west-1b","securityGroups":"default","amiId":"ami-1bf9de5e","amiLaunchIndex":"0"}"""
      }
    }
  }
}
