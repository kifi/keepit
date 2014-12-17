package com.keepit.search.index.sharding

import com.keepit.common.actor.ActorInstance
import com.keepit.common.db.Id
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.search.index.Indexer
import com.keepit.search.index.BasicIndexerActor
import com.keepit.search.index.IndexerPlugin
import com.keepit.search.index.IndexerPluginImpl

trait ShardedIndexerPlugin[K, S, I <: Indexer[_, S, I]] extends IndexerPlugin[S, I] {
  def getIndexerFor(id: Id[K]): I
}

abstract class ShardedIndexerPluginImpl[K, S, I <: Indexer[_, S, I], A <: BasicIndexerActor[S, I]](
    indexer: ShardedIndexer[K, S, I],
    actor: ActorInstance[A],
    serviceDiscovery: ServiceDiscovery) extends IndexerPluginImpl[S, I, A](indexer, actor, serviceDiscovery) {

  def getIndexerFor(id: Id[K]): I = indexer.getIndexerFor(id)
}
