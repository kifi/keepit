package com.keepit.search.graph

import com.google.inject.Inject
import com.keepit.common.actor.ActorInstance
import com.keepit.common.db.Id
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.plugin.SchedulingProperties
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.model.{Bookmark, NormalizedURI}
import com.keepit.search.index.IndexerActor
import com.keepit.search.index.IndexerPlugin
import com.keepit.search.index.IndexManager
import com.keepit.search.graph.bookmark.URIGraphIndexer
import com.keepit.search.sharding.ShardedURIGraphIndexer
import com.keepit.search.sharding.ShardedIndexerPlugin
import com.keepit.search.sharding.ShardedIndexerPluginImpl
import scala.concurrent.duration._

trait URIGraphPlugin extends ShardedIndexerPlugin[NormalizedURI, Bookmark, URIGraphIndexer]

class URIGraphPluginImpl @Inject() (
  actor: ActorInstance[URIGraphActor],
  indexer: ShardedURIGraphIndexer,
  serviceDiscovery: ServiceDiscovery,
  val scheduling: SchedulingProperties
) extends ShardedIndexerPluginImpl[NormalizedURI, Bookmark, URIGraphIndexer, URIGraphActor](indexer, actor, serviceDiscovery) with URIGraphPlugin

class URIGraphActor @Inject()(
  airbrake: AirbrakeNotifier,
  indexer: ShardedURIGraphIndexer
) extends IndexerActor[Bookmark, URIGraphIndexer](airbrake, indexer)
