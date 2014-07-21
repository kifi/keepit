package com.keepit.shoebox

import com.keepit.abook.ProdABookServiceClientModule
import com.keepit.common.cache.{ EhCacheCacheModule, MemcachedCacheModule, ShoeboxCacheModule }
import com.keepit.cortex.ProdCortexServiceClientModule
import com.keepit.eliza.ProdElizaServiceClientModule
import com.keepit.graph.ProdGraphServiceClientModule
import com.keepit.heimdal.ProdHeimdalServiceClientModule
import com.keepit.search.ProdSearchServiceClientModule
import com.keepit.social.ProdShoeboxSecureSocialModule
import com.keepit.common.analytics.ProdAnalyticsModule
import com.keepit.common.mail.ProdMailModule
import com.keepit.common.store.ShoeboxDevStoreModule
import com.keepit.classify.ProdDomainTagImporterModule
import com.keepit.inject.CommonProdModule
import com.keepit.common.integration.ProdReaperModule
import com.keepit.scraper.{ ProdScraperHealthMonitorModule, ProdScrapeSchedulerModule, ProdScraperServiceClientModule }
import com.keepit.common.zookeeper.{ DiscoveryModule, ProdDiscoveryModule }
import com.keepit.common.service.ServiceType
import com.keepit.common.queue.ProdSimpleQueueModule
import com.keepit.queue.ProdNormalizationUpdateJobQueueModule
import com.keepit.common.concurrent.ProdForkJoinContextMonitorModule
import com.keepit.common.external.ProdExternalServiceModule
import com.keepit.signal.ProdReKeepStatsUpdaterModule

case class ShoeboxProdModule() extends ShoeboxModule(
  secureSocialModule = ProdShoeboxSecureSocialModule(),
  mailModule = ProdMailModule(),
  reaperModule = ProdReaperModule(),
  storeModule = ShoeboxDevStoreModule(),
  sqsModule = ProdSimpleQueueModule(),
  normalizationQueueModule = ProdNormalizationUpdateJobQueueModule(),

  // Shoebox Functional Modules
  analyticsModule = ProdAnalyticsModule(),
  //topicModelModule = LdaTopicModelModule(), //disable for now
  domainTagImporterModule = ProdDomainTagImporterModule(),
  scrapeSchedulerModule = ProdScrapeSchedulerModule(),
  scraperHealthMonitorModule = ProdScraperHealthMonitorModule(),
  fjMonitorModule = ProdForkJoinContextMonitorModule(),
  cacheModule = ShoeboxCacheModule(MemcachedCacheModule(), EhCacheCacheModule()),
  externalServiceModule = ProdExternalServiceModule(),
  rekeepStatsUpdaterModule = ProdReKeepStatsUpdaterModule()
) with CommonProdModule {
  // Service clients
  val searchServiceClientModule = ProdSearchServiceClientModule()
  val shoeboxServiceClientModule = ProdShoeboxServiceClientModule()
  val elizaServiceClientModule = ProdElizaServiceClientModule()
  val heimdalServiceClientModule = ProdHeimdalServiceClientModule()
  val abookServiceClientModule = ProdABookServiceClientModule()
  val scraperServiceClientModule = ProdScraperServiceClientModule()
  val cortexServiceClientModule = ProdCortexServiceClientModule()
  val graphServiceClientModule = ProdGraphServiceClientModule()

  private val servicesToListenOn = ServiceType.SEARCH :: ServiceType.ELIZA :: ServiceType.HEIMDAL :: ServiceType.ABOOK :: ServiceType.SCRAPER :: ServiceType.CORTEX :: ServiceType.GRAPH :: ServiceType.CURATOR :: Nil
  val discoveryModule = new ProdDiscoveryModule(ServiceType.SHOEBOX, servicesToListenOn)
}
