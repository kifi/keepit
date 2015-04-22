package com.keepit.common.zookeeper

import com.keepit.common.service.{ ServiceType, ServiceStatus }
import org.specs2.mutable.Specification

class ShardingCommanderTest extends Specification {
  "ShardingCommander" should {
    "do offline" in {
      val sched = new ShardingCommander(null)

      val s1 = new ServiceInstance(Node("/node_00000001"), false, RemoteService(null, ServiceStatus.UP, ServiceType.TEST_MODE))
      val s2 = new ServiceInstance(Node("/node_00000002"), false, RemoteService(null, ServiceStatus.OFFLINE, ServiceType.TEST_MODE))
      val s3 = new ServiceInstance(Node("/node_00000003"), false, RemoteService(null, ServiceStatus.OFFLINE, ServiceType.TEST_MODE))
      val s4 = new ServiceInstance(Node("/node_00000004"), false, RemoteService(null, ServiceStatus.UP, ServiceType.TEST_MODE))
      val s5 = new ServiceInstance(Node("/node_00000005"), false, RemoteService(null, ServiceStatus.STOPPING, ServiceType.TEST_MODE))

      val members: Seq[ServiceInstance] = Seq(s1, s2, s3, s4, s5)

      sched.isRunningFor(members, s1, "a".hashCode) === false
      sched.isRunningFor(members, s2, "a".hashCode) === false
      sched.isRunningFor(members, s3, "a".hashCode) === true
      sched.isRunningFor(members, s4, "a".hashCode) === false
      sched.isRunningFor(members, s5, "a".hashCode) === false

      sched.isRunningFor(members, s1, "b".hashCode) === false
      sched.isRunningFor(members, s2, "b".hashCode) === true
      sched.isRunningFor(members, s3, "b".hashCode) === false
      sched.isRunningFor(members, s4, "b".hashCode) === false
      sched.isRunningFor(members, s5, "b".hashCode) === false

      sched.isRunningFor(members, s1, "c".hashCode) === false
      sched.isRunningFor(members, s2, "c".hashCode) === false
      sched.isRunningFor(members, s3, "c".hashCode) === true
      sched.isRunningFor(members, s4, "c".hashCode) === false
      sched.isRunningFor(members, s5, "c".hashCode) === false

    }
    "no offline" in {
      val sched = new ShardingCommander(null)

      val s1 = new ServiceInstance(Node("/node_00000001"), false, RemoteService(null, ServiceStatus.UP, ServiceType.TEST_MODE))
      val s4 = new ServiceInstance(Node("/node_00000004"), false, RemoteService(null, ServiceStatus.UP, ServiceType.TEST_MODE))
      val s5 = new ServiceInstance(Node("/node_00000005"), false, RemoteService(null, ServiceStatus.STOPPING, ServiceType.TEST_MODE))

      val members: Seq[ServiceInstance] = Seq(s1, s4, s5)

      sched.isRunningFor(members, s1, "a".hashCode) === false
      sched.isRunningFor(members, s4, "a".hashCode) === true
      sched.isRunningFor(members, s5, "a".hashCode) === false

      sched.isRunningFor(members, s1, "b".hashCode) === true
      sched.isRunningFor(members, s4, "b".hashCode) === false
      sched.isRunningFor(members, s5, "b".hashCode) === false

      sched.isRunningFor(members, s1, "c".hashCode) === false
      sched.isRunningFor(members, s4, "c".hashCode) === true
      sched.isRunningFor(members, s5, "c".hashCode) === false

    }
    "no healthy" in {
      val sched = new ShardingCommander(null)

      val s1 = new ServiceInstance(Node("/node_00000001"), false, RemoteService(null, ServiceStatus.SICK, ServiceType.TEST_MODE))
      val s4 = new ServiceInstance(Node("/node_00000004"), false, RemoteService(null, ServiceStatus.SICK, ServiceType.TEST_MODE))
      val s5 = new ServiceInstance(Node("/node_00000005"), false, RemoteService(null, ServiceStatus.STOPPING, ServiceType.TEST_MODE))

      val members: Seq[ServiceInstance] = Seq(s1, s4, s5)

      sched.isRunningFor(members, s1, "a".hashCode) === false
      sched.isRunningFor(members, s4, "a".hashCode) === true
      sched.isRunningFor(members, s5, "a".hashCode) === false

      sched.isRunningFor(members, s1, "b".hashCode) === true
      sched.isRunningFor(members, s4, "b".hashCode) === false
      sched.isRunningFor(members, s5, "b".hashCode) === false

      sched.isRunningFor(members, s1, "c".hashCode) === false
      sched.isRunningFor(members, s4, "c".hashCode) === true
      sched.isRunningFor(members, s5, "c".hashCode) === false

    }
  }
}
