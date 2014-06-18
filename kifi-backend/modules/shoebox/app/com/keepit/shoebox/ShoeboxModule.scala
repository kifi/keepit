package com.keepit.shoebox

import com.keepit.reports._
import com.keepit.common.cache.ShoeboxCacheModule
import com.keepit.social.SecureSocialModule
import com.keepit.common.mail.MailModule
import com.keepit.common.analytics.AnalyticsModule
import com.keepit.model.ProdSliderHistoryTrackerModule
import com.keepit.scraper.{ScrapeSchedulerModule, ProdScraperServiceClientModule}
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
import com.keepit.common.queue.SimpleQueueModule
import com.keepit.queue.{NormalizationUpdateJobQueueModule}
import com.keepit.common.concurrent.ForkJoinContextMonitorModule
import com.keepit.cortex.ProdCortexServiceClientModule
import com.keepit.common.external.ExternalServiceModule
import com.keepit.graph.ProdGraphServiceClientModule

abstract class ShoeboxModule(
  //these are modules that inheriting modules need to provide
  val secureSocialModule: SecureSocialModule,
  val mailModule: MailModule,
  val reaperModule: ReaperModule,
  val storeModule: ShoeboxDevStoreModule,
  val sqsModule: SimpleQueueModule,
  val normalizationQueueModule: NormalizationUpdateJobQueueModule,

  // Shoebox Functional Modules
  val analyticsModule: AnalyticsModule,
  val domainTagImporterModule: DomainTagImporterModule,
  val cacheModule: ShoeboxCacheModule,
  val scrapeSchedulerModule: ScrapeSchedulerModule,
  val fjMonitorModule: ForkJoinContextMonitorModule,
  val externalServiceModule: ExternalServiceModule
) extends ConfigurationModule with CommonServiceModule {
  //these are modules that are provided here (but can be overriden by inheriting modules)
  // Service clients
  val searchServiceClientModule = ProdSearchServiceClientModule()
  val shoeboxServiceClientModule = ProdShoeboxServiceClientModule()
  val elizaServiceClientModule = ProdElizaServiceClientModule()
  val heimdalServiceClientModule = ProdHeimdalServiceClientModule()
  val abookServiceClientModule = ProdABookServiceClientModule()
  val scraperServiceClientModule = ProdScraperServiceClientModule()
  val cortexServiceClientModule = ProdCortexServiceClientModule()
  val graphServiceClientModule = ProdGraphServiceClientModule()


  val abuseControlModule = AbuseControlModule()
  val slickModule = ShoeboxSlickModule()
  val socialGraphModule = ProdSocialGraphModule()
  val sliderHistoryTrackerModule = ProdSliderHistoryTrackerModule()
  val geckoboardModule = GeckoboardModule()
  val dataIntegrityModule = DataIntegrityModule()
  val keepImportsModule = KeepImportsModule()

  val repoChangeListenerModule = ShoeboxRepoChangeListenerModule()

  val mailerModule = PlayMailerModule()
}
