package com.keepit.search.graph

import com.google.inject.Inject
import com.keepit.common.actor.ActorInstance
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.plugin.SchedulingProperties
import scala.concurrent.duration._
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.search.index.IndexerActor
import com.keepit.search.index.IndexerPlugin
import com.keepit.search.index.IndexerPluginImpl
import com.keepit.search.index.IndexManager
import com.keepit.search.graph.bookmark.URIGraphIndexer
import com.keepit.search.sharding.ShardedURIGraphIndexer

trait URIGraphPlugin extends IndexerPlugin[URIGraphIndexer]

class URIGraphPluginImpl @Inject() (
  actor: ActorInstance[URIGraphActor],
  indexer: ShardedURIGraphIndexer,
  serviceDiscovery: ServiceDiscovery,
  val scheduling: SchedulingProperties
) extends IndexerPluginImpl[URIGraphIndexer, URIGraphActor](indexer, actor, serviceDiscovery) with URIGraphPlugin

class URIGraphActor @Inject()(
  airbrake: AirbrakeNotifier,
  indexer: ShardedURIGraphIndexer
) extends IndexerActor[URIGraphIndexer](airbrake, indexer)
