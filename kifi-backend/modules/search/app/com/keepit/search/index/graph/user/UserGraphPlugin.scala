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
import com.keepit.model.{ UserConnection, NormalizedURI }

trait UserGraphPlugin extends IndexerPlugin[UserConnection, UserGraphIndexer]

class UserGraphPluginImpl @Inject() (
  actor: ActorInstance[UserGraphActor],
  indexer: UserGraphIndexer,
  serviceDiscovery: ServiceDiscovery,
  val scheduling: SchedulingProperties) extends IndexerPluginImpl[UserConnection, UserGraphIndexer, UserGraphActor](indexer, actor, serviceDiscovery) with UserGraphPlugin

class UserGraphActor @Inject() (
  airbrake: AirbrakeNotifier,
  indexer: UserGraphIndexer) extends BasicIndexerActor[UserConnection, UserGraphIndexer](airbrake, indexer)
