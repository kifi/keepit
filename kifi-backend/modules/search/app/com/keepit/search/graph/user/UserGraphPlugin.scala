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

trait UserGraphPlugin extends IndexerPlugin[UserGraphIndexer]

class UserGraphPluginImpl @Inject()(
  actor: ActorInstance[UserGraphActor],
  indexer: UserGraphIndexer,
  serviceDiscovery: ServiceDiscovery,
  val scheduling: SchedulingProperties
) extends IndexerPluginImpl[UserGraphIndexer, UserGraphActor](indexer.asInstanceOf[IndexManager[UserGraphIndexer]], actor, serviceDiscovery) with UserGraphPlugin

class UserGraphActor @Inject()(
  airbrake: AirbrakeNotifier,
  indexer: UserGraphIndexer
) extends IndexerActor[UserGraphIndexer](airbrake, indexer.asInstanceOf[IndexManager[UserGraphIndexer]])
