package com.keepit.common.zookeeper

import com.keepit.test._
import com.keepit.common.actor.TestActorSystemModule
import com.keepit.common.amazon._
import com.keepit.common.healthcheck.FakeAirbrakeNotifier
import com.keepit.common.service._
import com.keepit.common.strings._
import com.keepit.common.time._
import com.keepit.inject.{ FortyTwoConfig, ApplicationInjector }
import play.api.Mode
import play.api.libs.json._
import play.api.test.Helpers._
import org.specs2.mutable.Specification
import org.apache.zookeeper.CreateMode._
import com.google.inject.util._
import com.google.inject.Injector
import akka.actor.Scheduler

class ServiceDiscoveryLiveTest extends Specification with ApplicationInjector {

  args(skipAll = true)

  implicit val amazonInstanceInfoFormat = AmazonInstanceInfo.format

  def amazonInstanceInfo(id: Int) = new AmazonInstanceInfo(
    instanceId = AmazonInstanceId("i-f168c1a8"),
    name = Some("some-name"),
    service = Some("some-service"),
    localHostname = s"host$id",
    publicHostname = s"host$id",
    localIp = IpAddress(s"127.0.0.$id"),
    publicIp = IpAddress(s"127.0.0.$id"),
    instanceType = "c1.medium",
    availabilityZone = "us-west-1b",
    securityGroups = "default",
    amiId = "ami-1bf9de5e",
    amiLaunchIndex = "0",
    loadBalancer = Some("some-elb")
  )
  def remoteServiceJson(id: Int) = RemoteService.toJson(RemoteService(amazonInstanceInfo(id), ServiceStatus.UP, ServiceType.SHOEBOX))

  "discovery" should {

    "register" in {
      running(new CommonTestApplication(TestActorSystemModule())) {
        val services = new FortyTwoServices(inject[Clock], Mode.Test, None, None, inject[FortyTwoConfig]) {
          override lazy val currentService: ServiceType = ServiceType.SHOEBOX
        }
        val zkClient = new ZooKeeperClientImpl("localhost", 3000,
          Some({ zk1 => println(s"in callback, got $zk1") }))
        val discovery: ServiceDiscovery = new ServiceDiscoveryImpl(zkClient, services, amazonInstanceInfo(1), inject[Scheduler], Providers.of(new FakeAirbrakeNotifier()), false, Nil)
        zkClient.session { zk =>
          try {
            discovery.myClusterSize === 0
            zk.watchChildrenWithData[String](Node("/fortytwo/services/SHOEBOX"), { (children: Seq[(Node, String)]) =>
              println("Service Instances ----------- : %s".format(children.map(_._1).mkString(", ")))
            })
            val path = zk.create(Node("/fortytwo/services/SHOEBOX"))
            val firstNode = zk.createChild(path, "SHOEBOX_", remoteServiceJson(1), EPHEMERAL_SEQUENTIAL)
            val secondNode = zk.createChild(path, "SHOEBOX_", remoteServiceJson(2), EPHEMERAL_SEQUENTIAL)
            println("new node: " + secondNode, null, EPHEMERAL_SEQUENTIAL)
            val registeredInstance = discovery.register()
            println("registerred:::::")
            println(registeredInstance)
            println(zk.getData[String](registeredInstance.node))
            discovery.startSelfCheck()
            val thirdNode = zk.createChild(path, "SHOEBOX_", remoteServiceJson(3), EPHEMERAL_SEQUENTIAL)
            println("new node: " + thirdNode, null, EPHEMERAL_SEQUENTIAL)
            println("sleeping 1")
            Thread.sleep(10000)

            println(zk.getChildren(path) mkString ",")
            zk.getChildren(path).size === 3
            discovery.isLeader() === false
            discovery.myClusterSize === 3
            zk.delete(secondNode)
            println("sleeping 2")
            Thread.sleep(10000)

            discovery.myClusterSize === 2
            discovery.isLeader() === true
            zk.delete(thirdNode)
            println("sleeping 3")
            Thread.sleep(10000)

            discovery.myClusterSize === 1
            discovery.isLeader() === true
            println(zk.getData[String](registeredInstance.node))
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
}
