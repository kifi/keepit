package com.keepit.search.index.message

import com.google.inject.Inject
import com.keepit.common.actor.ActorInstance
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.plugin.SchedulingProperties
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.search.index.BasicIndexerActor
import com.keepit.search.index.IndexerPlugin
import com.keepit.search.index.IndexerPluginImpl
import com.keepit.search.index.IndexManager

trait MessageIndexerPlugin extends IndexerPlugin[ThreadContent, MessageIndexer]

class MessageIndexerPluginImpl @Inject() (
  actor: ActorInstance[MessageIndexerActor],
  indexer: MessageIndexer,
  serviceDiscovery: ServiceDiscovery,
  val scheduling: SchedulingProperties) extends IndexerPluginImpl[ThreadContent, MessageIndexer, MessageIndexerActor](indexer, actor, serviceDiscovery) with MessageIndexerPlugin

class MessageIndexerActor @Inject() (
  airbrake: AirbrakeNotifier,
  indexer: MessageIndexer) extends BasicIndexerActor[ThreadContent, MessageIndexer](airbrake, indexer)
