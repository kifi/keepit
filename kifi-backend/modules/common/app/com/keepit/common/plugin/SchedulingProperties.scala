package com.keepit.common.plugin

import com.keepit.common.zookeeper._
import com.google.inject.{Inject, Singleton, ImplementedBy}

@ImplementedBy(classOf[SchedulingPropertiesImpl])
trait SchedulingProperties {
  def allowScheduling: Boolean
  def neverAllowScheduling: Boolean = allowScheduling
}

@Singleton
class SchedulingPropertiesImpl @Inject() (
  serviceDiscovery: ServiceDiscovery,
  schedulingEnabled: SchedulingEnabled)
    extends SchedulingProperties {

  override def neverAllowScheduling = (schedulingEnabled == SchedulingEnabled.Never)

  def allowScheduling = schedulingEnabled match {
    case SchedulingEnabled.Always => true
    case SchedulingEnabled.Never => false
    case SchedulingEnabled.LeaderOnly => serviceDiscovery.isLeader()
  }
}

object SchedulingProperties {
  val AlwaysEnabled = new SchedulingProperties {
    def allowScheduling: Boolean = true
    override def neverAllowScheduling: Boolean = false
  }
  val NeverEnabled = new SchedulingProperties {
    def allowScheduling: Boolean = false
    override def neverAllowScheduling: Boolean = true
  }
}
