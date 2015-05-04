package com.keepit.search.index.article

import com.google.inject.Inject
import com.keepit.common.actor.ActorInstance
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.plugin.SchedulingProperties
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.model.NormalizedURI
import com.keepit.search.index.BasicIndexerActor
import com.keepit.search.index.sharding.DeprecatedShardedArticleIndexer
import com.keepit.search.index.sharding.ShardedIndexerPlugin
import com.keepit.search.index.sharding.ShardedIndexerPluginImpl

trait DeprecatedArticleIndexerPlugin extends ShardedIndexerPlugin[NormalizedURI, NormalizedURI, DeprecatedArticleIndexer]

class DeprecatedArticleIndexerPluginImpl @Inject() (
  actor: ActorInstance[DeprecatedArticleIndexerActor],
  indexer: DeprecatedShardedArticleIndexer,
  serviceDiscovery: ServiceDiscovery,
  val scheduling: SchedulingProperties) extends ShardedIndexerPluginImpl[NormalizedURI, NormalizedURI, DeprecatedArticleIndexer, DeprecatedArticleIndexerActor](indexer, actor, serviceDiscovery) with DeprecatedArticleIndexerPlugin

class DeprecatedArticleIndexerActor @Inject() (
  airbrake: AirbrakeNotifier,
  indexer: DeprecatedShardedArticleIndexer) extends BasicIndexerActor[NormalizedURI, DeprecatedArticleIndexer](airbrake, indexer)
