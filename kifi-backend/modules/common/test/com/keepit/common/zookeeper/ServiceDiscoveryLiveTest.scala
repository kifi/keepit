package com.keepit.common.zookeeper

import com.keepit.test._
import com.keepit.common.amazon._
import com.keepit.common.service._
import play.api.Mode
import play.api.libs.json._
import org.specs2.mutable.Specification
import com.keepit.common.strings._
import com.keepit.common.time._
import org.apache.zookeeper.CreateMode._
import com.google.inject.util._

class ServiceDiscoveryLiveTest extends Specification with TestInjector {

  args(skipAll = true)

  val fakeJson =  RemoteService.toJson(RemoteService(inject[AmazonInstanceInfo], ServiceStatus.UP, ServiceType.SHOEBOX))
  

  implicit val amazonInstanceInfoFormat = AmazonInstanceInfo.format

  "discovery" should {

    "register" in {
      withInjector() { implicit injector =>
        val services = new FortyTwoServices(inject[Clock], Mode.Test, None, None) {
          override lazy val currentService: ServiceType = ServiceType.SHOEBOX
        }
        val amazonInstanceInfo = inject[AmazonInstanceInfo]
        val service = RemoteService(amazonInstanceInfo, ServiceStatus.UP, services.currentService)
        val zk = new ZooKeeperClientImpl("localhost", 3000,
          Some({zk1 => println(s"in callback, got $zk1")}))
        try {
          val discovery: ServiceDiscovery = new ServiceDiscoveryImpl(zk, services, Providers.of(amazonInstanceInfo.copy(localHostname = "main")))
          discovery.myClusterSize === 0
          zk.watchChildren(Path(s"/fortytwo/services/SHOEBOX"), { (children : Seq[Node]) =>
            println("Service Instances ----------- : %s".format(children.mkString(", ")))
          })
          val path = zk.createPath(Path("/fortytwo/services/SHOEBOX"))
          val firstNode = zk.createNode(Node("/fortytwo/services/SHOEBOX/SHOEBOX_"), fakeJson, EPHEMERAL_SEQUENTIAL)
          //zk.set(firstNode, Json.toJson(amazonInstanceInfo.copy(localHostname = "first")).toString)
          val registeredNode = discovery.register()
          println("registerred:::::")
          println(registeredNode)
          println(new String(zk.get(registeredNode)))
          discovery.startSelfCheck()
          val thirdNode = zk.createNode(Node("/fortytwo/services/SHOEBOX/SHOEBOX_"), fakeJson, EPHEMERAL_SEQUENTIAL)
          //zk.set(thirdNode, Json.toJson(amazonInstanceInfo.copy(localHostname = "third")).toString)
          println("new node: " + thirdNode, null, EPHEMERAL_SEQUENTIAL)
          println("sleeping 1")
          Thread.sleep(10000)
          println(zk.getChildren(Path("/fortytwo/services/SHOEBOX")) mkString ",")
          zk.getChildren(Path("/fortytwo/services/SHOEBOX")).size === 3
          discovery.isLeader() === false
          discovery.myClusterSize === 3
          zk.deleteNode(firstNode)
          println("sleeping 2")
          Thread.sleep(10000)
          discovery.myClusterSize === 2
          discovery.isLeader() === true
          zk.deleteNode(thirdNode)
          println("sleeping 3")
          Thread.sleep(10000)
          discovery.myClusterSize === 1
          discovery.isLeader() === true
          println(new String(zk.get(registeredNode)))
          discovery.unRegister()
          println("sleeping 4")
          Thread.sleep(10000)
          discovery.myClusterSize === 0
          discovery.isLeader() === false
        }
      }
    }
  }
}
