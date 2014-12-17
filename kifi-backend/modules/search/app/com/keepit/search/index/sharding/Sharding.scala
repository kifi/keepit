package com.keepit.search.index.sharding

import com.keepit.common.db.Id
import com.keepit.model.{ User, NormalizedURI }
import com.keepit.common.zookeeper.ServiceInstance
import com.keepit.search.{ DistributedSearchServiceClient, SearchServiceClient }
import com.keepit.common.logging.Logging

trait Sharding { self: Logging =>

  val searchClient: DistributedSearchServiceClient

  def distributionPlanRemoteOnly(userId: Id[User], maxShardsPerInstance: Int = Int.MaxValue): (Set[Shard[NormalizedURI]], Seq[(ServiceInstance, Set[Shard[NormalizedURI]])]) = {
    // NOTE: Remote-only may actually loop back to the current instance. We don't check it for now.
    (Set.empty[Shard[NormalizedURI]], searchClient.distPlanRemoteOnly(userId, maxShardsPerInstance))
  }

  def distributionPlan(userId: Id[User], shards: ActiveShards, maxShardsPerInstance: Int = Int.MaxValue): (Set[Shard[NormalizedURI]], Seq[(ServiceInstance, Set[Shard[NormalizedURI]])]) = {
    try {
      if (shards.local.size < maxShardsPerInstance) {
        // The current instance processes a subset of local shards. Remaining shards are processed by remote instances.
        // It is possible that one of the remote call loops back to the current instance. We don't check it for now.
        val local = shards.local.take(maxShardsPerInstance)
        val remote = shards.all -- local
        (local, if (remote.isEmpty) Seq.empty else searchClient.distPlan(userId, remote, maxShardsPerInstance))
      } else {
        (shards.local, if (shards.remote.isEmpty) Seq.empty else searchClient.distPlan(userId, shards.remote, maxShardsPerInstance))
      }
    } catch {
      case e: DispatchFailedException =>
        log.info("dispatch failed. resorting to remote-only plan.")
        // fallback to no local plan. This may happen when a new sharding scheme is being deployed
        distributionPlanRemoteOnly(userId, maxShardsPerInstance)
      case t: Throwable => throw t
    }
  }

}
