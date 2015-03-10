package com.keepit.common.plugin

import com.keepit.common.logging.Logging
import com.keepit.common.service.ServiceStatus
import com.keepit.common.service.ServiceStatus._
import com.keepit.common.zookeeper.{ ServiceInstance, ServiceDiscovery }

trait SchedulingProperties {
  def enabled: Boolean
  //bad name, can you think of anything else?
  //method returns true if schedualing is enabled and the instance is the leader
  def enabledOnlyForLeader: Boolean
  def enabledOnlyForOneMachine(taskName: String): Boolean
  def isRunnerFor(taskName: String): Boolean
}

class SchedulingPropertiesImpl(serviceDiscovery: ServiceDiscovery, val enabled: Boolean = true) extends SchedulingProperties with Logging {
  def enabledOnlyForLeader: Boolean = enabled && serviceDiscovery.isLeader()
  def enabledOnlyForOneMachine(taskName: String): Boolean = enabled && isRunnerFor(taskName)

  private def isRunningFor(taskName: String, me: ServiceInstance, members: Seq[ServiceInstance]): Boolean = {
    val index = (taskName.hashCode() & 0x7FFFFFFF) % members.size
    members(index) == me
  }

  def isRunningFor(members: Seq[ServiceInstance], me: ServiceInstance, taskName: String): Boolean = {
    val offline = members.filter(_.remoteService.status == ServiceStatus.OFFLINE)
    if (offline.isEmpty) {
      println("if there's no offline service, consider all UP cluster")
      val healthy = members.filter(_.isHealthy)
      if (healthy.nonEmpty) {
        isRunningFor(taskName, me, healthy)
      } else {
        val alive = Seq(UP, SICK, SELFCHECK_FAIL)
        val stillAlive = members.filter(m => alive.contains(m.remoteService.status))
        isRunningFor(taskName, me, stillAlive)
      }
    } else {
      if (offline.contains(me)) {
        //if there's at least one offline service and I'm offline as well, use only offline services for the check
        isRunningFor(taskName, me, offline)
      } else {
        //if i'm not an offline services and at least one like this exist in my cluster, don't even consider me
        false
      }
    }
  }

  def isRunnerFor(taskName: String): Boolean = if (serviceDiscovery.isCanary) false else {
    serviceDiscovery.thisInstance exists { me =>
      isRunningFor(serviceDiscovery.instancesInCluster, me, taskName)
    }
  }
}

