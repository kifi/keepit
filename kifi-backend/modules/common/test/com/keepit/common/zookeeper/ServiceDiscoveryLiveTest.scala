package com.keepit.common.zookeeper

import com.keepit.test._
import com.keepit.inject._
import com.keepit.common.amazon._
import com.keepit.common.service._
import play.api.Play.current
import play.api.libs.json._
import play.api.test.Helpers._
import play.api.templates.Html
import akka.actor.ActorRef
import akka.testkit.ImplicitSender
import org.specs2.mutable.Specification
import com.keepit.common.strings._
import org.apache.zookeeper.CreateMode
import org.apache.zookeeper.CreateMode._
import scala.util.{Random, Try}
import com.google.inject.util._
import com.google.inject._

class ServiceDiscoveryLiveTest extends Specification with TestInjector {

  args(skipAll = true)

  implicit val amazonInstanceInfoFormat = AmazonInstanceInfo.format

  "discovery" should {

    "register" in {
      withInjector()  { implicit injector =>
        val services = inject[FortyTwoServices]
        val service = RemoteService(AmazonInstanceId("id"), ServiceStatus.UP, IpAddress("127.0.0.1"), services.currentService)
        val amazonInstanceInfo = inject[AmazonInstanceInfo]
        val zk = new ZooKeeperClientImpl("localhost", 2000,
          Some({zk1 => println(s"in callback, got $zk1")}))
        try {
          val discovery: ServiceDiscovery = new ServiceDiscoveryImpl(zk, services, Providers.of(amazonInstanceInfo.copy(localHostname = "main")))
          discovery.myClusterSize === 0
          zk.watchChildren(Path(s"/fortytwo/services/TEST_MODE"), { (children : Seq[Node]) =>
            println("Service Instances ----------- : %s".format(children.mkString(", ")))
          })
          val path = zk.createPath(Path("/fortytwo/services/TEST_MODE"))
          val firstNode = zk.createNode(Node("/fortytwo/services/TEST_MODE/TEST_MODE_"), null, EPHEMERAL_SEQUENTIAL)
          zk.set(firstNode, Json.toJson(amazonInstanceInfo.copy(localHostname = "first")).toString)
          val registeredNode = discovery.register()
          val thirdNode = zk.createNode(Node("/fortytwo/services/TEST_MODE/TEST_MODE_"), null, EPHEMERAL_SEQUENTIAL)
          zk.set(thirdNode, Json.toJson(amazonInstanceInfo.copy(localHostname = "third")).toString)
          println("new node: " + thirdNode, null, EPHEMERAL_SEQUENTIAL)
          println("sleeping 1")
          Thread.sleep(10000)
          println(zk.getChildren(Path("/fortytwo/services/TEST_MODE")) mkString ",")
          zk.getChildren(Path("/fortytwo/services/TEST_MODE")).size === 3
          discovery.isLeader() === false
          discovery.myClusterSize === 3
          println("sleeping 2 - about to delete")
          Thread.sleep(10000)
          zk.deleteNode(firstNode)
          println("sleeping 3")
          Thread.sleep(10000)
          discovery.myClusterSize === 2
          discovery.isLeader() === true
        } finally {
          zk.close()
        }
      }
    }
  }
}
