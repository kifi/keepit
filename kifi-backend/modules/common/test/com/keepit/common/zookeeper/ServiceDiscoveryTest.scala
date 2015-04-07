package com.keepit.common.zookeeper

import com.keepit.test._
import com.keepit.common.amazon._
import com.keepit.common.service._
import com.keepit.common.strings._
import akka.actor.Scheduler
import org.specs2.mutable.Specification

import scala.concurrent.ExecutionContext

class ServiceDiscoveryTest extends Specification with CommonTestInjector {

  "discovery" should {
    "serialize" in {
      withInjector() { implicit injector =>
        val service = RemoteService(inject[AmazonInstanceInfo], ServiceStatus.UP, ServiceType.DEV_MODE)
        val json = RemoteService.toJson(service)
        val deserialized = RemoteService.fromJson(json)
        deserialized === service
      }
    }

    "set of nodes" in {
      Set(Node("/a/b"), Node("/a/c")).contains(Node("/a/b")) === true
      Set(Node("/a/b"), Node("/a/c")).contains(Node("/a/d")) === false
    }

    "register" in {
      withInjector() { implicit injector =>
        val zkClient = inject[ZooKeeperClient]
        val discovery = new ServiceDiscoveryImpl(inject[ZooKeeperClient], inject[FortyTwoServices], inject[AmazonInstanceInfo], inject[Scheduler], null, false, Set.empty, inject[ExecutionContext])
        val registeredInstance = discovery.register()
        zkClient.session { zk => zk.getData[String](registeredInstance.node).get } === RemoteService.toJson(RemoteService(inject[AmazonInstanceInfo], ServiceStatus.STARTING, ServiceType.TEST_MODE))
      }
    }
  }
}
