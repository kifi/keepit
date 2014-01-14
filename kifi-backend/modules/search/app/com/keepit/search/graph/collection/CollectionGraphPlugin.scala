package com.keepit.search.graph.collection

import com.google.inject.Inject
import com.keepit.common.actor.ActorInstance
import com.keepit.common.db.Id
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.plugin.SchedulingProperties
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.model.NormalizedURI
import com.keepit.search.index.IndexerActor
import com.keepit.search.index.IndexerPlugin
import com.keepit.search.index.IndexManager
import com.keepit.search.sharding.ShardedCollectionIndexer
import com.keepit.search.sharding.ShardedIndexerPlugin
import com.keepit.search.sharding.ShardedIndexerPluginImpl

trait CollectionGraphPlugin extends ShardedIndexerPlugin[NormalizedURI, CollectionIndexer]

class CollectionGraphPluginImpl @Inject()(
  actor: ActorInstance[CollectionGraphActor],
  indexer: ShardedCollectionIndexer,
  serviceDiscovery: ServiceDiscovery,
  val scheduling: SchedulingProperties
) extends ShardedIndexerPluginImpl[NormalizedURI, CollectionIndexer, CollectionGraphActor](indexer, actor, serviceDiscovery) with CollectionGraphPlugin

class CollectionGraphActor @Inject()(
  airbrake: AirbrakeNotifier,
  indexer: ShardedCollectionIndexer
) extends IndexerActor[CollectionIndexer](airbrake, indexer)
