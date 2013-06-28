package com.keepit.dev

import net.codingwell.scalaguice.ScalaModule
import com.keepit.common.logging.Logging
import com.keepit.common.analytics._
import com.keepit.inject.AppScoped
import com.google.inject.{Inject, Provider, Singleton, Provides}
import com.keepit.common.plugin.{SchedulingProperties, SchedulingEnabled}
import play.api.Play._
import com.keepit.common.zookeeper.{Node, ServiceDiscovery}
import com.keepit.common.db.slick.Database
import com.keepit.model.{SliderHistoryTrackerImplModule, NormalizedURIRepo, UserRepo}
import com.keepit.search.{SearchServiceClientImplModule, SearchServiceClient}
import com.keepit.common.time.Clock
import com.keepit.common.service.{IpAddress, FortyTwoServices}
import com.google.common.io.Files
import com.keepit.common.amazon.AmazonInstanceInfo
import com.keepit.common.mail._
import com.keepit.common.actor.ActorFactory
import com.keepit.classify.{DevDomainTagImporterModule, DomainTagImportSettings}
import scala.Some
import com.keepit.common.zookeeper.Node
import com.keepit.common.amazon.AmazonInstanceId
import com.keepit.learning.topicmodel._
import com.keepit.shoebox._
import com.keepit.module.{DevDiscoveryModule, DevActorSystemModule}
import com.keepit.common.cache.{HashMapMemoryCacheModule, ShoeboxCacheModule, EhCacheCacheModule}
import com.keepit.realtime.ShoeboxWebSocketModule
import com.keepit.common.social.SocialGraphImplModule
import com.keepit.scraper.ScraperImplModule
import com.keepit.common.db.SlickModule
import com.keepit.social.ShoeboxSecureSocialModule
import com.keepit.common.db.SlickModule
import com.keepit.module.DevActorSystemModule
import com.keepit.common.social.SocialGraphImplModule
import com.keepit.model.SliderHistoryTrackerImplModule
import com.keepit.learning.topicmodel.DevTopicModelModule
import com.keepit.realtime.ShoeboxWebSocketModule
import com.keepit.scraper.ScraperImplModule
import com.keepit.search.SearchServiceClientImplModule
import com.keepit.shoebox.ShoeboxClickHistoryModule
import com.keepit.shoebox.ShoeboxDbInfo
import com.keepit.common.cache.ShoeboxCacheModule
import com.keepit.classify.DevDomainTagImporterModule
import com.keepit.common.cache.HashMapMemoryCacheModule
import com.keepit.module.DevDiscoveryModule
import com.keepit.shoebox.UserIndexModule
import com.keepit.social.ShoeboxSecureSocialModule
import com.keepit.common.analytics.DevAnalyticsModule
import com.keepit.common.db.SlickModule
import com.keepit.module.DevActorSystemModule
import com.keepit.shoebox.ShoeboxBrowsingHistoryModule
import com.keepit.common.social.SocialGraphImplModule
import com.keepit.model.SliderHistoryTrackerImplModule
import com.keepit.learning.topicmodel.DevTopicModelModule
import com.keepit.realtime.ShoeboxWebSocketModule
import com.keepit.scraper.ScraperImplModule
import com.keepit.search.SearchServiceClientImplModule
import com.keepit.shoebox.ShoeboxClickHistoryModule
import com.keepit.shoebox.ShoeboxDbInfo
import com.keepit.common.cache.ShoeboxCacheModule
import com.keepit.classify.DevDomainTagImporterModule
import com.keepit.common.cache.HashMapMemoryCacheModule
import com.keepit.module.DevDiscoveryModule
import com.keepit.shoebox.UserIndexModule
import com.keepit.social.ShoeboxSecureSocialModule
import com.keepit.common.analytics.DevAnalyticsModule
import com.keepit.common.crypto.ShoeboxCryptoModule
import com.keepit.common.store.ShoeboxS3Module

case class ShoeboxDevModule() extends ShoeboxModule(


  // Common Functional Modules
  cacheModule = ShoeboxCacheModule(HashMapMemoryCacheModule()),
  secureSocialModule = ShoeboxSecureSocialModule(),
  searchServiceClientModule = SearchServiceClientImplModule(),
  clickHistoryModule = ShoeboxClickHistoryModule(),
  browsingHistoryModule = ShoeboxBrowsingHistoryModule(),
  mailModule = ProdMailModule(),
  cryptoModule = ShoeboxCryptoModule(),
  s3Module = ShoeboxS3Module(),
  actorSystemModule = DevActorSystemModule(),
  discoveryModule = DevDiscoveryModule(),

  // Shoebox Functional Modules
  slickModule = SlickModule(ShoeboxDbInfo()),
  scraperModule = ScraperImplModule(),
  socialGraphModule = SocialGraphImplModule(),
  analyticsModule = DevAnalyticsModule(),
  webSocketModule = ShoeboxWebSocketModule(),
  topicModelModule = DevTopicModelModule(),
  domainTagImporterModule = DevDomainTagImporterModule(),
  sliderHistoryTrackerModule = SliderHistoryTrackerImplModule(),
  userIndexModule = UserIndexModule()
)
