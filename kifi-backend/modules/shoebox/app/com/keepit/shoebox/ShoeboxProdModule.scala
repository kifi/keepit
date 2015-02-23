package com.keepit.shoebox

import com.keepit.abook.ProdABookServiceClientModule
import com.keepit.common.cache.{ EhCacheCacheModule, MemcachedCacheModule, ShoeboxCacheModule }
import com.keepit.common.controller.ProdShoeboxUserActionsModule
import com.keepit.common.seo.{ ProdSiteMapGeneratorModule }
import com.keepit.cortex.ProdCortexServiceClientModule
import com.keepit.eliza.ProdElizaServiceClientModule
import com.keepit.graph.ProdGraphServiceClientModule
import com.keepit.heimdal.ProdHeimdalServiceClientModule
import com.keepit.search.ProdSearchServiceClientModule
import com.keepit.social.ProdShoeboxSecureSocialModule
import com.keepit.common.analytics.ProdAnalyticsModule
import com.keepit.common.mail.ProdMailModule
import com.keepit.common.store.ShoeboxDevStoreModule
import com.keepit.inject.CommonProdModule
import com.keepit.common.integration.ProdReaperModule
import com.keepit.scraper.{ ProdScraperHealthMonitorModule, ProdScrapeSchedulerModule, ProdScraperServiceClientModule }
import com.keepit.common.queue.ProdSimpleQueueModule
import com.keepit.queue.ProdNormalizationUpdateJobQueueModule
import com.keepit.common.concurrent.ProdForkJoinContextMonitorModule
import com.keepit.curator.ProdCuratorServiceClientModule

case class ShoeboxProdModule() extends ShoeboxModule(
  secureSocialModule = ProdShoeboxSecureSocialModule(),
  userActionsModule = ProdShoeboxUserActionsModule(),
  mailModule = ProdMailModule(),
  reaperModule = ProdReaperModule(),
  siteMapModule = ProdSiteMapGeneratorModule(),
  storeModule = ShoeboxDevStoreModule(),
  sqsModule = ProdSimpleQueueModule(),
  normalizationQueueModule = ProdNormalizationUpdateJobQueueModule(),

  // Shoebox Functional Modules
  analyticsModule = ProdAnalyticsModule(),
  //topicModelModule = LdaTopicModelModule(), //disable for now
  scrapeSchedulerModule = ProdScrapeSchedulerModule(),
  scraperHealthMonitorModule = ProdScraperHealthMonitorModule(),
  fjMonitorModule = ProdForkJoinContextMonitorModule(),
  twilioCredentialsModule = ProdTwilioCredentialsModule(),
  cacheModule = ShoeboxCacheModule(MemcachedCacheModule(), EhCacheCacheModule())
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
  val curatorServiceClientModule = ProdCuratorServiceClientModule()
}
