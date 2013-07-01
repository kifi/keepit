package com.keepit.common.zookeeper

import com.keepit.test._
import com.keepit.inject._
import com.keepit.common.amazon._
import com.keepit.common.service._
import play.api.Mode
import play.api.Mode._
import play.api.Play.current
import play.api.libs.json._
import play.api.test.Helpers._
import play.api.templates.Html
import akka.actor.ActorRef
import akka.testkit.ImplicitSender
import org.specs2.mutable.Specification
import com.keepit.common.strings._
import com.keepit.common.time._
import com.keepit.common.db._
import com.keepit.common.db.slick._
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
      val services = new FortyTwoServices(inject[Clock], Mode.Test, None, None) {
        override lazy val currentService: ServiceType = ServiceType.SHOEBOX
      }
      val service = RemoteService(AmazonInstanceId("id"), ServiceStatus.UP, IpAddress("127.0.0.1"), services.currentService)
      val amazonInstanceInfo = inject[AmazonInstanceInfo]
      val zk = new ZooKeeperClientImpl("localhost", 2000,
        Some({zk1 => println(s"in callback, got $zk1")}))
      try {
        val discovery: ServiceDiscovery = new ServiceDiscoveryImpl(zk, services, Providers.of(amazonInstanceInfo.copy(localHostname = "main")))
        discovery.myClusterSize === 0
        zk.watchChildren(Path(s"/fortytwo/services/SHOEBOX"), { (children : Seq[Node]) =>
          println("Service Instances ----------- : %s".format(children.mkString(", ")))
        })
        val path = zk.createPath(Path("/fortytwo/services/SHOEBOX"))
        val firstNode = zk.createNode(Node("/fortytwo/services/SHOEBOX/SHOEBOX_"), null, EPHEMERAL_SEQUENTIAL)
        zk.set(firstNode, Json.toJson(amazonInstanceInfo.copy(localHostname = "first")).toString)
        val registeredNode = discovery.register()
        val thirdNode = zk.createNode(Node("/fortytwo/services/SHOEBOX/SHOEBOX_"), null, EPHEMERAL_SEQUENTIAL)
        zk.set(thirdNode, Json.toJson(amazonInstanceInfo.copy(localHostname = "third")).toString)
        println("new node: " + thirdNode, null, EPHEMERAL_SEQUENTIAL)
        println("sleeping 1")
        Thread.sleep(2000)
        println(zk.getChildren(Path("/fortytwo/services/SHOEBOX")) mkString ",")
        zk.getChildren(Path("/fortytwo/services/SHOEBOX")).size === 3
        discovery.isLeader() === false
        discovery.myClusterSize === 3
        zk.deleteNode(firstNode)
        println("sleeping 2")
        Thread.sleep(2000)
        discovery.myClusterSize === 2
        discovery.isLeader() === true
        zk.deleteNode(thirdNode)
        println("sleeping 3")
        Thread.sleep(2000)
        discovery.myClusterSize === 1
        discovery.isLeader() === true
        discovery.unRegister()
        println("sleeping 3")
        Thread.sleep(2000)
        discovery.myClusterSize === 0
        discovery.isLeader() === true
      } finally {
        zk.close()
      }
    }
  }
}
