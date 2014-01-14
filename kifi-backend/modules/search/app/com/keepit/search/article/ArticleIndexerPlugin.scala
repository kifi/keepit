package com.keepit.search.article

import com.google.inject.Inject
import com.keepit.common.actor.ActorInstance
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.plugin.SchedulingProperties
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.search.index.IndexerActor
import com.keepit.search.index.IndexerPlugin
import com.keepit.search.index.IndexerPluginImpl
import com.keepit.search.index.IndexManager
import com.keepit.search.sharding.ShardedArticleIndexer

trait ArticleIndexerPlugin extends IndexerPlugin[ArticleIndexer]

class ArticleIndexerPluginImpl @Inject() (
  actor: ActorInstance[ArticleIndexerActor],
  indexer: ShardedArticleIndexer,
  serviceDiscovery: ServiceDiscovery,
  val scheduling: SchedulingProperties
) extends IndexerPluginImpl[ArticleIndexer, ArticleIndexerActor](indexer, actor, serviceDiscovery) with ArticleIndexerPlugin

class ArticleIndexerActor @Inject() (
  airbrake: AirbrakeNotifier,
  indexer: ShardedArticleIndexer
) extends IndexerActor[ArticleIndexer](airbrake, indexer)
