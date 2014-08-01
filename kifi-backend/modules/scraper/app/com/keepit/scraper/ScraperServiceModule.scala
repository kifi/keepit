package com.keepit.scraper

import com.keepit.common.cache.CacheModule
import com.keepit.common.concurrent.ForkJoinContextMonitorModule
import com.keepit.common.store.StoreModule
import com.keepit.inject.{ CommonServiceModule, ConfigurationModule }
import com.keepit.scraper.embedly.EmbedlyModule
import com.keepit.scraper.fetcher.HttpFetcherModule
import com.keepit.shoebox.{ ShoeboxScraperClientModule, ShoeboxServiceClientModule }
import com.keepit.social.RemoteSecureSocialModule

abstract class ScraperServiceModule(
    val cacheModule: CacheModule,
    val storeModule: StoreModule,
    val fjMonitorModule: ForkJoinContextMonitorModule,
    val scrapeProcessorModule: ScrapeProcessorModule,
    val embedlyModule: EmbedlyModule) extends ConfigurationModule with CommonServiceModule {
  // Service clients
  val shoeboxServiceClientModule: ShoeboxServiceClientModule
  val shoeboxScraperClientModule: ShoeboxScraperClientModule
  val secureSocialModule = RemoteSecureSocialModule()

  val fetcherModule: HttpFetcherModule
}
