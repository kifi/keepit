package com.keepit.dev

import com.keepit.common.mail._
import com.keepit.shoebox._
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
import com.keepit.module.LocalDiscoveryModule
import com.keepit.shoebox.UserIndexModule
import com.keepit.social.ShoeboxSecureSocialModule
import com.keepit.common.analytics.DevAnalyticsModule
import com.keepit.common.crypto.ShoeboxCryptoModule
import com.keepit.common.store.ShoeboxS3Module
import com.keepit.common.service.ServiceType

case class ShoeboxDevModule() extends ShoeboxModule(


  // Common Functional Modules
  cacheModule = ShoeboxCacheModule(HashMapMemoryCacheModule()),
  secureSocialModule = ShoeboxSecureSocialModule(),
  searchServiceClientModule = SearchServiceClientImplModule(),
  clickHistoryModule = ShoeboxClickHistoryModule(),
  browsingHistoryModule = ShoeboxBrowsingHistoryModule(),
  mailModule = DevMailModule(),
  cryptoModule = ShoeboxCryptoModule(),
  s3Module = ShoeboxS3Module(),
  actorSystemModule = DevActorSystemModule(),
  discoveryModule = LocalDiscoveryModule(ServiceType.DEV_MODE),

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
