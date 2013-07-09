package com.keepit.dev

import com.keepit.common.mail._
import com.keepit.shoebox._
import com.keepit.shoebox.ShoeboxBrowsingHistoryModule
import com.keepit.common.social.ProdSocialGraphModule
import com.keepit.model.ProdSliderHistoryTrackerModule
import com.keepit.learning.topicmodel.DevTopicModelModule
import com.keepit.realtime.ShoeboxWebSocketModule
import com.keepit.scraper.ScraperImplModule
import com.keepit.search.ProdSearchServiceClientModule
import com.keepit.shoebox.ShoeboxClickHistoryModule
import com.keepit.common.cache.ShoeboxCacheModule
import com.keepit.classify.DevDomainTagImporterModule
import com.keepit.common.cache.HashMapMemoryCacheModule
import com.keepit.shoebox.UserIndexModule
import com.keepit.social.ProdShoeboxSecureSocialModule
import com.keepit.common.analytics.DevAnalyticsModule
import com.keepit.common.crypto.ShoeboxCryptoModule
import com.keepit.common.store.ShoeboxDevStoreModule
import com.keepit.common.healthcheck.ProdHealthCheckModule
import com.keepit.common.net.ProdHttpClientModule
import com.keepit.inject.ProdFortyTwoModule
import com.keepit.common.actor.DevActorSystemModule
import com.keepit.common.zookeeper.DevDiscoveryModule
import com.keepit.common.db.slick.ShoeboxSlickModule

case class ShoeboxDevModule() extends ShoeboxModule(


  // Common Functional Modules
  fortyTwoModule = ProdFortyTwoModule(),
  cacheModule = ShoeboxCacheModule(HashMapMemoryCacheModule()),
  secureSocialModule = ProdShoeboxSecureSocialModule(),
  searchServiceClientModule = ProdSearchServiceClientModule(),
  clickHistoryModule = ShoeboxClickHistoryModule(),
  browsingHistoryModule = ShoeboxBrowsingHistoryModule(),
  mailModule = DevMailModule(),
  cryptoModule = ShoeboxCryptoModule(),
  storeModule = ShoeboxDevStoreModule(),
  actorSystemModule = DevActorSystemModule(),
  discoveryModule = DevDiscoveryModule(),
  healthCheckModule = ProdHealthCheckModule(),
  httpClientModule = ProdHttpClientModule(),
  shoeboxServiceClientModule = ProdShoeboxServiceClientModule(),

  // Shoebox Functional Modules
  slickModule = ShoeboxSlickModule(),
  scraperModule = ScraperImplModule(),
  socialGraphModule = ProdSocialGraphModule(),
  analyticsModule = DevAnalyticsModule(),
  webSocketModule = ShoeboxWebSocketModule(),
  topicModelModule = DevTopicModelModule(),
  domainTagImporterModule = DevDomainTagImporterModule(),
  sliderHistoryTrackerModule = ProdSliderHistoryTrackerModule(),
  userIndexModule = UserIndexModule()
)
