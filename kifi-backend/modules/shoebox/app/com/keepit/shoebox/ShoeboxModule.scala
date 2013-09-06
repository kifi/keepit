package com.keepit.shoebox

import com.keepit.reports._
import com.keepit.common.cache.ShoeboxCacheModule
import com.keepit.social.SecureSocialModule
import com.keepit.common.mail.MailModule
import com.keepit.common.analytics.AnalyticsModule
import com.keepit.learning.topicmodel.TopicModelModule
import com.keepit.model.ProdSliderHistoryTrackerModule
import com.keepit.scraper.ScraperImplModule
import com.keepit.realtime.WebSocketModule
import com.keepit.classify.DomainTagImporterModule
import com.keepit.common.store.ShoeboxDevStoreModule
import com.keepit.inject.{CommonServiceModule, ConfigurationModule}
import com.keepit.integrity.DataIntegrityModule
import com.keepit.search.ProdSearchServiceClientModule
import com.keepit.eliza.ProdElizaServiceClientModule
import com.keepit.common.social.ProdSocialGraphModule

abstract class ShoeboxModule(
  val secureSocialModule: SecureSocialModule,
  val mailModule: MailModule,
  val storeModule: ShoeboxDevStoreModule,

  // Shoebox Functional Modules
  val analyticsModule: AnalyticsModule,
  val webSocketModule: WebSocketModule,
  val topicModelModule: TopicModelModule,
  val domainTagImporterModule: DomainTagImporterModule,
  val cacheModule: ShoeboxCacheModule
) extends ConfigurationModule with CommonServiceModule {

  // Service clients
  val searchServiceClientModule = ProdSearchServiceClientModule()
  val shoeboxServiceClientModule = ProdShoeboxServiceClientModule()
  val elizaServiceClientModule = ProdElizaServiceClientModule()

  val slickModule = ShoeboxSlickModule()
  val scraperModule = ScraperImplModule()
  val socialGraphModule = ProdSocialGraphModule()
  val sliderHistoryTrackerModule = ProdSliderHistoryTrackerModule()
  val userIndexModule = UserIndexModule()
  val geckoboardModule = GeckoboardModule()
  val dataIntegrityModule = DataIntegrityModule()
  val clickHistoryModule = ShoeboxClickHistoryModule()
  val browsingHistoryModule = ShoeboxBrowsingHistoryModule()

}
