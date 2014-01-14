package com.keepit.search.phrasedetector

import com.google.inject.Inject
import com.keepit.common.actor.ActorInstance
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.plugin.SchedulingProperties
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.search.index.IndexerActor
import com.keepit.search.index.IndexerPlugin
import com.keepit.search.index.IndexerPluginImpl
import com.keepit.search.index.IndexManager

trait PhraseIndexerPlugin extends IndexerPlugin[PhraseIndexer]

class PhraseIndexerPluginImpl @Inject() (
  actor: ActorInstance[PhraseIndexerActor],
  indexer: PhraseIndexer,
  serviceDiscovery: ServiceDiscovery,
  val scheduling: SchedulingProperties
) extends IndexerPluginImpl[PhraseIndexer, PhraseIndexerActor](indexer.asInstanceOf[IndexManager[PhraseIndexer]], actor, serviceDiscovery) with PhraseIndexerPlugin

class PhraseIndexerActor @Inject() (
  airbrake: AirbrakeNotifier,
  indexer: PhraseIndexer
) extends IndexerActor[PhraseIndexer](airbrake, indexer.asInstanceOf[IndexManager[PhraseIndexer]])

