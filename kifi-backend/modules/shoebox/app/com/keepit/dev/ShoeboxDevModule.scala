package com.keepit.dev

import com.keepit.controllers.internal.DevDataPipelineExecutorModule
import com.keepit.abook.ProdABookServiceClientModule
import com.keepit.common.controller.ProdShoeboxUserActionsModule
import com.keepit.common.mail._
import com.keepit.common.seo.DevSiteMapGeneratorModule
import com.keepit.cortex.ProdCortexServiceClientModule
import com.keepit.eliza.ProdElizaServiceClientModule
import com.keepit.graph.ProdGraphServiceClientModule
import com.keepit.heimdal.DevHeimdalServiceClientModule
import com.keepit.search.ProdSearchServiceClientModule
import com.keepit.shoebox._
import com.keepit.common.cache.ShoeboxCacheModule
import com.keepit.common.cache.HashMapMemoryCacheModule
import com.keepit.shoebox.DevTwilioCredentialsModule
import com.keepit.social.ProdShoeboxSecureSocialModule
import com.keepit.common.analytics.DevAnalyticsModule
import com.keepit.common.store.ShoeboxDevStoreModule
import com.keepit.inject.CommonDevModule
import com.keepit.common.integration.DevReaperModule
import com.keepit.scraper.{ DevScrapeSchedulerModule, ProdScraperServiceClientModule, DevScraperHealthMonitorModule }
import com.keepit.common.queue.DevSimpleQueueModule
import com.keepit.queue.DevNormalizationUpdateJobQueueModule
import com.keepit.common.concurrent.ProdForkJoinContextMonitorModule
import com.keepit.curator.ProdCuratorServiceClientModule

case class ShoeboxDevModule() extends ShoeboxModule with CommonDevModule {
  val secureSocialModule = ProdShoeboxSecureSocialModule()
  val userActionsModule = ProdShoeboxUserActionsModule()
  val mailModule = DevMailModule()
  val reaperModule = DevReaperModule()
  val siteMapModule = DevSiteMapGeneratorModule()
  val storeModule = ShoeboxDevStoreModule()
  val sqsModule = DevSimpleQueueModule()
  val normalizationQueueModule = DevNormalizationUpdateJobQueueModule()

  // Shoebox Functional Modules
  val analyticsModule = DevAnalyticsModule()
  //  topicModelModule = DevTopicModelModule()
  val scrapeSchedulerModule = DevScrapeSchedulerModule()
  val scraperHealthMonitorModule = DevScraperHealthMonitorModule()
  val fjMonitorModule = ProdForkJoinContextMonitorModule()
  val twilioCredentialsModule = DevTwilioCredentialsModule()
  val dataPipelineExecutorModule = DevDataPipelineExecutorModule()
  val cacheModule = ShoeboxCacheModule(HashMapMemoryCacheModule())

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
