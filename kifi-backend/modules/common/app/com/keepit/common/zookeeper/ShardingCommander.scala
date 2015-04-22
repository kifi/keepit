package com.keepit.common.zookeeper

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.service.ServiceStatus
import com.keepit.common.service.ServiceStatus.{ SELFCHECK_FAIL, SICK, UP }
import com.keepit.model.User

class ShardingCommander @Inject() (
    serviceDiscovery: ServiceDiscovery) {

  private def isInShard(id: Int, me: ServiceInstance, members: Seq[ServiceInstance]): Boolean = {
    val index = (id & 0x7FFFFFFF) % members.size
    members(index) == me
  }

  def isRunningFor(members: Seq[ServiceInstance], me: ServiceInstance, id: Int): Boolean = {
    val offline = members.filter(_.remoteService.status == ServiceStatus.OFFLINE)
    if (offline.isEmpty) {
      val healthy = members.filter(_.isHealthy)
      if (healthy.nonEmpty) {
        isInShard(id, me, healthy)
      } else {
        val alive = Seq(UP, SICK, SELFCHECK_FAIL)
        val stillAlive = members.filter(m => alive.contains(m.remoteService.status))
        isInShard(id, me, stillAlive)
      }
    } else {
      if (offline.contains(me)) {
        //if there's at least one offline service and I'm offline as well, use only offline services for the check
        isInShard(id, me, offline)
      } else {
        //if i'm not an offline services and at least one like this exist in my cluster, don't even consider me
        false
      }
    }
  }

  def isRunnerFor(id: Int): Boolean = if (serviceDiscovery.isCanary) false else {
    serviceDiscovery.thisInstance exists { me =>
      isRunningFor(serviceDiscovery.instancesInCluster, me, id)
    }
  }

}
