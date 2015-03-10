package com.keepit.common.plugin

import com.keepit.common.service.{ ServiceType, ServiceStatus }
import com.keepit.common.zookeeper.{ Node, RemoteService, ServiceInstance, ServiceDiscovery }
import org.specs2.mutable.Specification

class SchedulingPropertiesTest extends Specification {
  "SchedulingProperties" should {
    "do offline" in {
      val sched = new SchedulingPropertiesImpl(null, true)

      val s1 = new ServiceInstance(Node("/node_00000001"), false, RemoteService(null, ServiceStatus.UP, ServiceType.TEST_MODE))
      val s2 = new ServiceInstance(Node("/node_00000002"), false, RemoteService(null, ServiceStatus.OFFLINE, ServiceType.TEST_MODE))
      val s3 = new ServiceInstance(Node("/node_00000003"), false, RemoteService(null, ServiceStatus.OFFLINE, ServiceType.TEST_MODE))
      val s4 = new ServiceInstance(Node("/node_00000004"), false, RemoteService(null, ServiceStatus.UP, ServiceType.TEST_MODE))
      val s5 = new ServiceInstance(Node("/node_00000005"), false, RemoteService(null, ServiceStatus.STOPPING, ServiceType.TEST_MODE))

      val members: Seq[ServiceInstance] = Seq(s1, s2, s3, s4, s5)

      sched.isRunningFor(members, s1, "a") === false
      sched.isRunningFor(members, s2, "a") === false
      sched.isRunningFor(members, s3, "a") === true
      sched.isRunningFor(members, s4, "a") === false
      sched.isRunningFor(members, s5, "a") === false

      sched.isRunningFor(members, s1, "b") === false
      sched.isRunningFor(members, s2, "b") === true
      sched.isRunningFor(members, s3, "b") === false
      sched.isRunningFor(members, s4, "b") === false
      sched.isRunningFor(members, s5, "b") === false

      sched.isRunningFor(members, s1, "c") === false
      sched.isRunningFor(members, s2, "c") === false
      sched.isRunningFor(members, s3, "c") === true
      sched.isRunningFor(members, s4, "c") === false
      sched.isRunningFor(members, s5, "c") === false

    }
    "no offline" in {
      val sched = new SchedulingPropertiesImpl(null, true)

      val s1 = new ServiceInstance(Node("/node_00000001"), false, RemoteService(null, ServiceStatus.UP, ServiceType.TEST_MODE))
      val s4 = new ServiceInstance(Node("/node_00000004"), false, RemoteService(null, ServiceStatus.UP, ServiceType.TEST_MODE))
      val s5 = new ServiceInstance(Node("/node_00000005"), false, RemoteService(null, ServiceStatus.STOPPING, ServiceType.TEST_MODE))

      val members: Seq[ServiceInstance] = Seq(s1, s4, s5)

      sched.isRunningFor(members, s1, "a") === false
      sched.isRunningFor(members, s4, "a") === true
      sched.isRunningFor(members, s5, "a") === false

      sched.isRunningFor(members, s1, "b") === true
      sched.isRunningFor(members, s4, "b") === false
      sched.isRunningFor(members, s5, "b") === false

      sched.isRunningFor(members, s1, "c") === false
      sched.isRunningFor(members, s4, "c") === true
      sched.isRunningFor(members, s5, "c") === false

    }
    "no healthy" in {
      val sched = new SchedulingPropertiesImpl(null, true)

      val s1 = new ServiceInstance(Node("/node_00000001"), false, RemoteService(null, ServiceStatus.SICK, ServiceType.TEST_MODE))
      val s4 = new ServiceInstance(Node("/node_00000004"), false, RemoteService(null, ServiceStatus.SICK, ServiceType.TEST_MODE))
      val s5 = new ServiceInstance(Node("/node_00000005"), false, RemoteService(null, ServiceStatus.STOPPING, ServiceType.TEST_MODE))

      val members: Seq[ServiceInstance] = Seq(s1, s4, s5)

      sched.isRunningFor(members, s1, "a") === false
      sched.isRunningFor(members, s4, "a") === true
      sched.isRunningFor(members, s5, "a") === false

      sched.isRunningFor(members, s1, "b") === true
      sched.isRunningFor(members, s4, "b") === false
      sched.isRunningFor(members, s5, "b") === false

      sched.isRunningFor(members, s1, "c") === false
      sched.isRunningFor(members, s4, "c") === true
      sched.isRunningFor(members, s5, "c") === false

    }
  }
}
