package com.keepit.scraper

import com.keepit.common.cache.CacheModule
import com.keepit.social.RemoteSecureSocialModule
import com.keepit.inject.{CommonServiceModule, ConfigurationModule}
import com.keepit.shoebox.ProdShoeboxServiceClientModule
import com.keepit.common.store.{S3ImageConfig, StoreModule}
import com.keepit.scraper.extractor.ExtractorFactory
import com.keepit.common.concurrent.ForkJoinContextMonitorModule
import com.keepit.scraper.embedly.EmbedlyModule

abstract class ScraperServiceModule(
  val cacheModule: CacheModule,
  val storeModule: StoreModule,
  val fjMonitorModule: ForkJoinContextMonitorModule,
  val scrapeProcessorModule: ScrapeProcessorModule,
  val embedlyModule: EmbedlyModule
) extends ConfigurationModule with CommonServiceModule  {
  // Service clients
  val shoeboxServiceClientModule = ProdShoeboxServiceClientModule()
  val secureSocialModule = RemoteSecureSocialModule()
}
