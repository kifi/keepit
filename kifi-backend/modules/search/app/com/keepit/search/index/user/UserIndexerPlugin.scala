package com.keepit.search.index.user

import com.google.inject.Inject
import com.keepit.common.actor.ActorInstance
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.plugin.SchedulingProperties
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.search.index.BasicIndexerActor
import com.keepit.search.index.IndexerPlugin
import com.keepit.search.index.IndexerPluginImpl
import com.keepit.search.index.IndexManager
import com.keepit.model.User

trait UserIndexerPlugin extends IndexerPlugin[User, UserIndexer]

class UserIndexerPluginImpl @Inject() (
  actor: ActorInstance[UserIndexerActor],
  indexer: UserIndexer,
  serviceDiscovery: ServiceDiscovery,
  val scheduling: SchedulingProperties) extends IndexerPluginImpl[User, UserIndexer, UserIndexerActor](indexer, actor, serviceDiscovery) with UserIndexerPlugin

class UserIndexerActor @Inject() (
  airbrake: AirbrakeNotifier,
  indexer: UserIndexer) extends BasicIndexerActor[User, UserIndexer](airbrake, indexer)

