package com.keepit.search.index.article

import com.google.inject.Inject
import com.keepit.common.actor.ActorInstance
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.plugin.SchedulingProperties
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.model.NormalizedURI
import com.keepit.search.index.BasicIndexerActor
import com.keepit.search.index.sharding.ShardedArticleIndexer
import com.keepit.search.index.sharding.ShardedIndexerPlugin
import com.keepit.search.index.sharding.ShardedIndexerPluginImpl

trait ArticleIndexerPlugin extends ShardedIndexerPlugin[NormalizedURI, NormalizedURI, ArticleIndexer]

class ArticleIndexerPluginImpl @Inject() (
  actor: ActorInstance[ArticleIndexerActor],
  indexer: ShardedArticleIndexer,
  serviceDiscovery: ServiceDiscovery,
  val scheduling: SchedulingProperties) extends ShardedIndexerPluginImpl[NormalizedURI, NormalizedURI, ArticleIndexer, ArticleIndexerActor](indexer, actor, serviceDiscovery) with ArticleIndexerPlugin

class ArticleIndexerActor @Inject() (
  airbrake: AirbrakeNotifier,
  indexer: ShardedArticleIndexer) extends BasicIndexerActor[NormalizedURI, ArticleIndexer](airbrake, indexer)
