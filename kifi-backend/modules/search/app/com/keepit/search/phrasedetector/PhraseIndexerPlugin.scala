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
import com.keepit.model.Phrase

trait PhraseIndexerPlugin extends IndexerPlugin[Phrase, PhraseIndexer]

class PhraseIndexerPluginImpl @Inject() (
  actor: ActorInstance[PhraseIndexerActor],
  indexer: PhraseIndexer,
  serviceDiscovery: ServiceDiscovery,
  val scheduling: SchedulingProperties
) extends IndexerPluginImpl[Phrase, PhraseIndexer, PhraseIndexerActor](indexer, actor, serviceDiscovery) with PhraseIndexerPlugin

class PhraseIndexerActor @Inject() (
  airbrake: AirbrakeNotifier,
  indexer: PhraseIndexer
) extends IndexerActor[Phrase, PhraseIndexer](airbrake, indexer)

