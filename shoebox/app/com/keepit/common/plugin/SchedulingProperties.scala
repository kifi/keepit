package com.keepit.common.plugin

import com.keepit.common.zookeeper._
import com.google.inject.{Inject, Singleton, ImplementedBy}

@ImplementedBy(classOf[SchedulingPropertiesImpl])
trait SchedulingProperties  {
  def allowSchecualing: Boolean
}

class SchedulingPropertiesImpl @Inject() (
  serviceDiscovery: ServiceDiscovery,
  schedulingEnabled: SchedulingEnabled)
    extends SchedulingProperties {
  def allowSchecualing = schedulingEnabled match {
    case SchedulingEnabled.Always => true
    case SchedulingEnabled.Never => false
    case SchedulingEnabled.LeaderOnly => serviceDiscovery.isLeader()
  }
}
