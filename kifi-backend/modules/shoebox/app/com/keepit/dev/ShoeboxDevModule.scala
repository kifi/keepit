package com.keepit.dev

import com.keepit.abook.ProdABookServiceClientModule
import com.keepit.common.auth.ProdShoeboxLegacyUserServiceModule
import com.keepit.common.controller.ProdShoeboxUserActionsModule
import com.keepit.common.mail._
import com.keepit.common.seo.DevSiteMapGeneratorModule
import com.keepit.cortex.ProdCortexServiceClientModule
import com.keepit.eliza.ProdElizaServiceClientModule
import com.keepit.graph.ProdGraphServiceClientModule
import com.keepit.heimdal.{ DevHeimdalServiceClientModule, ProdHeimdalServiceClientModule }
import com.keepit.search.ProdSearchServiceClientModule
import com.keepit.shoebox._
import com.keepit.common.cache.ShoeboxCacheModule
import com.keepit.classify.DevDomainTagImporterModule
import com.keepit.common.cache.HashMapMemoryCacheModule
import com.keepit.social.ProdShoeboxSecureSocialModule
import com.keepit.common.analytics.DevAnalyticsModule
import com.keepit.common.store.ShoeboxDevStoreModule
import com.keepit.inject.CommonDevModule
import com.keepit.common.integration.DevReaperModule
import com.keepit.scraper.{ DevScrapeSchedulerModule, ProdScraperServiceClientModule, DevScraperHealthMonitorModule }
import com.keepit.common.queue.{ ProdSimpleQueueModule, DevSimpleQueueModule }
import com.keepit.queue.DevNormalizationUpdateJobQueueModule
import com.keepit.common.concurrent.ProdForkJoinContextMonitorModule
import com.keepit.common.external.DevExternalServiceModule
import com.keepit.curator.ProdCuratorServiceClientModule

case class ShoeboxDevModule() extends ShoeboxModule(
  secureSocialModule = ProdShoeboxSecureSocialModule(),
  userActionsModule = ProdShoeboxUserActionsModule(),
  mailModule = DevMailModule(),
  reaperModule = DevReaperModule(),
  siteMapModule = DevSiteMapGeneratorModule(),
  storeModule = ShoeboxDevStoreModule(),
  sqsModule = DevSimpleQueueModule(),
  normalizationQueueModule = DevNormalizationUpdateJobQueueModule(),

  // Shoebox Functional Modules
  analyticsModule = DevAnalyticsModule(),
  //  topicModelModule = DevTopicModelModule(),
  domainTagImporterModule = DevDomainTagImporterModule(),
  scrapeSchedulerModule = DevScrapeSchedulerModule(),
  scraperHealthMonitorModule = DevScraperHealthMonitorModule(),
  fjMonitorModule = ProdForkJoinContextMonitorModule(),
  cacheModule = ShoeboxCacheModule(HashMapMemoryCacheModule()),
  externalServiceModule = DevExternalServiceModule()
) with CommonDevModule {

  override val legacyUserServiceModule = ProdShoeboxLegacyUserServiceModule()

  // Service clients
  val searchServiceClientModule = ProdSearchServiceClientModule()
  val shoeboxServiceClientModule = ProdShoeboxServiceClientModule()
  val elizaServiceClientModule = ProdElizaServiceClientModule()
  val heimdalServiceClientModule = DevHeimdalServiceClientModule()
  val abookServiceClientModule = ProdABookServiceClientModule()
  val scraperServiceClientModule = ProdScraperServiceClientModule()
  val cortexServiceClientModule = ProdCortexServiceClientModule()
  val graphServiceClientModule = ProdGraphServiceClientModule()
  val curatorServiceClientModule = ProdCuratorServiceClientModule()
}
