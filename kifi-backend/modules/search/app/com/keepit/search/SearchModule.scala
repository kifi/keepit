package com.keepit.search

import com.keepit.common.cache.CacheModule
import com.keepit.social.SecureSocialModule
import com.keepit.shoebox.ShoeboxServiceClientModule
import net.codingwell.scalaguice.ScalaModule
import com.keepit.model.{BrowsingHistoryModule, ClickHistoryModule}
import com.keepit.module.{ConfigurationModule, ActorSystemModule, DiscoveryModule}
import com.keepit.common.healthcheck.HealthCheckModule
import com.keepit.common.store.StoreModule
import com.keepit.common.net.HttpClientModule
import com.keepit.inject.FortyTwoModule

abstract class SearchModule(

  // Common Functional Modules
  val fortyTwoModule: FortyTwoModule,
  val cacheModule: CacheModule,
  val secureSocialModule: SecureSocialModule,
  val shoeboxServiceClientModule: ShoeboxServiceClientModule,
  val clickHistoryModule: ClickHistoryModule,
  val browsingHistoryModule: BrowsingHistoryModule,
  val actorSystemModule: ActorSystemModule,
  val discoveryModule: DiscoveryModule,
  val healthCheckModule: HealthCheckModule,
  val storeModule: StoreModule,
  val httpClientModule: HttpClientModule,


  // Search Functional Modules
  val indexModule: IndexModule,
  val searchConfigModule: SearchConfigModule,
  val resultFeedbackModule: ResultFeedbackModule

) extends ConfigurationModule(
    fortyTwoModule,
    cacheModule,
    secureSocialModule,
    shoeboxServiceClientModule,
    clickHistoryModule,
    browsingHistoryModule,
    actorSystemModule,
    discoveryModule,
    healthCheckModule,
    storeModule,
    httpClientModule,

    indexModule,
    searchConfigModule,
    resultFeedbackModule
)
