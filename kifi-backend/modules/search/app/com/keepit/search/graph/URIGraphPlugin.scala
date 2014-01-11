package com.keepit.search.graph

import com.google.inject.Inject
import com.keepit.common.actor.ActorInstance
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.plugin.SchedulingProperties
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.search.IndexerActor
import com.keepit.search.IndexerPlugin
import com.keepit.search.IndexerPluginImpl
import com.keepit.search.IndexManager
import com.keepit.search.graph.bookmark.URIGraphIndexer


trait URIGraphPlugin extends IndexerPlugin[URIGraphIndexer]

class URIGraphPluginImpl @Inject() (
  actor: ActorInstance[URIGraphActor],
  indexer: URIGraphIndexer,
  serviceDiscovery: ServiceDiscovery,
  val scheduling: SchedulingProperties
) extends IndexerPluginImpl[URIGraphIndexer, URIGraphActor](indexer.asInstanceOf[IndexManager[URIGraphIndexer]] , actor, serviceDiscovery) with URIGraphPlugin

class URIGraphActor @Inject()(
  airbrake: AirbrakeNotifier,
  uriGraphIndexer: URIGraphIndexer
) extends IndexerActor[URIGraphIndexer](airbrake, uriGraphIndexer.asInstanceOf[IndexManager[URIGraphIndexer]])
