package com.keepit.shoebox

import com.keepit.common.cache.{EhCacheCacheModule, MemcachedCacheModule, ShoeboxCacheModule}
import com.keepit.social.ProdShoeboxSecureSocialModule
import com.keepit.search.ProdSearchServiceClientModule
import com.keepit.scraper.ScraperImplModule
import com.keepit.common.social.ProdSocialGraphModule
import com.keepit.common.analytics.ProdAnalyticsModule
import com.keepit.learning.topicmodel.LdaTopicModelModule
import com.keepit.model.ProdSliderHistoryTrackerModule
import com.keepit.common.mail.ProdMailModule
import com.keepit.common.crypto.ShoeboxCryptoModule
import com.keepit.common.store.ShoeboxProdStoreModule
import com.keepit.classify.ProdDomainTagImporterModule
import com.keepit.common.healthcheck.ProdHealthCheckModule
import com.keepit.common.net.ProdHttpClientModule
import com.keepit.inject.ProdFortyTwoModule
import com.keepit.common.actor.ProdActorSystemModule
import com.keepit.common.zookeeper.ProdDiscoveryModule
import com.keepit.integrity.DataIntegrityModule
import com.keepit.eliza.ProdElizaServiceClientModule

case class ShoeboxProdModule() extends ShoeboxModule(
  // Common Functional Modules
  fortyTwoModule = ProdFortyTwoModule(),
  cacheModule = ShoeboxCacheModule(MemcachedCacheModule(), EhCacheCacheModule()),
  secureSocialModule = ProdShoeboxSecureSocialModule(),
  searchServiceClientModule = ProdSearchServiceClientModule(),
  clickHistoryModule = ShoeboxClickHistoryModule(),
  browsingHistoryModule = ShoeboxBrowsingHistoryModule(),
  mailModule = ProdMailModule(),
  cryptoModule = ShoeboxCryptoModule(),
  storeModule = ShoeboxProdStoreModule(),
  actorSystemModule = ProdActorSystemModule(),
  discoveryModule = ProdDiscoveryModule(),
  healthCheckModule = ProdHealthCheckModule(),
  httpClientModule = ProdHttpClientModule(),
  shoeboxServiceClientModule = ProdShoeboxServiceClientModule(),
  elizaServiceClientModule = ProdElizaServiceClientModule(),

  // Shoebox Functional Modules
  slickModule = ShoeboxSlickModule(),
  scraperModule = ScraperImplModule(),
  socialGraphModule = ProdSocialGraphModule(),
  analyticsModule = ProdAnalyticsModule(),
  topicModelModule = LdaTopicModelModule(),
  domainTagImporterModule = ProdDomainTagImporterModule(),
  sliderHistoryTrackerModule = ProdSliderHistoryTrackerModule(),
  dataIntegrityModule = DataIntegrityModule()
)
