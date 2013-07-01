package com.keepit.common.zookeeper

import com.keepit.common.amazon._
import com.keepit.common.service._
import play.api.libs.json._
import org.specs2.mutable.Specification
import com.keepit.common.strings._

class ServiceClusterTest extends Specification {

  val instance1 = new AmazonInstanceInfo(
    instanceId = AmazonInstanceId("i-f168c1a8"),
    localHostname = "ip-10-160-95-26.us-west-1.compute.internal",
    publicHostname = "ec2-50-18-183-73.us-west-1.compute.amazonaws.com",
    localIp = IpAddress("10.160.95.26"),
    publicIp = IpAddress("50.18.183.73"),
    instanceType = "c1.medium",
    availabilityZone = "us-west-1b",
    securityGroups = "default",
    amiId = "ami-1bf9de5e",
    amiLaunchIndex = "0"
  )

  val instance2 = new AmazonInstanceInfo(
    instanceId = AmazonInstanceId("i-f168c1a9"),
    localHostname = "ip-10-160-95-25.us-west-1.compute.internal",
    publicHostname = "ec2-50-18-183-74.us-west-1.compute.amazonaws.com",
    localIp = IpAddress("10.160.95.23"),
    publicIp = IpAddress("50.18.183.72"),
    instanceType = "m1.medium",
    availabilityZone = "us-west-1a",
    securityGroups = "default",
    amiId = "ami-1bf9de5f",
    amiLaunchIndex = "1"
  )

  "ServiceCluster" should {
    "find node" in {
      val cluster = new ServiceCluster(ServiceType.TEST_MODE)
      val zk = new FakeZooKeeperClient()
      val basePath = "/fortytwo/services/TEST_MODE"
      zk.set(Node(s"$basePath/node_00000001"), Json.toJson(instance1).toString)
      zk.set(Node(s"$basePath/node_00000002"), Json.toJson(instance2).toString)
      zk.registeredCount === 2
      println(zk.nodes.mkString(" : "))
      zk.nodes.size === 2

      zk.nodes.exists(n => n == Node(s"$basePath/node_00000001")) === true
      zk.nodes.exists(n => n == Node(s"$basePath/node_00000002")) === true
      zk.nodes.exists(n => n == Node(s"$basePath/node_00000003")) === false

      cluster.update(zk, Node("node_00000001") :: Node("node_00000002") :: Nil)
      cluster.update(zk, Node(s"$basePath/node_00000001") :: Node(s"$basePath/node_00000002") :: Nil) must throwA[Exception]

      zk.registeredCount === 2
      println(zk.nodes.mkString(" : "))
      zk.nodes.size === 2

      cluster.registered(Node(s"$basePath/node_00000001")) === true
      cluster.registered(Node(s"$basePath/node_00000002")) === true
    }

    "RR router" in {
      val cluster = new ServiceCluster(ServiceType.TEST_MODE)
      val zk = new FakeZooKeeperClient()
      val basePath = "/fortytwo/services/TEST_MODE"
      zk.set(Node(s"$basePath/node_00000001"), Json.toJson(instance1).toString)
      zk.set(Node(s"$basePath/node_00000002"), Json.toJson(instance2).toString)
      zk.set(Node(s"$basePath/node_00000003"), Json.toJson(instance2).toString)
      cluster.update(zk, Node("node_00000001") :: Node("node_00000002") :: Node("node_00000003") :: Nil)
      val service1 = cluster.nextService()
      val service2 = cluster.nextService()
      val service3 = cluster.nextService()
      val service4 = cluster.nextService()
      val service5 = cluster.nextService()
      val service6 = cluster.nextService()
      val service7 = cluster.nextService()
      service1 === service4
      service2 === service5
      service3 === service6
      service1 === service7
      service1 !== service2
      service1 !== service3
    }
  }
}
