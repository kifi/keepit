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

class ServiceDiscoveryLiveTest extends Specification with TestInjector {

  args(skipAll = true)

  "discovery" should {

    "register" in {
      withInjector()  { implicit injector =>
        val services = inject[FortyTwoServices]
        val service = RemoteService(AmazonInstanceId("id"), ServiceStatus.UP, IpAddress("127.0.0.1"), services.currentService)
        val amazonInstanceInfo = inject[AmazonInstanceInfo]
        val zk = new ZooKeeperClientImpl("localhost", 2000,
          Some({zk1 => println(s"in callback, got $zk1")}))
        try {
          val discovery: ServiceDiscovery = new ServiceDiscoveryImpl(zk, services, amazonInstanceInfo)
          zk.watchChildren(Path(s"/fortytwo/services/TEST_MODE"), { (children : Seq[Node]) =>
            println("Service Instances ----------- : %s".format(children.mkString(", ")))
          })
          val path = zk.createPath(Path("/fortytwo/services/TEST_MODE"))
          val firstNode = zk.createNode(Node("/fortytwo/services/TEST_MODE/TEST_MODE_"), null, EPHEMERAL_SEQUENTIAL)
          val registeredNode = discovery.register()
          registeredNode.name === "/fortytwo/services/TEST_MODE/TEST_MODE_0000000001"
          println("new node: " + zk.createNode(Node("/fortytwo/services/TEST_MODE/TEST_MODE_"), null, EPHEMERAL_SEQUENTIAL))
          println("sleeping 1")
          Thread.sleep(10000)
          println(zk.getChildren(Path("/fortytwo/services/TEST_MODE")) mkString ",")
          zk.getChildren(Path("/fortytwo/services/TEST_MODE")).size === 3
          discovery.isLeader() === false
          println("sleeping 2 - about to delete")
          Thread.sleep(10000)
          zk.deleteNode(firstNode)
          discovery.isLeader() === true
          println("sleeping 3")
          Thread.sleep(10000)
        } finally {
          zk.close()
        }
      }
    }
  }
}
