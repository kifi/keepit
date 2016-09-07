package com.keepit.shoebox

import com.keepit.abook.ABookServiceClientModule
import com.keepit.commanders.emails.activity.ActivityEmailQueueModule
import com.keepit.common.analytics.AnalyticsModule
import com.keepit.common.cache.ShoeboxCacheModule
import com.keepit.common.concurrent.ForkJoinContextMonitorModule
import com.keepit.common.controller.UserActionsModule
import com.keepit.common.integration.ReaperModule
import com.keepit.common.mail.MailModule
import com.keepit.common.oauth.OAuthConfigurationModule
import com.keepit.common.seo.SiteMapGeneratorModule
import com.keepit.common.service.ServiceType
import com.keepit.common.social.ProdSocialGraphModule
import com.keepit.common.store.ShoeboxStoreModule
import com.keepit.common.zookeeper.ServiceTypeModule
import com.keepit.controllers.internal.DataPipelineExecutorModule
import com.keepit.cortex.CortexServiceClientModule
import com.keepit.eliza.ElizaServiceClientModule
import com.keepit.graph.GraphServiceClientModule
import com.keepit.heimdal.HeimdalServiceClientModule
import com.keepit.inject.{ CommonServiceModule, ConfigurationModule }
import com.keepit.integrity.DataIntegrityModule
import com.keepit.model.ProdSliderHistoryTrackerModule
import com.keepit.payments.ProdStripeClientModule
import com.keepit.queue.{ LibrarySuggestedSearchQueueModule, NormalizationUpdateJobQueueModule }
import com.keepit.reports._
import com.keepit.rover.RoverServiceClientModule
import com.keepit.search.SearchServiceClientModule
import com.keepit.shoebox.cron.ActivityEmailCronModule
import com.keepit.slack.ProdSlackClientModule
import com.keepit.social.SecureSocialModule

case class ShoeboxServiceTypeModule() extends ServiceTypeModule {
  val serviceType = ServiceType.SHOEBOX
  val servicesToListenOn = ServiceType.SEARCH :: ServiceType.ELIZA :: ServiceType.HEIMDAL :: ServiceType.ABOOK :: ServiceType.CORTEX :: ServiceType.GRAPH :: ServiceType.ROVER :: Nil
}

trait ShoeboxModule extends ConfigurationModule with CommonServiceModule {
  //these are modules that inheriting modules need to provide
  val secureSocialModule: SecureSocialModule
  val oauthModule: OAuthConfigurationModule
  val userActionsModule: UserActionsModule
  val mailModule: MailModule
  val reaperModule: ReaperModule
  val siteMapModule: SiteMapGeneratorModule
  val storeModule: ShoeboxStoreModule
  val normalizationQueueModule: NormalizationUpdateJobQueueModule

  // Shoebox Functional Modules
  val analyticsModule: AnalyticsModule
  val cacheModule: ShoeboxCacheModule
  val fjMonitorModule: ForkJoinContextMonitorModule
  val twilioCredentialsModule: TwilioCredentialsModule
  val dataPipelineExecutorModule: DataPipelineExecutorModule
  val activityEmailActorModule: ActivityEmailQueueModule
  val suggestedSearchTermsModule: LibrarySuggestedSearchQueueModule

  //these are modules that are provided here (but can be overriden by inheriting modules)
  // Service clients
  val serviceTypeModule = ShoeboxServiceTypeModule()
  val searchServiceClientModule: SearchServiceClientModule
  val shoeboxServiceClientModule: ShoeboxServiceClientModule //why do we need the shoeboxServiceClientModule here?
  val elizaServiceClientModule: ElizaServiceClientModule
  val heimdalServiceClientModule: HeimdalServiceClientModule
  val abookServiceClientModule: ABookServiceClientModule
  val cortexServiceClientModule: CortexServiceClientModule
  val graphServiceClientModule: GraphServiceClientModule
  val roverServiceClientModule: RoverServiceClientModule

  val abuseControlModule = AbuseControlModule()
  val slickModule = ShoeboxSlickModule()
  val socialGraphModule = ProdSocialGraphModule()
  val sliderHistoryTrackerModule = ProdSliderHistoryTrackerModule()
  val geckoboardModule = GeckoboardModule()
  val dataIntegrityModule = DataIntegrityModule()
  val keepImportsModule = KeepImportsModule()
  val stripeClientModule = ProdStripeClientModule()

  val dbSequencingModule = ShoeboxDbSequencingModule()

  val mailerModule = PlayMailerModule()

  val activityEmailCronModule = ActivityEmailCronModule()

  val shoeboxTasksModule: ShoeboxTasksPluginModule = ShoeboxTasksPluginModule()
}
