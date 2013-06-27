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
import com.keepit.model.SliderHistoryTrackerModule
import com.keepit.scraper.ScraperModule
import com.keepit.realtime.WebSocketModule
import com.keepit.classify.DomainTagImporterModule
import com.keepit.common.crypto.CryptoModule
import com.keepit.common.healthcheck.HealthCheckModule
import com.keepit.common.store.S3Module

abstract class ShoeboxModule(
  // Common Functional Modules
  val cacheModule: CacheModule,
  val secureSocialModule: SecureSocialModule,
  val searchServiceClientModule: SearchServiceClientModule,
  val clickHistoryTrackerModule: ClickHistoryTrackerModule,
  val browsingHistoryTrackerModule: BrowsingHistoryTrackerModule,
  val mailModule: MailModule,
  val cryptoModule: CryptoModule,
  val healthCheckModule: HealthCheckModule,
  val s3Module: S3Module,

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

) extends ScalaModule {
  def configure {
    println("Configuring ShoeboxModule")

    install(cacheModule)
    install(secureSocialModule)
    install(searchServiceClientModule)
    install(clickHistoryTrackerModule)
    install(browsingHistoryTrackerModule)
    install(mailModule)
    install(cryptoModule)
    install(healthCheckModule)
    install(s3Module)

    install(slickModule)
    install(scraperModule)
    install(socialGraphModule)
    install(analyticsModule)
    install(webSocketModule)
    install(topicModelModule)
    install(domainTagImporterModule)
    install(sliderHistoryTrackerModule)
    install(userIndexModule)
  }
}
