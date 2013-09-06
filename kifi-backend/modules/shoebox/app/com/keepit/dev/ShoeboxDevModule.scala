package com.keepit.dev

import com.keepit.common.mail._
import com.keepit.shoebox._
import com.keepit.shoebox.ShoeboxBrowsingHistoryModule
import com.keepit.common.social.ProdSocialGraphModule
import com.keepit.model.ProdSliderHistoryTrackerModule
import com.keepit.learning.topicmodel.DevTopicModelModule
import com.keepit.realtime.ShoeboxWebSocketModule
import com.keepit.scraper.ScraperImplModule
import com.keepit.shoebox.ShoeboxClickHistoryModule
import com.keepit.common.cache.ShoeboxCacheModule
import com.keepit.classify.DevDomainTagImporterModule
import com.keepit.common.cache.HashMapMemoryCacheModule
import com.keepit.shoebox.UserIndexModule
import com.keepit.social.ProdShoeboxSecureSocialModule
import com.keepit.common.analytics.DevAnalyticsModule
import com.keepit.common.store.ShoeboxDevStoreModule
import com.keepit.inject.CommonDevModule
import com.keepit.shoebox.ShoeboxSlickModule
import com.keepit.integrity.DataIntegrityModule
import com.keepit.reports.GeckoboardModule

case class ShoeboxDevModule() extends ShoeboxModule(
  secureSocialModule = ProdShoeboxSecureSocialModule(),
  mailModule = DevMailModule(),
  storeModule = ShoeboxDevStoreModule(),

  // Shoebox Functional Modules
  slickModule = ShoeboxSlickModule(),
  scraperModule = ScraperImplModule(),
  socialGraphModule = ProdSocialGraphModule(),
  analyticsModule = DevAnalyticsModule(),
  webSocketModule = ShoeboxWebSocketModule(),
  topicModelModule = DevTopicModelModule(),
  domainTagImporterModule = DevDomainTagImporterModule(),
  sliderHistoryTrackerModule = ProdSliderHistoryTrackerModule(),
  userIndexModule = UserIndexModule(),
  geckoboardModule = GeckoboardModule(),
  dataIntegrityModule = DataIntegrityModule(),
  cacheModule = ShoeboxCacheModule(HashMapMemoryCacheModule()),
  clickHistoryModule = ShoeboxClickHistoryModule(),
  browsingHistoryModule = ShoeboxBrowsingHistoryModule()
) with CommonDevModule

