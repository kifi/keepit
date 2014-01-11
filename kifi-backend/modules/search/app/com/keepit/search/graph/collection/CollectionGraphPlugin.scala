package com.keepit.search.graph.collection

import com.google.inject.Inject
import com.keepit.common.actor.ActorInstance
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.plugin.SchedulingProperties
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.search.IndexerActor
import com.keepit.search.IndexerPlugin
import com.keepit.search.IndexerPluginImpl
import com.keepit.search.IndexManager

trait CollectionGraphPlugin extends IndexerPlugin[CollectionIndexer]

class CollectionGraphPluginImpl @Inject()(
  actor: ActorInstance[CollectionGraphActor],
  indexer: CollectionIndexer,
  serviceDiscovery: ServiceDiscovery,
  val scheduling: SchedulingProperties
) extends IndexerPluginImpl[CollectionIndexer, CollectionGraphActor](indexer.asInstanceOf[IndexManager[CollectionIndexer]], actor, serviceDiscovery) with CollectionGraphPlugin

class CollectionGraphActor @Inject()(
  airbrake: AirbrakeNotifier,
  collectionIndexer: CollectionIndexer
) extends IndexerActor[CollectionIndexer](airbrake, collectionIndexer.asInstanceOf[IndexManager[CollectionIndexer]])
