package com.keepit.shoebox

import com.keepit.common.auth.LegacyUserServiceModule
import com.keepit.common.controller.UserActionsModule
import com.keepit.common.seo.SiteMapGeneratorModule
import com.keepit.reports._
import com.keepit.common.cache.ShoeboxCacheModule
import com.keepit.social.SecureSocialModule
import com.keepit.common.mail.MailModule
import com.keepit.common.analytics.AnalyticsModule
import com.keepit.model.{ UrlPatternRuleModule, ProdSliderHistoryTrackerModule }
import com.keepit.scraper.{ ScraperHealthMonitorModule, ScrapeSchedulerModule, ScraperServiceClientModule }
import com.keepit.classify.DomainTagImporterModule
import com.keepit.common.store.ShoeboxDevStoreModule
import com.keepit.inject.{ CommonServiceModule, ConfigurationModule }
import com.keepit.integrity.DataIntegrityModule
import com.keepit.search.{ SearchServiceClientModule }
import com.keepit.eliza.{ ElizaServiceClientModule }
import com.keepit.curator.CuratorServiceClientModule
import com.keepit.common.social.ProdSocialGraphModule
import com.keepit.heimdal.{ HeimdalServiceClientModule }
import com.keepit.abook.{ ABookServiceClientModule }
import com.keepit.common.integration.ReaperModule
import com.keepit.common.queue.SimpleQueueModule
import com.keepit.queue.{ NormalizationUpdateJobQueueModule }
import com.keepit.common.concurrent.ForkJoinContextMonitorModule
import com.keepit.cortex.{ CortexServiceClientModule }
import com.keepit.common.external.ExternalServiceModule
import com.keepit.graph.{ GraphServiceClientModule }
import com.keepit.common.zookeeper.ServiceTypeModule
import com.keepit.common.service.ServiceType

case class ShoeboxServiceTypeModule() extends ServiceTypeModule {
  val serviceType = ServiceType.SHOEBOX
  val servicesToListenOn = ServiceType.SEARCH :: ServiceType.ELIZA :: ServiceType.HEIMDAL :: ServiceType.ABOOK :: ServiceType.SCRAPER :: ServiceType.CORTEX :: ServiceType.GRAPH :: ServiceType.CURATOR :: Nil
}

abstract class ShoeboxModule(
    //these are modules that inheriting modules need to provide
    val secureSocialModule: SecureSocialModule,
    //    val legacyUserServiceModule: LegacyUserServiceModule,
    val userActionsModule: UserActionsModule,
    val mailModule: MailModule,
    val reaperModule: ReaperModule,
    val siteMapModule: SiteMapGeneratorModule,
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
    val externalServiceModule: ExternalServiceModule) extends ConfigurationModule with CommonServiceModule {
  //these are modules that are provided here (but can be overriden by inheriting modules)
  // Service clients
  val serviceTypeModule = ShoeboxServiceTypeModule()
  val searchServiceClientModule: SearchServiceClientModule
  val shoeboxServiceClientModule: ShoeboxServiceClientModule //why do we need the shoeboxServiceClientModule here?
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
  val urlPatternRuleModule = UrlPatternRuleModule()

  val repoChangeListenerModule = ShoeboxRepoChangeListenerModule()

  val dbSequencingModule = ShoeboxDbSequencingModule()

  val mailerModule = PlayMailerModule()
}
