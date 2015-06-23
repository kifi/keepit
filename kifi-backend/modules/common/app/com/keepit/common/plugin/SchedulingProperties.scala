package com.keepit.common.plugin

import com.keepit.common.logging.Logging
import com.keepit.common.service.ServiceStatus
import com.keepit.common.service.ServiceStatus._
import com.keepit.common.zookeeper.{ ShardingCommander, ServiceInstance, ServiceDiscovery }

trait SchedulingProperties {
  def enabled: Boolean
  //bad name, can you think of anything else?
  //method returns true if schedualing is enabled and the instance is the leader
  def enabledOnlyForLeader: Boolean
  def enabledOnlyForOneMachine(taskName: String): Boolean
  def isRunnerFor(taskName: String): Boolean
}

class SchedulingPropertiesImpl(serviceDiscovery: ServiceDiscovery, shardingCommander: ShardingCommander, val enabled: Boolean = true) extends SchedulingProperties with Logging {
  def enabledOnlyForLeader: Boolean = enabled && serviceDiscovery.isLeader()
  def enabledOnlyForOneMachine(taskName: String): Boolean = enabled && shardingCommander.isRunnerFor(taskName.hashCode)
  def isRunnerFor(taskName: String): Boolean = if (serviceDiscovery.isCanary) false else shardingCommander.isRunnerFor(taskName.hashCode)
}

