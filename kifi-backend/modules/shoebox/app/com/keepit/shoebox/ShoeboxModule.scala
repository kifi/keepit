package com.keepit.shoebox

import net.codingwell.scalaguice.ScalaModule
import com.keepit.common.db.SlickModule
import com.keepit.common.cache.CacheModule
import com.keepit.social.SecureSocialModule
import com.keepit.search.SearchServiceClientModule
import com.keepit.common.mail.MailModule
import com.keepit.common.social.SocialGraphModule
import com.keepit.common.analytics.AnalyticsModule
import com.keepit.learning.topicmodel.TopicModelModule
import com.keepit.model.{BrowsingHistoryModule, ClickHistoryModule, SliderHistoryTrackerModule}
import com.keepit.scraper.ScraperModule
import com.keepit.realtime.WebSocketModule
import com.keepit.classify.DomainTagImporterModule
import com.keepit.common.crypto.CryptoModule
import com.keepit.module.{ConfigurationModule, DiscoveryModule, ActorSystemModule}
import com.keepit.common.healthcheck.HealthCheckModule
import com.keepit.common.store.StoreModule
import com.keepit.common.net.HttpClientModule
import com.keepit.inject.FortyTwoModule

abstract class ShoeboxModule(
  // Common Functional Modules
  val fortyTwoModule: FortyTwoModule,
  val cacheModule: CacheModule,
  val secureSocialModule: SecureSocialModule,
  val searchServiceClientModule: SearchServiceClientModule,
  val clickHistoryModule: ClickHistoryModule,
  val browsingHistoryModule: BrowsingHistoryModule,
  val mailModule: MailModule,
  val cryptoModule: CryptoModule,
  val storeModule: StoreModule,
  val actorSystemModule: ActorSystemModule,
  val discoveryModule: DiscoveryModule,
  val healthCheckModule: HealthCheckModule,
  val httpClientModule: HttpClientModule,

  // Shoebox Functional Modules
  val slickModule: SlickModule,
  val scraperModule: ScraperModule,
  val socialGraphModule: SocialGraphModule,
  val analyticsModule: AnalyticsModule,
  val webSocketModule: WebSocketModule,
  val topicModelModule: TopicModelModule,
  val domainTagImporterModule: DomainTagImporterModule,
  val sliderHistoryTrackerModule: SliderHistoryTrackerModule,
  val userIndexModule: UserIndexModule = UserIndexModule()

) extends ConfigurationModule(
    fortyTwoModule,
    cacheModule,
    secureSocialModule,
    searchServiceClientModule,
    clickHistoryModule,
    browsingHistoryModule,
    mailModule,
    cryptoModule,
    storeModule,
    actorSystemModule,
    discoveryModule,
    healthCheckModule,
    httpClientModule,

    slickModule,
    scraperModule,
    socialGraphModule,
    analyticsModule,
    webSocketModule,
    topicModelModule,
    domainTagImporterModule,
    sliderHistoryTrackerModule,
    userIndexModule
)
