package com.keepit.shoebox

import com.keepit.common.cache.{EhCacheCacheModule, MemcachedCacheModule, ShoeboxCacheModule}
import com.keepit.social.ProdShoeboxSecureSocialModule
import com.keepit.scraper.ScraperImplModule
import com.keepit.common.social.ProdSocialGraphModule
import com.keepit.common.analytics.ProdAnalyticsModule
import com.keepit.learning.topicmodel.LdaTopicModelModule
import com.keepit.model.ProdSliderHistoryTrackerModule
import com.keepit.common.mail.DevMailModule
import com.keepit.common.store.ShoeboxDevStoreModule
import com.keepit.realtime.ShoeboxWebSocketModule
import com.keepit.classify.ProdDomainTagImporterModule
import com.keepit.inject.CommonProdModule
import com.keepit.integrity.DataIntegrityModule
import com.keepit.reports.GeckoboardModule

case class ShoeboxProdModule() extends ShoeboxModule(
  secureSocialModule = ProdShoeboxSecureSocialModule(),
  mailModule = DevMailModule(),
  storeModule = ShoeboxDevStoreModule(),

  // Shoebox Functional Modules
  slickModule = ShoeboxSlickModule(),
  scraperModule = ScraperImplModule(),
  socialGraphModule = ProdSocialGraphModule(),
  analyticsModule = ProdAnalyticsModule(),
  webSocketModule = ShoeboxWebSocketModule(),
  topicModelModule = LdaTopicModelModule(),
  domainTagImporterModule = ProdDomainTagImporterModule(),
  sliderHistoryTrackerModule = ProdSliderHistoryTrackerModule(),
  userIndexModule = UserIndexModule(),
  geckoboardModule = GeckoboardModule(),
  dataIntegrityModule = DataIntegrityModule(),
  cacheModule = ShoeboxCacheModule(MemcachedCacheModule(), EhCacheCacheModule()),
  clickHistoryModule = ShoeboxClickHistoryModule(),
  browsingHistoryModule = ShoeboxBrowsingHistoryModule()
) with CommonProdModule
