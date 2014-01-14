package com.keepit.search.sharding

import com.keepit.common.actor.ActorInstance
import com.keepit.common.db.Id
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.search.index.Indexer
import com.keepit.search.index.IndexerActor
import com.keepit.search.index.IndexerPlugin
import com.keepit.search.index.IndexerPluginImpl

trait ShardedIndexerPlugin[K, T <: Indexer[_]] extends IndexerPlugin[T] {
  def getIndexerFor(id: Id[K]): T
}

abstract class ShardedIndexerPluginImpl[K, T <: Indexer[_], A <: IndexerActor[T]](
  indexer: ShardedIndexer[K, T],
  actor: ActorInstance[A],
  serviceDiscovery: ServiceDiscovery
) extends IndexerPluginImpl[T, A](indexer, actor, serviceDiscovery) {

  def getIndexerFor(id: Id[K]): T = indexer.getIndexerFor(id)
}
