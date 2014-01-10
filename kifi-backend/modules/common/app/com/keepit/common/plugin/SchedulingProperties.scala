package com.keepit.common.plugin

import com.keepit.common.zookeeper._
import com.google.inject.{Inject, Singleton, ImplementedBy}

sealed trait SchedulingEnabled

object SchedulingEnabled {
  case object Always extends SchedulingEnabled
  case object Never extends SchedulingEnabled
  case object LeaderOnly extends SchedulingEnabled
}

@ImplementedBy(classOf[SchedulingPropertiesImpl])
trait SchedulingProperties {
  def allowScheduling: Boolean
  def neverAllowScheduling: Boolean = !allowScheduling
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
}
