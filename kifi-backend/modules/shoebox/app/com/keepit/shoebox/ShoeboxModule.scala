package com.keepit.shoebox

import com.keepit.reports._
import com.keepit.common.cache.ShoeboxCacheModule
import com.keepit.social.SecureSocialModule
import com.keepit.common.mail.MailModule
import com.keepit.common.analytics.AnalyticsModule
import com.keepit.model.ProdSliderHistoryTrackerModule
import com.keepit.scraper.{ ScraperHealthMonitorModule, ScrapeSchedulerModule, ScraperServiceClientModule }
import com.keepit.classify.DomainTagImporterModule
import com.keepit.common.store.ShoeboxDevStoreModule
import com.keepit.inject.{ CommonServiceModule, ConfigurationModule }
import com.keepit.integrity.DataIntegrityModule
import com.keepit.search.{ SearchServiceClientModule, ProdSearchServiceClientModule }
import com.keepit.eliza.{ ElizaServiceClientModule, ProdElizaServiceClientModule }
import com.keepit.curator.CuratorServiceClientModule
import com.keepit.common.social.ProdSocialGraphModule
import com.keepit.heimdal.{ HeimdalServiceClientModule, ProdHeimdalServiceClientModule }
import com.keepit.abook.{ ABookServiceClientModule, ProdABookServiceClientModule }
import com.keepit.common.integration.ReaperModule
import com.keepit.common.queue.SimpleQueueModule
import com.keepit.queue.{ NormalizationUpdateJobQueueModule }
import com.keepit.common.concurrent.ForkJoinContextMonitorModule
import com.keepit.cortex.{ CortexServiceClientModule, ProdCortexServiceClientModule }
import com.keepit.common.external.ExternalServiceModule
import com.keepit.graph.{ GraphServiceClientModule, ProdGraphServiceClientModule }
import com.keepit.signal.ReKeepStatsUpdaterModule

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
    val scraperHealthMonitorModule: ScraperHealthMonitorModule,
    val fjMonitorModule: ForkJoinContextMonitorModule,
    val externalServiceModule: ExternalServiceModule,
    val rekeepStatsUpdaterModule: ReKeepStatsUpdaterModule) extends ConfigurationModule with CommonServiceModule {
  //these are modules that are provided here (but can be overriden by inheriting modules)
  // Service clients
  val searchServiceClientModule: SearchServiceClientModule
  val shoeboxServiceClientModule: ShoeboxServiceClientModule
  val elizaServiceClientModule: ElizaServiceClientModule
  val heimdalServiceClientModule: HeimdalServiceClientModule
  val abookServiceClientModule: ABookServiceClientModule
  val scraperServiceClientModule: ScraperServiceClientModule
  val cortexServiceClientModule: CortexServiceClientModule
  val graphServiceClientModule: GraphServiceClientModule
  val curatorServiceClientModule: CuratorServiceClientModule

  val abuseControlModule = AbuseControlModule()
  val slickModule = ShoeboxSlickModule()
  val socialGraphModule = ProdSocialGraphModule()
  val sliderHistoryTrackerModule = ProdSliderHistoryTrackerModule()
  val geckoboardModule = GeckoboardModule()
  val dataIntegrityModule = DataIntegrityModule()
  val keepImportsModule = KeepImportsModule()

  val repoChangeListenerModule = ShoeboxRepoChangeListenerModule()

  val dbSequencingModule = ShoeboxDbSequencingModule()

  val mailerModule = PlayMailerModule()
}
