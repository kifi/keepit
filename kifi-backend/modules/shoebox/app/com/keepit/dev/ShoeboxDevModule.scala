package com.keepit.dev

import com.keepit.common.mail._
import com.keepit.shoebox._
import com.keepit.common.cache.ShoeboxCacheModule
import com.keepit.classify.DevDomainTagImporterModule
import com.keepit.common.cache.HashMapMemoryCacheModule
import com.keepit.social.ProdShoeboxSecureSocialModule
import com.keepit.common.analytics.DevAnalyticsModule
import com.keepit.common.store.ShoeboxDevStoreModule
import com.keepit.inject.CommonDevModule
import com.keepit.common.integration.DevReaperModule
import com.keepit.scraper.DevScrapeSchedulerModule
import com.keepit.common.queue.{ProdSimpleQueueModule, DevSimpleQueueModule}
import com.keepit.queue.DevNormalizationUpdateJobQueueModule
import com.keepit.common.concurrent.ProdForkJoinContextMonitorModule
import com.keepit.common.external.DevExternalServiceModule
import com.keepit.signal.DevReKeepStatsUpdaterModule

case class ShoeboxDevModule() extends ShoeboxModule(
  secureSocialModule = ProdShoeboxSecureSocialModule(),
  mailModule = DevMailModule(),
  reaperModule = DevReaperModule(),
  storeModule = ShoeboxDevStoreModule(),
  sqsModule = DevSimpleQueueModule(),
  normalizationQueueModule = DevNormalizationUpdateJobQueueModule(),

  // Shoebox Functional Modules
  analyticsModule = DevAnalyticsModule(),
//  topicModelModule = DevTopicModelModule(),
  domainTagImporterModule = DevDomainTagImporterModule(),
  scrapeSchedulerModule = DevScrapeSchedulerModule(),
  fjMonitorModule = ProdForkJoinContextMonitorModule(),
  cacheModule = ShoeboxCacheModule(HashMapMemoryCacheModule()),
  externalServiceModule = DevExternalServiceModule(),
  rekeepStatsUpdaterModule = DevReKeepStatsUpdaterModule()
) with CommonDevModule
