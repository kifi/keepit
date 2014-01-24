package com.keepit.shoebox

import com.keepit.reports._
import com.keepit.common.cache.ShoeboxCacheModule
import com.keepit.social.SecureSocialModule
import com.keepit.common.mail.MailModule
import com.keepit.common.analytics.AnalyticsModule
import com.keepit.learning.topicmodel.TopicModelModule
import com.keepit.model.ProdSliderHistoryTrackerModule
import com.keepit.scraper.{ScrapeSchedulerModule, ProdScraperServiceClientModule, ProdScrapeSchedulerModule}
import com.keepit.classify.DomainTagImporterModule
import com.keepit.common.store.ShoeboxDevStoreModule
import com.keepit.inject.{CommonServiceModule, ConfigurationModule}
import com.keepit.integrity.DataIntegrityModule
import com.keepit.search.ProdSearchServiceClientModule
import com.keepit.eliza.ProdElizaServiceClientModule
import com.keepit.common.social.ProdSocialGraphModule
import com.keepit.heimdal.ProdHeimdalServiceClientModule
import com.keepit.abook.ProdABookServiceClientModule
import com.keepit.common.integration.ReaperModule

abstract class ShoeboxModule(
  val secureSocialModule: SecureSocialModule,
  val mailModule: MailModule,
  val reaperModule: ReaperModule,
  val storeModule: ShoeboxDevStoreModule,

  // Shoebox Functional Modules
  val analyticsModule: AnalyticsModule,
//  val topicModelModule: TopicModelModule, //disable for now
  val domainTagImporterModule: DomainTagImporterModule,
  val cacheModule: ShoeboxCacheModule,
  val scrapeSchedulerModule: ScrapeSchedulerModule
) extends ConfigurationModule with CommonServiceModule {

  // Service clients
  val searchServiceClientModule = ProdSearchServiceClientModule()
  val shoeboxServiceClientModule = ProdShoeboxServiceClientModule()
  val elizaServiceClientModule = ProdElizaServiceClientModule()
  val heimdalServiceClientModule = ProdHeimdalServiceClientModule()
  val abookServiceClientModule = ProdABookServiceClientModule()
  val scraperServiceClientModule = ProdScraperServiceClientModule()

  val abuseControlModule = AbuseControlModule()
  val slickModule = ShoeboxSlickModule()
  val socialGraphModule = ProdSocialGraphModule()
  val sliderHistoryTrackerModule = ProdSliderHistoryTrackerModule()
  val geckoboardModule = GeckoboardModule()
  val dataIntegrityModule = DataIntegrityModule()
  val keepImportsModule = KeepImportsModule()

  val mailerModule = PlayMailerModule()
}
