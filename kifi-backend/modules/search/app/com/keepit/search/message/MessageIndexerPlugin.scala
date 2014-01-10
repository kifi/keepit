package com.keepit.search.message

import com.google.inject.Inject
import com.keepit.common.actor.ActorInstance
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.search.IndexerActor
import com.keepit.search.IndexerPlugin
import com.keepit.search.IndexerPluginImpl
import com.keepit.search.IndexManager

trait MessageIndexerPlugin extends IndexerPlugin[MessageIndexer]

class MessageIndexerPluginImpl @Inject() (
  actor: ActorInstance[MessageIndexerActor],
  indexer: MessageIndexer,
  serviceDiscovery: ServiceDiscovery
) extends IndexerPluginImpl[MessageIndexer, MessageIndexerActor](indexer.asInstanceOf[IndexManager[MessageIndexer]], actor, serviceDiscovery) with MessageIndexerPlugin

class MessageIndexerActor @Inject() (
  airbrake: AirbrakeNotifier,
  indexer: MessageIndexer
) extends IndexerActor[MessageIndexer](airbrake, indexer.asInstanceOf[IndexManager[MessageIndexer]])
