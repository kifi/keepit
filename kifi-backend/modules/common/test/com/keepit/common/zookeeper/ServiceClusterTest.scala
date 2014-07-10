package com.keepit.common.zookeeper

import com.keepit.common.healthcheck.FakeAirbrakeNotifier
import com.keepit.common.amazon._
import com.keepit.common.service._
import org.specs2.mutable.Specification
import com.keepit.common.strings._
import com.google.inject.util.Providers
import com.keepit.common.actor.FakeScheduler

class ServiceClusterTest extends Specification {

  val instance1 = new AmazonInstanceInfo(
    instanceId = AmazonInstanceId("i-f168c1a8"),
    name = Some("some-name"),
    service = Some("some-service"),
    localHostname = "ip-10-160-95-26.us-west-1.compute.internal",
    publicHostname = "ec2-50-18-183-73.us-west-1.compute.amazonaws.com",
    localIp = IpAddress("10.160.95.26"),
    publicIp = IpAddress("50.18.183.73"),
    instanceType = "c1.medium",
    availabilityZone = "us-west-1b",
    securityGroups = "default",
    amiId = "ami-1bf9de5e",
    amiLaunchIndex = "0",
    loadBalancer = Some("some-elb")
  )

  val remoteService1 = RemoteService(instance1, ServiceStatus.UP, ServiceType.TEST_MODE)

  val instance2 = new AmazonInstanceInfo(
    instanceId = AmazonInstanceId("i-f168c1a9"),
    name = Some("some-name"),
    service = Some("some-service"),
    localHostname = "ip-10-160-95-25.us-west-1.compute.internal",
    publicHostname = "ec2-50-18-183-74.us-west-1.compute.amazonaws.com",
    localIp = IpAddress("10.160.95.23"),
    publicIp = IpAddress("50.18.183.72"),
    instanceType = "m1.medium",
    availabilityZone = "us-west-1a",
    securityGroups = "default",
    amiId = "ami-1bf9de5f",
    amiLaunchIndex = "1",
    loadBalancer = Some("some-elb")
  )

  val remoteService2 = RemoteService(instance2, ServiceStatus.UP, ServiceType.TEST_MODE)

  val instance3 = new AmazonInstanceInfo(
    instanceId = AmazonInstanceId("i-f168c1a8"),
    name = Some("some-name"),
    service = Some("some-service"),
    localHostname = "ip-10-160-95-2.us-west-1.compute.internal",
    publicHostname = "ec2-50-18-183-7.us-west-1.compute.amazonaws.com",
    localIp = IpAddress("10.160.95.2"),
    publicIp = IpAddress("50.18.183.7"),
    instanceType = "m1.medium",
    availabilityZone = "us-west-1a",
    securityGroups = "default",
    amiId = "ami-1bf9de5f",
    amiLaunchIndex = "1",
    loadBalancer = Some("some-elb")
  )

  val remoteService3 = RemoteService(instance3, ServiceStatus.UP, ServiceType.TEST_MODE)

  "ServiceCluster" should {
    "find node" in {
      val cluster = new ServiceCluster(ServiceType.TEST_MODE, Providers.of(new FakeAirbrakeNotifier()), new FakeScheduler(), () => {})
      val zk = new FakeZooKeeperClient()
      val basePath = Node("/fortytwo/services/TEST_MODE")
      zk.session { zk =>
        zk.createChild(basePath, "node_00000001", RemoteService.toJson(remoteService1))
        zk.createChild(basePath, "node_00000002", RemoteService.toJson(remoteService2))
      }
      zk.registeredCount === 2
      println(zk.nodes.mkString(" : "))
      zk.nodes.size === 2

      zk.nodes.exists(n => n == Node(basePath, "node_00000001")) === true
      zk.nodes.exists(n => n == Node(basePath, "node_00000002")) === true
      zk.nodes.exists(n => n == Node(basePath, "node_00000003")) === false

      zk.session { zk =>
        val children = zk.getChildren(basePath).map(child => (child, zk.getData[String](child).get))
        children.size === 2
        cluster.update(zk, children)
      }
      zk.registeredCount === 2
      println(zk.nodes.mkString(" : "))
      zk.nodes.size === 2

      cluster.registered(new ServiceInstance(Node(basePath, "node_00000001"), false, remoteService1)) === true
      cluster.registered(new ServiceInstance(Node(basePath, "node_00000002"), false, remoteService2)) === true
    }

    "equal service instances" in {
      val basePath = Node("/fortytwo/services/TEST_MODE")
      val s1 = new ServiceInstance(Node(basePath, "node_00000001"), false, null)
      val s2 = new ServiceInstance(Node(basePath, "node_00000001"), false, null)
      val s3 = new ServiceInstance(Node(basePath, "node_00000003"), false, null)
      s1 === s2
      s1 !== s3
      s2 !== s3
    }

    "dedup nodes" in {
      val cluster = new ServiceCluster(ServiceType.TEST_MODE, Providers.of(new FakeAirbrakeNotifier()), new FakeScheduler(), () => {})
      val zk = new FakeZooKeeperClient()
      val basePath = Node("/fortytwo/services/TEST_MODE")
      zk.session { zk =>
        zk.createChild(basePath, "node_00000001", RemoteService.toJson(remoteService1))
        zk.createChild(basePath, "node_00000002", RemoteService.toJson(remoteService1)) //me a dup!
        zk.createChild(basePath, "node_00000003", RemoteService.toJson(remoteService2))
      }
      zk.registeredCount === 3
      println(zk.nodes.mkString(" : "))
      zk.nodes.size === 3

      zk.nodes.exists(n => n == Node(basePath, "node_00000001")) === true
      zk.nodes.exists(n => n == Node(basePath, "node_00000002")) === true
      zk.nodes.exists(n => n == Node(basePath, "node_00000003")) === true
      zk.nodes.exists(n => n == Node(basePath, "node_00000004")) === false

      zk.session { zk =>
        val children = zk.getChildren(basePath).map(child => (child, zk.getData[String](child).get))
        cluster.update(zk, children)
      }
      zk.registeredCount === 2
      println(zk.nodes.mkString(" : "))
      zk.nodes.size === 2

      cluster.registered(new ServiceInstance(Node(basePath, "node_00000002"), false, remoteService1)) === true
      cluster.registered(new ServiceInstance(Node(basePath, "node_00000003"), false, remoteService2)) === true
    }

    "RR router" in {
      val cluster = new ServiceCluster(ServiceType.TEST_MODE, Providers.of(new FakeAirbrakeNotifier()), new FakeScheduler(), () => {})
      val zk = new FakeZooKeeperClient()
      val basePath = Node("/fortytwo/services/TEST_MODE")
      zk.session { zk =>
        zk.createChild(basePath, "node_00000001", RemoteService.toJson(remoteService1))
        zk.createChild(basePath, "node_00000002", RemoteService.toJson(remoteService2))
        zk.createChild(basePath, "node_00000003", RemoteService.toJson(remoteService3))
        val children = zk.getChildren(basePath).map(child => (child, zk.getData[String](child).get))
        cluster.update(zk, children)
      }
      val service1 = cluster.nextService.get
      val service2 = cluster.nextService.get
      val service3 = cluster.nextService.get
      val service4 = cluster.nextService.get
      val service5 = cluster.nextService.get
      val service6 = cluster.nextService.get
      val service7 = cluster.nextService.get
      Set(service1.node.path, service2.node.path, service3.node.path) === Set("/fortytwo/services/TEST_MODE/node_00000001", "/fortytwo/services/TEST_MODE/node_00000002", "/fortytwo/services/TEST_MODE/node_00000003")
      service1 === service4
      service2 === service5
      service3 === service6
      service1 === service7
      service1 !== service2
      service1 !== service3
      service1.reportedSentServiceUnavailable === false
      service1.reportServiceUnavailable()
      service1.reportedSentServiceUnavailable === true
      service1.reportServiceUnavailable()
      service1.reportedSentServiceUnavailable === true
      val service = cluster.nextService.get
      service !== service1
      service !== service2
      service === service3
      cluster.nextService.get === service2
      cluster.nextService.get === service3
      cluster.nextService.get === service2
    }
  }
}
