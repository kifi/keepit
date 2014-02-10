package com.keepit.search.graph.user

import com.google.inject.Inject
import com.keepit.common.actor.ActorInstance
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.plugin.SchedulingProperties
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.search.index.IndexerActor
import com.keepit.search.index.IndexerPlugin
import com.keepit.search.index.IndexerPluginImpl
import com.keepit.search.index.IndexManager

trait SearchFriendGraphPlugin extends IndexerPlugin[SearchFriendIndexer]

class SearchFriendGraphPluginImpl @Inject()(
  actor: ActorInstance[SearchFriendIndexerActor],
  indexer: SearchFriendIndexer,
  serviceDiscovery: ServiceDiscovery,
  val scheduling: SchedulingProperties
) extends IndexerPluginImpl[SearchFriendIndexer, SearchFriendIndexerActor](indexer.asInstanceOf[IndexManager[SearchFriendIndexer]], actor, serviceDiscovery) with SearchFriendGraphPlugin

class SearchFriendIndexerActor @Inject()(
  airbrake: AirbrakeNotifier,
  indexer: SearchFriendIndexer
) extends IndexerActor[SearchFriendIndexer](airbrake, indexer.asInstanceOf[IndexManager[SearchFriendIndexer]])
