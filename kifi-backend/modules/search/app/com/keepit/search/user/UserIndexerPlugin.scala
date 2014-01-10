package com.keepit.search.user

import com.google.inject.Inject
import com.keepit.common.actor.ActorInstance
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.plugin.SchedulingProperties
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.search.index.IndexerActor
import com.keepit.search.index.IndexerPlugin
import com.keepit.search.index.IndexerPluginImpl
import com.keepit.search.index.IndexManager

trait UserIndexerPlugin extends IndexerPlugin[UserIndexer]

class UserIndexerPluginImpl @Inject() (
  actor: ActorInstance[UserIndexerActor],
  indexer: UserIndexer,
  serviceDiscovery: ServiceDiscovery,
  val scheduling: SchedulingProperties
) extends IndexerPluginImpl[UserIndexer, UserIndexerActor](indexer.asInstanceOf[IndexManager[UserIndexer]], actor, serviceDiscovery) with UserIndexerPlugin

class UserIndexerActor @Inject() (
  airbrake: AirbrakeNotifier,
  indexer: UserIndexer
) extends IndexerActor[UserIndexer](airbrake, indexer.asInstanceOf[IndexManager[UserIndexer]])

