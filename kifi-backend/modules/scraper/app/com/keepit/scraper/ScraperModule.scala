package com.keepit.scraper

import com.keepit.common.cache.CacheModule
import com.keepit.scraper.fetcher.HttpFetcherModule
import com.keepit.social.RemoteSecureSocialModule
import com.keepit.inject.{ CommonServiceModule, ConfigurationModule }
import com.keepit.shoebox.ProdShoeboxServiceClientModule
import com.keepit.common.store.StoreModule
import com.keepit.common.concurrent.ForkJoinContextMonitorModule
import com.keepit.scraper.embedly.EmbedlyModule
import com.keepit.common.zookeeper.ServiceTypeModule
import com.keepit.common.service.ServiceType

case class ScraperServiceTypeModule() extends ServiceTypeModule {
  val serviceType = ServiceType.SCRAPER
  val servicesToListenOn = ServiceType.SHOEBOX :: Nil
}

abstract class ScraperModule(
    val cacheModule: CacheModule,
    val storeModule: StoreModule,
    val fjMonitorModule: ForkJoinContextMonitorModule,
    val scrapeProcessorModule: ScrapeProcessorModule,
    val embedlyModule: EmbedlyModule) extends ConfigurationModule with CommonServiceModule {
  // Service clients
  val serviceTypeModule = ScraperServiceTypeModule()
  val shoeboxServiceClientModule = ProdShoeboxServiceClientModule()
  val secureSocialModule = RemoteSecureSocialModule()

  val fetcherModule: HttpFetcherModule
}
