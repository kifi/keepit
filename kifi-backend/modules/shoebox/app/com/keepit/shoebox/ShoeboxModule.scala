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
import com.keepit.common.store.S3Module
import com.keepit.module.{DiscoveryModule, ActorSystemModule}

abstract class ShoeboxModule(
  // Common Functional Modules
  val cacheModule: CacheModule,
  val secureSocialModule: SecureSocialModule,
  val searchServiceClientModule: SearchServiceClientModule,
  val clickHistoryModule: ClickHistoryModule,
  val browsingHistoryModule: BrowsingHistoryModule,
  val mailModule: MailModule,
  val cryptoModule: CryptoModule,
  val s3Module: S3Module,
  val actorSystemModule: ActorSystemModule,
  val discoveryModule: DiscoveryModule,

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
  final def configure() {
    println(s"Configuring ${this}")

    install(cacheModule)
    install(secureSocialModule)
    install(searchServiceClientModule)
    install(clickHistoryModule)
    install(browsingHistoryModule)
    install(mailModule)
    install(cryptoModule)
    install(s3Module)
    install(actorSystemModule)
    install(discoveryModule)

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
