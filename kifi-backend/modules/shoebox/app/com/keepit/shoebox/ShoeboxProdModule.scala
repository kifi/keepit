package com.keepit.shoebox

import com.keepit.abook.ProdABookServiceClientModule
import com.keepit.commanders.emails.activity.ProdActivityEmailQueueModule
import com.keepit.common.cache.{ EhCacheCacheModule, HashMapMemoryCacheModule, ShoeboxCacheModule }
import com.keepit.common.controller.ProdShoeboxUserActionsModule
import com.keepit.common.oauth.ProdOAuthConfigurationModule
import com.keepit.common.seo.{ ProdSiteMapGeneratorModule }
import com.keepit.common.store.ShoeboxProdStoreModule
import com.keepit.controllers.internal.ProdDataPipelineExecutorModule
import com.keepit.cortex.ProdCortexServiceClientModule
import com.keepit.eliza.ProdElizaServiceClientModule
import com.keepit.graph.ProdGraphServiceClientModule
import com.keepit.heimdal.ProdHeimdalServiceClientModule
import com.keepit.rover.ProdRoverServiceClientModule
import com.keepit.search.ProdSearchServiceClientModule
import com.keepit.social.ProdShoeboxSecureSocialModule
import com.keepit.common.analytics.ProdAnalyticsModule
import com.keepit.common.mail.ProdMailModule
import com.keepit.inject.CommonProdModule
import com.keepit.common.integration.ProdReaperModule
import com.keepit.queue.{ ProdLibrarySuggestedSearchQueueModule, ProdNormalizationUpdateJobQueueModule }
import com.keepit.common.concurrent.ProdForkJoinContextMonitorModule

case class ShoeboxProdModule() extends ShoeboxModule with CommonProdModule {

  val secureSocialModule = ProdShoeboxSecureSocialModule()
  val oauthModule = ProdOAuthConfigurationModule()
  val userActionsModule = ProdShoeboxUserActionsModule()
  val mailModule = ProdMailModule()
  val reaperModule = ProdReaperModule()
  val siteMapModule = ProdSiteMapGeneratorModule()
  val storeModule = ShoeboxProdStoreModule()
  val normalizationQueueModule = ProdNormalizationUpdateJobQueueModule()
  val activityEmailActorModule = ProdActivityEmailQueueModule()
  val suggestedSearchTermsModule = ProdLibrarySuggestedSearchQueueModule()

  // Shoebox Functional Modules
  val analyticsModule = ProdAnalyticsModule()
  //topicModelModule = LdaTopicModelModule() //disable for now
  val fjMonitorModule = ProdForkJoinContextMonitorModule()
  val twilioCredentialsModule = ProdTwilioCredentialsModule()
  val dataPipelineExecutorModule = ProdDataPipelineExecutorModule()
  val cacheModule = ShoeboxCacheModule(EhCacheCacheModule())

  // Service clients
  val searchServiceClientModule = ProdSearchServiceClientModule()
  val shoeboxServiceClientModule = ProdShoeboxServiceClientModule()
  val elizaServiceClientModule = ProdElizaServiceClientModule()
  val heimdalServiceClientModule = ProdHeimdalServiceClientModule()
  val abookServiceClientModule = ProdABookServiceClientModule()
  val cortexServiceClientModule = ProdCortexServiceClientModule()
  val graphServiceClientModule = ProdGraphServiceClientModule()
  val roverServiceClientModule = ProdRoverServiceClientModule()
}
