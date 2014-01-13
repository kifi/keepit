package com.keepit.search.graph.collection

import com.google.inject.Inject
import com.keepit.common.actor.ActorInstance
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.plugin.SchedulingProperties
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.search.index.IndexerActor
import com.keepit.search.index.IndexerPlugin
import com.keepit.search.index.IndexerPluginImpl
import com.keepit.search.index.IndexManager
import com.keepit.search.sharding.ShardedCollectionIndexer

trait CollectionGraphPlugin extends IndexerPlugin[CollectionIndexer]

class CollectionGraphPluginImpl @Inject()(
  actor: ActorInstance[CollectionGraphActor],
  indexer: ShardedCollectionIndexer,
  serviceDiscovery: ServiceDiscovery,
  val scheduling: SchedulingProperties
) extends IndexerPluginImpl[CollectionIndexer, CollectionGraphActor](indexer, actor, serviceDiscovery) with CollectionGraphPlugin

class CollectionGraphActor @Inject()(
  airbrake: AirbrakeNotifier,
  indexer: ShardedCollectionIndexer
) extends IndexerActor[CollectionIndexer](airbrake, indexer)
