package com.keepit.search.index.graph.user

import com.google.inject.Inject
import com.keepit.common.actor.ActorInstance
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.plugin.SchedulingProperties
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.search.index.BasicIndexerActor
import com.keepit.search.index.IndexerPlugin
import com.keepit.search.index.IndexerPluginImpl
import com.keepit.search.index.IndexManager
import com.keepit.model.SearchFriend

trait SearchFriendGraphPlugin extends IndexerPlugin[SearchFriend, SearchFriendIndexer]

class SearchFriendGraphPluginImpl @Inject() (
  actor: ActorInstance[SearchFriendIndexerActor],
  indexer: SearchFriendIndexer,
  serviceDiscovery: ServiceDiscovery,
  val scheduling: SchedulingProperties) extends IndexerPluginImpl[SearchFriend, SearchFriendIndexer, SearchFriendIndexerActor](indexer, actor, serviceDiscovery) with SearchFriendGraphPlugin

class SearchFriendIndexerActor @Inject() (
  airbrake: AirbrakeNotifier,
  indexer: SearchFriendIndexer) extends BasicIndexerActor[SearchFriend, SearchFriendIndexer](airbrake, indexer)
