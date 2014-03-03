package com.keepit.scraper

import com.keepit.common.cache.CacheModule
import com.keepit.social.RemoteSecureSocialModule
import com.keepit.inject.{CommonServiceModule, ConfigurationModule}
import com.keepit.shoebox.ProdShoeboxServiceClientModule
import com.keepit.common.store.{S3ImageConfig, StoreModule}
import com.keepit.scraper.extractor.ExtractorFactory
import com.keepit.common.concurrent.ForkJoinContextMonitorModule

abstract class ScraperServiceModule(
  val cacheModule: CacheModule,
  val storeModule: StoreModule,
  val fjMonitorModule: ForkJoinContextMonitorModule,
  val scrapeProcessorModule: ScrapeProcessorModule
) extends ConfigurationModule with CommonServiceModule  {
  // Service clients
  val shoeboxServiceClientModule = ProdShoeboxServiceClientModule()
  val secureSocialModule = RemoteSecureSocialModule()
}
